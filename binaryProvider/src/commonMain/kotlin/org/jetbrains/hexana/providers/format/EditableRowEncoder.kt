package org.jetbrains.hexana.providers.format

import kotlin.io.encoding.Base64
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord

/**
 * Inverse of [WatMnemonics]: takes a row's edited WAT text plus the
 * original opcode and byte location, returns the [JournalOp] list that
 * applies the edit.
 *
 * **Phase C.2 scope — length-preserving only.** If the canonical encoding
 * of the user's new value has a different byte count than the original
 * row's bytes, [encode] returns [RowEncodeResult.Error] with the
 * `requiresCascade = true` flag set. Phase C.5 will pair this encoder with
 * function-body and section-size cascade ops; until then, length-changing
 * edits are surfaced to the user as a clear error rather than silently
 * producing a malformed binary.
 *
 * **Mnemonic-preserving only.** The user can edit the immediate values of
 * an op but cannot change the op kind itself (e.g. `local.get 5` →
 * `local.set 5` is rejected even though both encode to the same shape).
 * Mnemonic swaps land in a follow-up phase if needed.
 *
 * **Subset.** The accepted ops match [EditableRows.isEditable]: numeric
 * constants, local/global access, direct call, conditional/unconditional
 * branches, memory load/store with MemArg.
 */
public object EditableRowEncoder {

    /**
     * Encodes the edited row.
     *
     * @param op the original opcode parsed from the binary at [opStartOffset]
     * @param opStartOffset logical offset of the opcode byte in the document
     * @param opEncodedLength current encoded byte length of the row (opcode + immediates)
     * @param text user-edited WAT text for the row (mnemonic + immediates;
     *   leading/trailing whitespace and trailing `(; … ;)` annotations are
     *   tolerated)
     */
    public fun encode(
        op: OpCode,
        opStartOffset: Long,
        opEncodedLength: Int,
        text: String,
        localCount: Int? = null,
        cascade: CascadeContext? = null,
    ): RowEncodeResult {
        @Suppress("UNUSED_PARAMETER") op
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return RowEncodeResult.Error("row text is empty")

        val mnemonic = tokens[0]
        val rest = tokens.drop(1)

        val newBytes = encodeByMnemonic(mnemonic, rest, localCount)

        return when (newBytes) {
            is BytesResult.Success -> {
                if (newBytes.bytes.size == opEncodedLength) {
                    // Length-preserving — single op, no cascade needed.
                    RowEncodeResult.Success(
                        ops = listOf(
                            JournalOp.Replace(
                                start = opStartOffset,
                                end = opStartOffset + opEncodedLength.toLong(),
                                data = Base64.encode(newBytes.bytes),
                            ),
                        ),
                    )
                } else if (cascade == null) {
                    RowEncodeResult.Error(
                        message = "edit changes encoded length from $opEncodedLength to " +
                            "${newBytes.bytes.size} bytes; no cascade context available",
                        requiresCascade = true,
                    )
                } else {
                    RowEncodeResult.Success(
                        ops = computeCascadeOps(
                            cascade = cascade,
                            rowStart = opStartOffset,
                            oldRowLength = opEncodedLength,
                            newRowBytes = newBytes.bytes,
                        ),
                    )
                }
            }
            is BytesResult.Failure -> RowEncodeResult.Error(newBytes.message)
        }
    }

    /**
     * Encodes a NEW instruction inserted at [insertOffset]. Same mnemonic
     * dispatch as [encode]; produces a [JournalOp.Insert] (not Replace)
     * plus the funcSize/sectionSize cascade ops for the bytes shifted by
     * the insertion.
     *
     * Used by the panel's "press Enter on a row → empty insert row appears
     * below" UX (Phase D.3). The user types an instruction in the empty
     * row; on commit this method produces the ops.
     */
    public fun encodeInsert(
        insertOffset: Long,
        text: String,
        localCount: Int? = null,
        cascade: CascadeContext? = null,
    ): RowEncodeResult {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return RowEncodeResult.Error("row text is empty")
        val mnemonic = tokens[0]
        val rest = tokens.drop(1)
        val newBytes = encodeByMnemonic(mnemonic, rest, localCount)
        return when (newBytes) {
            is BytesResult.Success -> {
                if (cascade == null) {
                    RowEncodeResult.Error(
                        message = "no cascade context available for inserted instruction",
                        requiresCascade = true,
                    )
                } else {
                    RowEncodeResult.Success(
                        ops = computeInsertCascadeOps(cascade, insertOffset, newBytes.bytes),
                    )
                }
            }
            is BytesResult.Failure -> RowEncodeResult.Error(newBytes.message)
        }
    }

    /**
     * Three-op cascade for a pure insertion (delta = full inserted
     * length). Same shape as [computeCascadeOps] but the row op is Insert
     * rather than Replace, and rowDelta = newRowBytes.size (no original
     * bytes to subtract).
     */
    private fun computeInsertCascadeOps(
        cascade: CascadeContext,
        insertOffset: Long,
        newRowBytes: ByteArray,
    ): List<JournalOp> {
        val rowDelta = newRowBytes.size
        val newBodyLength = cascade.currentBodyLength + rowDelta
        val newFuncSizeBytes = encodeULEB128(newBodyLength)
        val funcSizeDelta = newFuncSizeBytes.size - cascade.funcSizePrefixLength
        val newSectionLength = cascade.currentSectionLength + rowDelta + funcSizeDelta
        val newSectionSizeBytes = encodeULEB128(newSectionLength)
        return listOf(
            JournalOp.Insert(
                offset = insertOffset,
                data = Base64.encode(newRowBytes),
            ),
            JournalOp.Replace(
                start = cascade.funcSizePrefixStart,
                end = cascade.funcSizePrefixStart + cascade.funcSizePrefixLength.toLong(),
                data = Base64.encode(newFuncSizeBytes),
            ),
            JournalOp.Replace(
                start = cascade.sectionSizePrefixStart,
                end = cascade.sectionSizePrefixStart + cascade.sectionSizePrefixLength.toLong(),
                data = Base64.encode(newSectionSizeBytes),
            ),
        )
    }

    /**
     * Encodes a `(local <type>...)` declaration row. The user types a
     * flat list of value types (e.g. `(local i32 i32 i64)`); the encoder
     * RLE-compresses consecutive same types and emits the binary form
     * `<groupCount: ULEB128> [<count: ULEB128> <type: byte>] * groupCount`.
     *
     * Length-changing edits cascade through funcSize + sectionSize via
     * the same path as opcode-row edits.
     *
     * @param opStartOffset byte offset of the locals declaration's first
     *   byte in the document (= entryStart + funcSize-ULEB128 length).
     * @param opEncodedLength current encoded byte length of the locals
     *   declaration.
     * @param text user-edited WAT text for the row, e.g. `(local i32 i64)`
     *   or `(local)`. Trailing `(; … ;)` annotations and surrounding
     *   whitespace are tolerated.
     * @param cascade surrounding-structure context for funcSize +
     *   sectionSize cascade ops; null disables cascade and length-changing
     *   edits return Error.
     */
    public fun encodeLocals(
        opStartOffset: Long,
        opEncodedLength: Int,
        text: String,
        cascade: CascadeContext? = null,
    ): RowEncodeResult {
        val parsed = parseLocalsDecl(text)
            ?: return RowEncodeResult.Error("invalid (local ...) syntax")
        val newBytes = encodeLocalsBytes(parsed)
        if (newBytes.size == opEncodedLength) {
            return RowEncodeResult.Success(
                ops = listOf(
                    JournalOp.Replace(
                        start = opStartOffset,
                        end = opStartOffset + opEncodedLength.toLong(),
                        data = Base64.encode(newBytes),
                    ),
                ),
            )
        }
        if (cascade == null) {
            return RowEncodeResult.Error(
                message = "edit changes encoded length from $opEncodedLength to " +
                    "${newBytes.size} bytes; no cascade context available",
                requiresCascade = true,
            )
        }
        return RowEncodeResult.Success(
            ops = computeCascadeOps(
                cascade = cascade,
                rowStart = opStartOffset,
                oldRowLength = opEncodedLength,
                newRowBytes = newBytes,
            ),
        )
    }

    /**
     * Parses `(local <type>...)` into a flat list of value-type bytes.
     * Returns `null` on syntax error.
     *
     * Recognized types: i32 (0x7F), i64 (0x7E), f32 (0x7D), f64 (0x7C).
     * Empty body — `(local)` — produces an empty list. Leading/trailing
     * whitespace and trailing `(; … ;)` annotations are tolerated.
     */
    private fun parseLocalsDecl(text: String): List<Byte>? {
        val tokens = tokenizeWat(text)
        if (tokens.size < 3) return null
        if (tokens.first() != "(" || tokens[1] != "local") return null
        if (tokens.last() != ")") return null
        val out = ArrayList<Byte>()
        for (i in 2 until tokens.size - 1) {
            val typeByte = when (tokens[i]) {
                "i32" -> 0x7F.toByte()
                "i64" -> 0x7E.toByte()
                "f32" -> 0x7D.toByte()
                "f64" -> 0x7C.toByte()
                else -> return null
            }
            out += typeByte
        }
        return out
    }

    /**
     * RLE-compresses [types] into the WASM binary form for a code-entry
     * locals declaration: `<groupCount: ULEB128>` followed by, for each
     * run of consecutive identical types, `<count: ULEB128> <type: byte>`.
     */
    private fun encodeLocalsBytes(types: List<Byte>): ByteArray {
        if (types.isEmpty()) return byteArrayOf(0x00) // zero groups
        val groups = ArrayList<Pair<Int, Byte>>()
        var i = 0
        while (i < types.size) {
            val t = types[i]
            var count = 0
            while (i < types.size && types[i] == t) {
                count++
                i++
            }
            groups += count to t
        }
        val out = ArrayList<Byte>()
        out += encodeULEB128(groups.size.toLong()).toList()
        for ((count, type) in groups) {
            out += encodeULEB128(count.toLong()).toList()
            out += type
        }
        return out.toByteArray()
    }

    /**
     * Paren-aware tokenizer for s-expression WAT syntax. Splits on
     * whitespace AND treats `(` and `)` as standalone tokens. Used for
     * locals declarations; opcode rows stick with the simpler [tokenize].
     */
    private fun tokenizeWat(text: String): List<String> {
        val noComments = COMMENT.replace(text, "")
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in noComments) {
            when {
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                    }
                }
                c == '(' || c == ')' -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                    }
                    out += c.toString()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /**
     * Builds the three-op cascade for a length-changing edit:
     *
     * 1. Replace the row bytes (inside the function body).
     * 2. Replace the function body's size ULEB128 prefix with the new
     *    canonical encoding of the post-edit body length.
     * 3. Replace the code section's outer size ULEB128 prefix similarly.
     *
     * Returned in this order. Each op uses ORIGINAL logical offsets (the
     * row is inside the body, the funcSize prefix is before the body, and
     * the sectionSize prefix is before the funcSize prefix — none overlaps
     * the others' affected range, so the offsets are stable across
     * intermediate states during journal replay).
     */
    private fun computeCascadeOps(
        cascade: CascadeContext,
        rowStart: Long,
        oldRowLength: Int,
        newRowBytes: ByteArray,
    ): List<JournalOp> {
        val rowDelta = newRowBytes.size - oldRowLength
        val newBodyLength = cascade.currentBodyLength + rowDelta
        val newFuncSizeBytes = encodeULEB128(newBodyLength)
        val funcSizeDelta = newFuncSizeBytes.size - cascade.funcSizePrefixLength
        val newSectionLength = cascade.currentSectionLength + rowDelta + funcSizeDelta
        val newSectionSizeBytes = encodeULEB128(newSectionLength)
        return listOf(
            JournalOp.Replace(
                start = rowStart,
                end = rowStart + oldRowLength.toLong(),
                data = Base64.encode(newRowBytes),
            ),
            JournalOp.Replace(
                start = cascade.funcSizePrefixStart,
                end = cascade.funcSizePrefixStart + cascade.funcSizePrefixLength.toLong(),
                data = Base64.encode(newFuncSizeBytes),
            ),
            JournalOp.Replace(
                start = cascade.sectionSizePrefixStart,
                end = cascade.sectionSizePrefixStart + cascade.sectionSizePrefixLength.toLong(),
                data = Base64.encode(newSectionSizeBytes),
            ),
        )
    }

    // -- Append + redirect path (Phase E.1) -----------------------------------

    /**
     * Length-changing function-body edit via **append + redirect** instead of
     * in-place cascade. For length-changing edits this is simpler than the
     * existing 3-op cascade because it avoids mid-section byte shifts: all
     * structural ops are at section boundaries.
     *
     * The algorithm:
     * 1. Assembles the new function body by splicing [newRowBytes] over the
     *    bytes at [[rowStart], [rowStart]+[oldRowLength]) inside the original
     *    code entry's body.
     * 2. Appends the new body as a fresh function entry at the end of the code
     *    section, with a matching new typeidx appended at the end of the
     *    function section. The new function gets absolute funcidx
     *    `importFuncCount + oldCodeEntryCount`.
     * 3. Overwrites the original code entry's body with a **redirect stub**
     *    (exactly the same number of bytes as the original body): the locals
     *    declaration is kept verbatim; the opcode area becomes
     *    `local.get 0 ... local.get (p-1) ; call $newIdx ; nop... ; end`, where
     *    `p` is the param count of the function's type.
     *
     * **Fallback contract.** If the redirect stub does not fit (original body
     * is too small — requires `2*p + 1 + uleb_len(newFuncIdx) + 1` bytes
     * minimum in the opcode area), returns [RowEncodeResult.Error] with a
     * "body too small for redirect stub" message. The panel can route those
     * edits through the existing in-place cascade path.
     *
     * @param provider the parsed WASM binary (reads original body bytes and
     *   section structural metadata).
     * @param chunk the code-section [BinaryChunk.Element] being edited;
     *   [BinaryChunk.Element.indexInSection] identifies the code entry.
     * @param rowStart logical byte offset of the row being edited.
     * @param oldRowLength current encoded byte length of the row.
     * @param newRowBytes the user's new bytes for the row (the replacement).
     */
    /**
     * Convenience overload for the panel: takes the user's edited text and
     * a localCount (for `local.{get,set,tee}` index validation). Tokenizes
     * + encodes via [encodeByMnemonic], then routes to the byte-array form
     * of [encodeAppendFunctionEdit]. Returns the same shape of result —
     * `Error("body too small for redirect stub")` when the stub doesn't
     * fit, which the caller treats as the signal to fall back to the
     * existing in-place cascade.
     */
    public fun encodeAppendFunctionEditFromText(
        provider: WasmBinaryProvider,
        chunk: BinaryChunk.Element,
        rowStart: Long,
        oldRowLength: Int,
        text: String,
        localCount: Int? = null,
    ): RowEncodeResult {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return RowEncodeResult.Error("row text is empty")
        val mnemonic = tokens[0]
        val rest = tokens.drop(1)
        val newBytes = encodeByMnemonic(mnemonic, rest, localCount)
        return when (newBytes) {
            is BytesResult.Success -> encodeAppendFunctionEdit(
                provider = provider,
                chunk = chunk,
                rowStart = rowStart,
                oldRowLength = oldRowLength,
                newRowBytes = newBytes.bytes,
            )
            is BytesResult.Failure -> RowEncodeResult.Error(newBytes.message)
        }
    }

    public fun encodeAppendFunctionEdit(
        provider: WasmBinaryProvider,
        chunk: BinaryChunk.Element,
        rowStart: Long,
        oldRowLength: Int,
        newRowBytes: ByteArray,
    ): RowEncodeResult {
        val wasmModule = provider.wasmModule
            ?: return RowEncodeResult.Error("no parsed WASM module available")
        val codeSection = wasmModule.codeSection
            ?: return RowEncodeResult.Error("module has no code section")
        val funcSection = wasmModule.functionSection
            ?: return RowEncodeResult.Error("module has no function section")
        val typesSection = wasmModule.typesSection
            ?: return RowEncodeResult.Error("module has no type section")

        val codeEntryIndex = chunk.indexInSection

        // --- 1. Read funcSize prefix and locate body boundaries. ---

        val entryStart = chunk.byteRange.first
        val buf = provider.bytes  // independent cursor
        buf.position(entryStart.toInt())
        val rawFuncSize = buf.getULEB128()       // value = bodyLength
        val entrySizePrefixLen = buf.position() - entryStart.toInt()
        val bodyLength = rawFuncSize.toInt()
        val bodyStart = entryStart + entrySizePrefixLen.toLong()  // first byte of body

        // --- 2. Read locals declaration and locate opcode area. ---

        val localsBytes: ByteArray
        val opcodeAreaStart: Long
        run {
            val preLocals = buf.position()   // = bodyStart.toInt()
            val localsGroupCount = buf.getULEB128().toInt()
            repeat(localsGroupCount) {
                buf.getULEB128()   // run count in group
                buf.get()          // type byte
            }
            val localsDeclarationLength = buf.position() - preLocals
            opcodeAreaStart = bodyStart + localsDeclarationLength.toLong()
            localsBytes = ByteArray(localsDeclarationLength)
            for (i in 0 until localsDeclarationLength) localsBytes[i] = buf[preLocals + i]
        }

        // --- 3. Validate that rowStart is in the opcode area, not locals. ---

        if (rowStart < opcodeAreaStart) {
            return RowEncodeResult.Error(
                "row at offset $rowStart is inside the locals declaration " +
                    "(opcode area starts at $opcodeAreaStart); use encodeLocals for this row",
            )
        }
        val bodyEndExclusive = bodyStart + bodyLength.toLong()
        if (rowStart + oldRowLength.toLong() > bodyEndExclusive) {
            return RowEncodeResult.Error(
                "row [$rowStart, ${rowStart + oldRowLength}) extends beyond the function body " +
                    "(body ends at $bodyEndExclusive)",
            )
        }

        // --- 4. Assemble the new function body. ---

        val rowStartInBody = (rowStart - bodyStart).toInt()
        val originalBodyBytes = ByteArray(bodyLength)
        for (i in 0 until bodyLength) originalBodyBytes[i] = buf[bodyStart.toInt() + i]
        val newBody = originalBodyBytes.copyOfRange(0, rowStartInBody) +
            newRowBytes +
            originalBodyBytes.copyOfRange(rowStartInBody + oldRowLength, bodyLength)

        // --- 5. Look up param count from the type section. ---

        val funcTypeIdxs = with(funcSection) { provider.bytes.parseFunctions() }
        val typeidx = funcTypeIdxs.getOrNull(codeEntryIndex)
            ?: return RowEncodeResult.Error("code entry $codeEntryIndex has no matching typeidx")
        val typeRecords = with(typesSection) { provider.bytes.parseTypes() }
        val typeRecord = typeRecords.getOrNull(typeidx)
            ?: return RowEncodeResult.Error("typeidx $typeidx has no matching type record")
        val paramCount = when (typeRecord) {
            is WasmSectionFunctionTypeRecord -> typeRecord.params.size
            else -> return RowEncodeResult.Error(
                "typeidx $typeidx maps to a non-functype record (${typeRecord::class.simpleName}); " +
                    "cannot compute param count for redirect stub",
            )
        }

        // --- 6. Compute new funcidx and redirect stub. ---

        val importFuncCount = provider.importFuncCount
        val codeEntryCount = provider.codeOffsets.size
        val newFuncIdx = importFuncCount + codeEntryCount  // one past the last current entry

        val callImmediate = encodeULEB128(newFuncIdx.toLong())
        // Minimum opcode-area bytes needed: 2 per param + 1 (call opcode) + |callImmediate| + 1 (end)
        val redirectOpcodeSize = 2 * paramCount + 1 + callImmediate.size + 1
        val nopPad = bodyLength - localsBytes.size - redirectOpcodeSize
        if (nopPad < 0) {
            return RowEncodeResult.Error(
                "body too small for redirect stub; in-place edit required " +
                    "(body=$bodyLength bytes, stub needs ${localsBytes.size + redirectOpcodeSize})",
            )
        }

        val redirectStub = buildRedirectStub(localsBytes, paramCount, callImmediate, nopPad)
        check(redirectStub.size == bodyLength) {
            "redirect stub size (${redirectStub.size}) must equal bodyLength ($bodyLength)"
        }

        // --- 7. Assemble the new function entry bytes (funcSize ULEB + new body). ---

        val newBodySizeBytes = encodeULEB128(newBody.size.toLong())
        val newFuncEntry = newBodySizeBytes + newBody

        // --- 8. Assemble the new typeidx bytes (same type as original). ---

        val newTypeidxBytes = encodeULEB128(typeidx.toLong())

        // --- 9. Compute new function-section count and size. ---
        //
        // Note: we measure the EXISTING count ULEB128 by reading it from
        // the file (not by re-encoding the value canonically). Some WASM
        // toolchains emit non-canonical multi-byte ULEBs for small values;
        // if we assumed canonical encoding here our Replace op would
        // overwrite only the first byte and leave trailing continuation
        // bytes in place — corrupting subsequent section content. The size
        // ULEB128 has the same risk but we already read its actual length
        // from `contentOffset - sectionOffset - 1`.

        val funcCountReader = provider.bytes
        funcCountReader.position(funcSection.contentOffset)
        funcCountReader.getULEB128()
        val funcCountLen = funcCountReader.position() - funcSection.contentOffset

        val newFuncCountBytes = encodeULEB128((codeEntryCount + 1).toLong())
        val funcCountDelta = newFuncCountBytes.size - funcCountLen
        val newFuncContentSize = funcSection.contentSize + newTypeidxBytes.size + funcCountDelta
        val newFuncSectionSizeBytes = encodeULEB128(newFuncContentSize.toLong())

        // --- 10. Compute new code-section count and size. ---

        val codeCountReader = provider.bytes
        codeCountReader.position(codeSection.contentOffset)
        codeCountReader.getULEB128()
        val codeCountLen = codeCountReader.position() - codeSection.contentOffset

        val newCodeCountBytes = encodeULEB128((codeEntryCount + 1).toLong())
        val codeCountDelta = newCodeCountBytes.size - codeCountLen
        val newCodeContentSize = codeSection.contentSize + newFuncEntry.size + codeCountDelta
        val newCodeSectionSizeBytes = encodeULEB128(newCodeContentSize.toLong())

        // --- 11. Assemble the 7 ops in descending byte-position order. ---

        val codeContentEnd = (codeSection.contentOffset + codeSection.contentSize).toLong()
        val codeCountOffset = codeSection.contentOffset.toLong()
        val codeSizePrefixStart = (codeSection.sectionOffset + 1).toLong()
        val codeSizePrefixLen = codeSection.contentOffset - codeSection.sectionOffset - 1
        val funcContentEnd = (funcSection.contentOffset + funcSection.contentSize).toLong()
        val funcCountOffset = funcSection.contentOffset.toLong()
        val funcSizePrefixStart = (funcSection.sectionOffset + 1).toLong()
        val funcSizePrefixLen = funcSection.contentOffset - funcSection.sectionOffset - 1

        val ops = listOf(
            // Op 1 (highest offset): append new function body to code section end.
            JournalOp.Insert(
                offset = codeContentEnd,
                data = Base64.encode(newFuncEntry),
            ),
            // Op 2: overwrite original body with redirect stub (length-preserving).
            JournalOp.Replace(
                start = bodyStart,
                end = bodyStart + bodyLength.toLong(),
                data = Base64.encode(redirectStub),
            ),
            // Op 3: update code-section entry count.
            JournalOp.Replace(
                start = codeCountOffset,
                end = codeCountOffset + codeCountLen.toLong(),
                data = Base64.encode(newCodeCountBytes),
            ),
            // Op 4: update code-section outer size ULEB128.
            JournalOp.Replace(
                start = codeSizePrefixStart,
                end = codeSizePrefixStart + codeSizePrefixLen.toLong(),
                data = Base64.encode(newCodeSectionSizeBytes),
            ),
            // Op 5: append typeidx to function section end.
            JournalOp.Insert(
                offset = funcContentEnd,
                data = Base64.encode(newTypeidxBytes),
            ),
            // Op 6: update function-section entry count.
            JournalOp.Replace(
                start = funcCountOffset,
                end = funcCountOffset + funcCountLen.toLong(),
                data = Base64.encode(newFuncCountBytes),
            ),
            // Op 7 (lowest offset): update function-section outer size ULEB128.
            JournalOp.Replace(
                start = funcSizePrefixStart,
                end = funcSizePrefixStart + funcSizePrefixLen.toLong(),
                data = Base64.encode(newFuncSectionSizeBytes),
            ),
        )

        return RowEncodeResult.Success(ops)
    }

    /**
     * Assembles the redirect stub that replaces the original function body.
     * Structure: localsBytes ++ (local.get 0 ... local.get p-1) ++
     * (call newFuncIdx) ++ (nop * nopPad) ++ end.
     */
    private fun buildRedirectStub(
        localsBytes: ByteArray,
        paramCount: Int,
        callImmediate: ByteArray,
        nopPad: Int,
    ): ByteArray {
        val out = ArrayList<Byte>(localsBytes.size + 2 * paramCount + 1 + callImmediate.size + nopPad + 1)
        out += localsBytes.toList()
        for (i in 0 until paramCount) {
            out += 0x20.toByte()                           // local.get opcode
            out += encodeULEB128(i.toLong()).toList()       // param index ULEB128
        }
        out += 0x10.toByte()                               // call opcode
        out += callImmediate.toList()                      // funcidx ULEB128
        repeat(nopPad) { out += 0x01.toByte() }            // nop padding
        out += 0x0B.toByte()                               // end
        return out.toByteArray()
    }

    /**
     * Mnemonic-keyed dispatch. The user can type any supported mnemonic in
     * an editable row regardless of what op kind is currently there — this
     * is the "freedom" that lets `local.get` be swapped to `call`,
     * `i32.const` to `i32.add`, etc. Mnemonic kind is no longer pinned by
     * the original op.
     *
     * Coverage matches the renderer's printer (Phase C subset). Unsupported
     * mnemonics return an Error rather than producing garbage bytes.
     */
    @Suppress("ComplexMethod")
    private fun encodeByMnemonic(
        mnemonic: String,
        rest: List<String>,
        localCount: Int?,
    ): BytesResult = when (mnemonic) {
        // Numeric constants (SLEB128 / IEEE-754 immediates).
        "i32.const" -> encodeI32Const(rest)
        "i64.const" -> encodeI64Const(rest)
        "f32.const" -> encodeF32Const(rest)
        "f64.const" -> encodeF64Const(rest)

        // Variable / global access (single ULEB128 index).
        "local.get" -> encodeULebOp(opcode = 0x20, rest, maxIndex = localCount, indexLabel = "local")
        "local.set" -> encodeULebOp(opcode = 0x21, rest, maxIndex = localCount, indexLabel = "local")
        "local.tee" -> encodeULebOp(opcode = 0x22, rest, maxIndex = localCount, indexLabel = "local")
        "global.get" -> encodeULebOp(opcode = 0x23, rest)
        "global.set" -> encodeULebOp(opcode = 0x24, rest)

        // Direct call / branches.
        "call" -> encodeULebOp(opcode = 0x10, rest)
        "br" -> encodeULebOp(opcode = 0x0c, rest)
        "br_if" -> encodeULebOp(opcode = 0x0d, rest)

        // Memory load/store with MemArg.
        "i32.load" -> encodeMemArgOp(opcode = 0x28, rest, naturalAlign = 2)
        "i32.load8_s" -> encodeMemArgOp(opcode = 0x2c, rest, naturalAlign = 0)
        "i32.load8_u" -> encodeMemArgOp(opcode = 0x2d, rest, naturalAlign = 0)
        "i32.load16_s" -> encodeMemArgOp(opcode = 0x2e, rest, naturalAlign = 1)
        "i32.load16_u" -> encodeMemArgOp(opcode = 0x2f, rest, naturalAlign = 1)
        "i32.store" -> encodeMemArgOp(opcode = 0x36, rest, naturalAlign = 2)
        "i32.store8" -> encodeMemArgOp(opcode = 0x3a, rest, naturalAlign = 0)
        "i32.store16" -> encodeMemArgOp(opcode = 0x3b, rest, naturalAlign = 1)
        "i64.load" -> encodeMemArgOp(opcode = 0x29, rest, naturalAlign = 3)
        "i64.load8_s" -> encodeMemArgOp(opcode = 0x30, rest, naturalAlign = 0)
        "i64.load8_u" -> encodeMemArgOp(opcode = 0x31, rest, naturalAlign = 0)
        "i64.load16_s" -> encodeMemArgOp(opcode = 0x32, rest, naturalAlign = 1)
        "i64.load16_u" -> encodeMemArgOp(opcode = 0x33, rest, naturalAlign = 1)
        "i64.load32_s" -> encodeMemArgOp(opcode = 0x34, rest, naturalAlign = 2)
        "i64.load32_u" -> encodeMemArgOp(opcode = 0x35, rest, naturalAlign = 2)
        "i64.store" -> encodeMemArgOp(opcode = 0x37, rest, naturalAlign = 3)
        "i64.store8" -> encodeMemArgOp(opcode = 0x3c, rest, naturalAlign = 0)
        "i64.store16" -> encodeMemArgOp(opcode = 0x3d, rest, naturalAlign = 1)
        "i64.store32" -> encodeMemArgOp(opcode = 0x3e, rest, naturalAlign = 2)
        "f32.load" -> encodeMemArgOp(opcode = 0x2a, rest, naturalAlign = 2)
        "f32.store" -> encodeMemArgOp(opcode = 0x38, rest, naturalAlign = 2)
        "f64.load" -> encodeMemArgOp(opcode = 0x2b, rest, naturalAlign = 3)
        "f64.store" -> encodeMemArgOp(opcode = 0x39, rest, naturalAlign = 3)

        // Parametric / no-immediate control ops.
        "unreachable" -> encodeNoImmediate(rest, 0x00)
        "nop" -> encodeNoImmediate(rest, 0x01)
        "return" -> encodeNoImmediate(rest, 0x0F)
        "drop" -> encodeNoImmediate(rest, 0x1A)
        "select" -> encodeNoImmediate(rest, 0x1B)

        // Numeric: i32 arithmetic / logic / shift / rotate.
        "i32.clz" -> encodeNoImmediate(rest, 0x67)
        "i32.ctz" -> encodeNoImmediate(rest, 0x68)
        "i32.popcnt" -> encodeNoImmediate(rest, 0x69)
        "i32.add" -> encodeNoImmediate(rest, 0x6A)
        "i32.sub" -> encodeNoImmediate(rest, 0x6B)
        "i32.mul" -> encodeNoImmediate(rest, 0x6C)
        "i32.div_s" -> encodeNoImmediate(rest, 0x6D)
        "i32.div_u" -> encodeNoImmediate(rest, 0x6E)
        "i32.rem_s" -> encodeNoImmediate(rest, 0x6F)
        "i32.rem_u" -> encodeNoImmediate(rest, 0x70)
        "i32.and" -> encodeNoImmediate(rest, 0x71)
        "i32.or" -> encodeNoImmediate(rest, 0x72)
        "i32.xor" -> encodeNoImmediate(rest, 0x73)
        "i32.shl" -> encodeNoImmediate(rest, 0x74)
        "i32.shr_s" -> encodeNoImmediate(rest, 0x75)
        "i32.shr_u" -> encodeNoImmediate(rest, 0x76)
        "i32.rotl" -> encodeNoImmediate(rest, 0x77)
        "i32.rotr" -> encodeNoImmediate(rest, 0x78)

        // Numeric: i32 compare.
        "i32.eqz" -> encodeNoImmediate(rest, 0x45)
        "i32.eq" -> encodeNoImmediate(rest, 0x46)
        "i32.ne" -> encodeNoImmediate(rest, 0x47)
        "i32.lt_s" -> encodeNoImmediate(rest, 0x48)
        "i32.lt_u" -> encodeNoImmediate(rest, 0x49)
        "i32.gt_s" -> encodeNoImmediate(rest, 0x4A)
        "i32.gt_u" -> encodeNoImmediate(rest, 0x4B)
        "i32.le_s" -> encodeNoImmediate(rest, 0x4C)
        "i32.le_u" -> encodeNoImmediate(rest, 0x4D)
        "i32.ge_s" -> encodeNoImmediate(rest, 0x4E)
        "i32.ge_u" -> encodeNoImmediate(rest, 0x4F)

        // Numeric: i32 convert / extend / reinterpret.
        "i32.wrap_i64" -> encodeNoImmediate(rest, 0xA7)
        "i32.trunc_f32_s" -> encodeNoImmediate(rest, 0xA8)
        "i32.trunc_f32_u" -> encodeNoImmediate(rest, 0xA9)
        "i32.trunc_f64_s" -> encodeNoImmediate(rest, 0xAA)
        "i32.trunc_f64_u" -> encodeNoImmediate(rest, 0xAB)
        "i32.reinterpret_f32" -> encodeNoImmediate(rest, 0xBC)
        "i32.extend8_s" -> encodeNoImmediate(rest, 0xC0)
        "i32.extend16_s" -> encodeNoImmediate(rest, 0xC1)

        // Numeric: i32 trunc_sat (0xFC-prefixed, sub-opcode 0x00..0x03).
        "i32.trunc_sat_f32_s" -> encodeNoImmediate(rest, 0xFC, 0x00)
        "i32.trunc_sat_f32_u" -> encodeNoImmediate(rest, 0xFC, 0x01)
        "i32.trunc_sat_f64_s" -> encodeNoImmediate(rest, 0xFC, 0x02)
        "i32.trunc_sat_f64_u" -> encodeNoImmediate(rest, 0xFC, 0x03)

        // Numeric: i64 compare.
        "i64.eqz" -> encodeNoImmediate(rest, 0x50)
        "i64.eq" -> encodeNoImmediate(rest, 0x51)
        "i64.ne" -> encodeNoImmediate(rest, 0x52)
        "i64.lt_s" -> encodeNoImmediate(rest, 0x53)
        "i64.lt_u" -> encodeNoImmediate(rest, 0x54)
        "i64.gt_s" -> encodeNoImmediate(rest, 0x55)
        "i64.gt_u" -> encodeNoImmediate(rest, 0x56)
        "i64.le_s" -> encodeNoImmediate(rest, 0x57)
        "i64.le_u" -> encodeNoImmediate(rest, 0x58)
        "i64.ge_s" -> encodeNoImmediate(rest, 0x59)
        "i64.ge_u" -> encodeNoImmediate(rest, 0x5A)

        // Numeric: i64 arithmetic / logic / shift / rotate.
        "i64.clz" -> encodeNoImmediate(rest, 0x79)
        "i64.ctz" -> encodeNoImmediate(rest, 0x7A)
        "i64.popcnt" -> encodeNoImmediate(rest, 0x7B)
        "i64.add" -> encodeNoImmediate(rest, 0x7C)
        "i64.sub" -> encodeNoImmediate(rest, 0x7D)
        "i64.mul" -> encodeNoImmediate(rest, 0x7E)
        "i64.div_s" -> encodeNoImmediate(rest, 0x7F)
        "i64.div_u" -> encodeNoImmediate(rest, 0x80)
        "i64.rem_s" -> encodeNoImmediate(rest, 0x81)
        "i64.rem_u" -> encodeNoImmediate(rest, 0x82)
        "i64.and" -> encodeNoImmediate(rest, 0x83)
        "i64.or" -> encodeNoImmediate(rest, 0x84)
        "i64.xor" -> encodeNoImmediate(rest, 0x85)
        "i64.shl" -> encodeNoImmediate(rest, 0x86)
        "i64.shr_s" -> encodeNoImmediate(rest, 0x87)
        "i64.shr_u" -> encodeNoImmediate(rest, 0x88)
        "i64.rotl" -> encodeNoImmediate(rest, 0x89)
        "i64.rotr" -> encodeNoImmediate(rest, 0x8A)

        // Numeric: i64 convert / extend / reinterpret.
        "i64.extend_i32_s" -> encodeNoImmediate(rest, 0xAC)
        "i64.extend_i32_u" -> encodeNoImmediate(rest, 0xAD)
        "i64.trunc_f32_s" -> encodeNoImmediate(rest, 0xAE)
        "i64.trunc_f32_u" -> encodeNoImmediate(rest, 0xAF)
        "i64.trunc_f64_s" -> encodeNoImmediate(rest, 0xB0)
        "i64.trunc_f64_u" -> encodeNoImmediate(rest, 0xB1)
        "i64.reinterpret_f64" -> encodeNoImmediate(rest, 0xBD)
        "i64.extend8_s" -> encodeNoImmediate(rest, 0xC2)
        "i64.extend16_s" -> encodeNoImmediate(rest, 0xC3)
        "i64.extend32_s" -> encodeNoImmediate(rest, 0xC4)

        // Numeric: i64 trunc_sat (0xFC-prefixed, sub-opcode 0x04..0x07).
        "i64.trunc_sat_f32_s" -> encodeNoImmediate(rest, 0xFC, 0x04)
        "i64.trunc_sat_f32_u" -> encodeNoImmediate(rest, 0xFC, 0x05)
        "i64.trunc_sat_f64_s" -> encodeNoImmediate(rest, 0xFC, 0x06)
        "i64.trunc_sat_f64_u" -> encodeNoImmediate(rest, 0xFC, 0x07)

        // Numeric: f32 arithmetic / compare / convert / reinterpret.
        "f32.abs" -> encodeNoImmediate(rest, 0x8B)
        "f32.neg" -> encodeNoImmediate(rest, 0x8C)
        "f32.ceil" -> encodeNoImmediate(rest, 0x8D)
        "f32.floor" -> encodeNoImmediate(rest, 0x8E)
        "f32.trunc" -> encodeNoImmediate(rest, 0x8F)
        "f32.nearest" -> encodeNoImmediate(rest, 0x90)
        "f32.sqrt" -> encodeNoImmediate(rest, 0x91)
        "f32.add" -> encodeNoImmediate(rest, 0x92)
        "f32.sub" -> encodeNoImmediate(rest, 0x93)
        "f32.mul" -> encodeNoImmediate(rest, 0x94)
        "f32.div" -> encodeNoImmediate(rest, 0x95)
        "f32.min" -> encodeNoImmediate(rest, 0x96)
        "f32.max" -> encodeNoImmediate(rest, 0x97)
        "f32.copysign" -> encodeNoImmediate(rest, 0x98)
        "f32.eq" -> encodeNoImmediate(rest, 0x5B)
        "f32.ne" -> encodeNoImmediate(rest, 0x5C)
        "f32.lt" -> encodeNoImmediate(rest, 0x5D)
        "f32.gt" -> encodeNoImmediate(rest, 0x5E)
        "f32.le" -> encodeNoImmediate(rest, 0x5F)
        "f32.ge" -> encodeNoImmediate(rest, 0x60)
        "f32.convert_i32_s" -> encodeNoImmediate(rest, 0xB2)
        "f32.convert_i32_u" -> encodeNoImmediate(rest, 0xB3)
        "f32.convert_i64_s" -> encodeNoImmediate(rest, 0xB4)
        "f32.convert_i64_u" -> encodeNoImmediate(rest, 0xB5)
        "f32.demote_f64" -> encodeNoImmediate(rest, 0xB6)
        "f32.reinterpret_i32" -> encodeNoImmediate(rest, 0xBE)

        // Numeric: f64 arithmetic / compare / convert / reinterpret.
        "f64.abs" -> encodeNoImmediate(rest, 0x99)
        "f64.neg" -> encodeNoImmediate(rest, 0x9A)
        "f64.ceil" -> encodeNoImmediate(rest, 0x9B)
        "f64.floor" -> encodeNoImmediate(rest, 0x9C)
        "f64.trunc" -> encodeNoImmediate(rest, 0x9D)
        "f64.nearest" -> encodeNoImmediate(rest, 0x9E)
        "f64.sqrt" -> encodeNoImmediate(rest, 0x9F)
        "f64.add" -> encodeNoImmediate(rest, 0xA0)
        "f64.sub" -> encodeNoImmediate(rest, 0xA1)
        "f64.mul" -> encodeNoImmediate(rest, 0xA2)
        "f64.div" -> encodeNoImmediate(rest, 0xA3)
        "f64.min" -> encodeNoImmediate(rest, 0xA4)
        "f64.max" -> encodeNoImmediate(rest, 0xA5)
        "f64.copysign" -> encodeNoImmediate(rest, 0xA6)
        "f64.eq" -> encodeNoImmediate(rest, 0x61)
        "f64.ne" -> encodeNoImmediate(rest, 0x62)
        "f64.lt" -> encodeNoImmediate(rest, 0x63)
        "f64.gt" -> encodeNoImmediate(rest, 0x64)
        "f64.le" -> encodeNoImmediate(rest, 0x65)
        "f64.ge" -> encodeNoImmediate(rest, 0x66)
        "f64.convert_i32_s" -> encodeNoImmediate(rest, 0xB7)
        "f64.convert_i32_u" -> encodeNoImmediate(rest, 0xB8)
        "f64.convert_i64_s" -> encodeNoImmediate(rest, 0xB9)
        "f64.convert_i64_u" -> encodeNoImmediate(rest, 0xBA)
        "f64.promote_f32" -> encodeNoImmediate(rest, 0xBB)
        "f64.reinterpret_i64" -> encodeNoImmediate(rest, 0xBF)

        else -> BytesResult.Failure("unknown or unsupported mnemonic '$mnemonic'")
    }

    // -- Per-op encoders ---------------------------------------------------

    private fun encodeI32Const(rest: List<String>): BytesResult {
        val arg = rest.singleOrNull() ?: return BytesResult.Failure("i32.const expects one immediate, got ${rest.size}")
        val value = parseInt32(arg) ?: return BytesResult.Failure("invalid i32 value: '$arg'")
        return BytesResult.Success(byteArrayOf(0x41) + encodeSLEB128(value.toLong()))
    }

    private fun encodeI64Const(rest: List<String>): BytesResult {
        val arg = rest.singleOrNull() ?: return BytesResult.Failure("i64.const expects one immediate, got ${rest.size}")
        val value = parseInt64(arg) ?: return BytesResult.Failure("invalid i64 value: '$arg'")
        return BytesResult.Success(byteArrayOf(0x42) + encodeSLEB128(value))
    }

    private fun encodeF32Const(rest: List<String>): BytesResult {
        val arg = rest.singleOrNull() ?: return BytesResult.Failure("f32.const expects one immediate, got ${rest.size}")
        val value = parseFloat32(arg) ?: return BytesResult.Failure("invalid f32 value: '$arg'")
        return BytesResult.Success(byteArrayOf(0x43) + encodeF32(value))
    }

    private fun encodeF64Const(rest: List<String>): BytesResult {
        val arg = rest.singleOrNull() ?: return BytesResult.Failure("f64.const expects one immediate, got ${rest.size}")
        val value = parseFloat64(arg) ?: return BytesResult.Failure("invalid f64 value: '$arg'")
        return BytesResult.Success(byteArrayOf(0x44) + encodeF64(value))
    }

    /**
     * ULEB128-immediate ops: opcode byte + ULEB128(index). When [maxIndex]
     * is non-null, rejects values >= maxIndex with a semantic-error message
     * referencing [indexLabel] ("local index 10 out of range …").
     */
    private fun encodeULebOp(
        opcode: Int,
        rest: List<String>,
        maxIndex: Int? = null,
        indexLabel: String? = null,
    ): BytesResult {
        val arg = rest.singleOrNull() ?: return BytesResult.Failure("expected one immediate, got ${rest.size}")
        val value = parseUInt32(arg) ?: return BytesResult.Failure("invalid u32 index: '$arg'")
        if (maxIndex != null && value.toLong() >= maxIndex.toLong()) {
            val label = indexLabel ?: "index"
            return BytesResult.Failure(
                "$label index $value out of range (function has $maxIndex ${label}s + params)",
            )
        }
        return BytesResult.Success(byteArrayOf(opcode.toByte()) + encodeULEB128(value.toLong() and 0xFFFFFFFFL))
    }

    /**
     * MemArg-carrying ops: opcode + ULEB128(align-exponent) + ULEB128(offset).
     * The renderer emits `align=N` (where N is the actual byte alignment,
     * `1 shl exponent`) and `offset=M`, omitting either when it equals the
     * natural alignment / zero. We accept the same forms here.
     */
    private fun encodeMemArgOp(opcode: Int, rest: List<String>, naturalAlign: Int): BytesResult {
        var alignExp = naturalAlign
        var offset: Long = 0L
        for (token in rest) {
            val eq = token.indexOf('=')
            if (eq < 0) return BytesResult.Failure("expected `name=value`, got '$token'")
            val key = token.substring(0, eq)
            val raw = token.substring(eq + 1)
            when (key) {
                "align" -> {
                    val align = parseUInt32(raw) ?: return BytesResult.Failure("invalid align: '$raw'")
                    if (align == 0u || (align and (align - 1u)) != 0u) {
                        return BytesResult.Failure("align must be a power of two: $align")
                    }
                    alignExp = log2Exact(align.toLong())
                }
                "offset" -> {
                    val v = parseUInt32(raw) ?: return BytesResult.Failure("invalid offset: '$raw'")
                    offset = v.toLong() and 0xFFFFFFFFL
                }
                else -> return BytesResult.Failure("unknown memarg key '$key'")
            }
        }
        return BytesResult.Success(
            byteArrayOf(opcode.toByte()) + encodeULEB128(alignExp.toLong()) + encodeULEB128(offset),
        )
    }

    /**
     * No-immediate ops: just the opcode byte (and an optional sub-opcode byte
     * for 0xFC-prefixed ops like trunc_sat). Rejects any trailing tokens so
     * the user gets a clear error when they accidentally type an immediate
     * after a zero-arity mnemonic.
     */
    private fun encodeNoImmediate(rest: List<String>, opcode: Int, subOpcode: Int? = null): BytesResult {
        if (rest.isNotEmpty()) {
            return BytesResult.Failure("this opcode takes no immediates, got ${rest.size} extra token(s)")
        }
        return if (subOpcode != null) {
            BytesResult.Success(byteArrayOf(opcode.toByte(), subOpcode.toByte()))
        } else {
            BytesResult.Success(byteArrayOf(opcode.toByte()))
        }
    }

    // -- Tokenizer ---------------------------------------------------------

    private val COMMENT = Regex("""\(;[^;]*;\)""")
    private val WHITESPACE = Regex("""\s+""")

    private fun tokenize(text: String): List<String> {
        val noComments = COMMENT.replace(text, "").trim()
        if (noComments.isEmpty()) return emptyList()
        return noComments.split(WHITESPACE)
    }

    // -- Number parsing ----------------------------------------------------

    private fun parseInt32(s: String): Int? = s.toIntOrNull()

    private fun parseInt64(s: String): Long? = s.toLongOrNull()

    private fun parseUInt32(s: String): UInt? = s.toUIntOrNull()

    private fun parseFloat32(s: String): Float? = when (s) {
        "inf" -> Float.POSITIVE_INFINITY
        "-inf" -> Float.NEGATIVE_INFINITY
        "nan", "+nan", "-nan" -> Float.NaN
        else -> s.toFloatOrNull()
    }

    private fun parseFloat64(s: String): Double? = when (s) {
        "inf" -> Double.POSITIVE_INFINITY
        "-inf" -> Double.NEGATIVE_INFINITY
        "nan", "+nan", "-nan" -> Double.NaN
        else -> s.toDoubleOrNull()
    }

    /** Returns the exponent N such that `1 shl N == value`, or -1 if not a power of two. */
    private fun log2Exact(value: Long): Int {
        if (value <= 0L) return -1
        var v = value
        var n = 0
        while (v > 1L) {
            if (v and 1L == 1L) return -1
            v = v shr 1
            n++
        }
        return n
    }

    // -- LEB128 / IEEE-754 byte encoders -----------------------------------

    private fun encodeULEB128(value: Long): ByteArray {
        require(value >= 0L) { "ULEB128 value must be non-negative: $value" }
        val out = ArrayList<Byte>()
        var v = value
        do {
            var byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) byte = byte or 0x80
            out += byte.toByte()
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun encodeSLEB128(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        var more = true
        while (more) {
            val byte = (v and 0x7F).toInt()
            v = v shr 7 // arithmetic shift
            val signBitOfByte = byte and 0x40 != 0
            more = !((v == 0L && !signBitOfByte) || (v == -1L && signBitOfByte))
            out += if (more) (byte or 0x80).toByte() else byte.toByte()
        }
        return out.toByteArray()
    }

    private fun encodeF32(value: Float): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(4) { i -> ((bits ushr (i * 8)) and 0xFF).toByte() }
    }

    private fun encodeF64(value: Double): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(8) { i -> ((bits ushr (i * 8)) and 0xFF).toByte() }
    }

    private sealed interface BytesResult {
        data class Success(val bytes: ByteArray) : BytesResult
        data class Failure(val message: String) : BytesResult
    }
}

/** Outcome of [EditableRowEncoder.encode]. */
public sealed interface RowEncodeResult {

    /** Encoding succeeded; [ops] applied in order produces the edited row. */
    public data class Success(public val ops: List<JournalOp>) : RowEncodeResult

    /**
     * Encoding failed. [requiresCascade] is `true` when the failure is
     * specifically that the new encoding has a different byte count than
     * the original (a length cascade through the function-body / section
     * size ULEB128s is needed but not yet implemented). All other failures
     * (parse errors, range violations, mnemonic mismatch, unknown memarg
     * key) leave [requiresCascade] `false`.
     */
    public data class Error(
        public val message: String,
        public val requiresCascade: Boolean = false,
    ) : RowEncodeResult
}
