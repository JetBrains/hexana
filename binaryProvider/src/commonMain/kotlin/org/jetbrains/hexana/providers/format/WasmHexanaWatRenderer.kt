package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkRenderer
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.document.format.TokenStream
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.sections.WasmAbsHeapType
import org.jetbrains.hexana.wasm.sections.WasmAnyRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActive
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActiveWithMemoryIndex
import org.jetbrains.hexana.wasm.sections.WasmDataRecordPassive
import org.jetbrains.hexana.wasm.sections.WasmEqRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExnRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmFixedI32Limits
import org.jetbrains.hexana.wasm.sections.WasmFixedI64Limits
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalRecord
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmI31RefHeapType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmIndexHeapType
import org.jetbrains.hexana.wasm.sections.WasmLimits
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmMemoryRecord
import org.jetbrains.hexana.wasm.sections.WasmNoExnHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoneHeapType
import org.jetbrains.hexana.wasm.sections.WasmNonNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmFieldType
import org.jetbrains.hexana.wasm.sections.WasmPactType
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmReferenceHeapType
import org.jetbrains.hexana.wasm.sections.WasmReferenceType
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmSectionGlobal
import org.jetbrains.hexana.wasm.sections.WasmSectionMemory
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmStructRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTableEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagType
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType

/**
 * Hexana-flavored WAT renderer with three-column layout for code chunks:
 *
 * ```
 * 0xXXXXXXXX  HH HH HH HH HH HH    mnemonic [imm...]   [(; uleb128 HH HH ;)]
 * ```
 *
 * - **offset gutter**: 10-char absolute file offset
 * - **hex bytes**: padded to 18 chars (room for 6 byte pairs); overflows for
 *   long instructions
 * - **WAT**: mnemonic + immediates, with a trailing `(; uleb128 … ;)` /
 *   `(; sleb128 … ;)` annotation when an immediate's encoded form exceeds
 *   one byte and is therefore non-trivial
 *
 * Block/loop/if recursively flatten into linear lines with explicit `end`
 * markers (wasm-tools `--no-fold-control` style); 2-space indent per nesting
 * level. Function names follow [WasmSymbolResolver]'s priority chain
 * (name section → DWARF name → DWARF linkage_name → export name → fallback).
 *
 * Mnemonic coverage: ~120 most common opcodes (parametric, control, variable,
 * memory, full numeric for i32/i64/f32/f64). Long-tail opcodes (SIMD, GC,
 * Atomic, EH, table, reference) emit a `(; <ClassName> ;)` placeholder.
 */
public class WasmHexanaWatRenderer(
    private val parsed: WasmParsedBinary,
) : ChunkRenderer {

    private val provider = parsed.provider
    private val resolver = parsed.symbolResolver
    private val importFuncCount = provider.importFuncCount

    /**
     * When true, `call`/`return_call` operands render as symbolic `$name` (when a
     * name resolves) instead of the numeric index. Off by default so the single-file
     * WAT panel is unchanged; the WAT-diff path enables it so unchanged callers of a
     * renumbered function render identically. Set on a dedicated renderer instance.
     */
    public var symbolicOperands: Boolean = false

    /**
     * When true, every `call_indirect` / `return_call_indirect` row is
     * followed by an [RowKind.IndirectCandidatesExpander] row plus one
     * clickable comment row per statically known dispatch candidate
     * ([RowKind.IndirectCandidate], each carrying a [FollowTarget]). Off by
     * default: other renderRows consumers (the VS Code WAT tab, the WAT-diff
     * path, tests) have no expander handling and would render every
     * candidate row ungated. The IDE's virtualized WAT panel enables it on
     * its renderer instance.
     */
    public var inlineIndirectCandidates: Boolean = false

    private val watSymbols = WatSymbolNames { funcIdx -> resolver.resolve(SymbolKind.FUNCTION, funcIdx) }

    private val memoryRecords: List<WasmMemoryRecord> by lazy {
        provider.wasmModule?.memorySection?.let { section ->
            with(section) { provider.bytes.scan() }
        } ?: emptyList()
    }

    private val tableRecords: List<WasmTableEntry> by lazy {
        provider.wasmModule?.tableSection?.let { section ->
            with(section) { provider.bytes.scanTable() }
        } ?: emptyList()
    }

    private val globalRecords: List<WasmGlobalRecord> by lazy {
        provider.wasmModule?.globalSection?.let { section ->
            with(section) { provider.bytes.scan() }
        } ?: emptyList()
    }

    // Flattened to one element per type index (rec-group entries expanded),
    // matching the per-type chunking in WasmSectionTypes.getRecordRanges so
    // `renderTypeElement` can index this list by `chunk.indexInSection`. Each
    // element is a leaf type (struct / array / func), never a rec wrapper —
    // so a chunk renders a single `(type $N …)` row, not the whole rec group.
    private val typeRecords: List<WasmTypeRecord> by lazy {
        provider.wasmModule?.typesSection?.let { section ->
            with(section) { provider.bytes.parseTypes() }
                .flatMap { if (it is WasmRecursiveTypeRecord) it.entries else listOf(it) }
        } ?: emptyList()
    }

    override fun render(snapshot: BinaryDocumentSnapshot, chunk: BinaryChunk): TokenStream {
        val tokens = mutableListOf<RenderToken>()
        when (chunk) {
            is BinaryChunk.SectionHeader -> renderSectionHeader(chunk, tokens)
            is BinaryChunk.Element -> renderElement(chunk, snapshot.byteSource(), tokens)
        }
        return TokenStream(tokens)
    }

    /**
     * Same content as [render] but pre-grouped into one [RenderedRow] per
     * on-screen line. Code-chunk rows whose opcode is in
     * [EditableRows.isEditable] additionally carry an [EditableRowInfo]
     * payload so the panel can wire that row to a `BasicTextField` and
     * route Enter / focus-loss commits through [EditableRowEncoder].
     *
     * For every other chunk kind the rows are returned with `editable = null`.
     */
    public fun renderRows(
        snapshot: BinaryDocumentSnapshot,
        chunk: BinaryChunk,
        dataViewMode: DataViewMode = DataViewMode.U8_HEX,
    ): List<RenderedRow> {
        val rawRows = splitOnLineBreaks(render(snapshot, chunk).tokens)
        if (chunk !is BinaryChunk.Element) {
            return rawRows.map { RenderedRow(tokens = it) }
        }
        // Data-section rows: header (always shown) + expander + body rows
        // in the requested view mode (one per 16 bytes for hex modes, one
        // per 64 bytes for ASCII / UTF-8). Body rendering is capped at
        // DATA_BODY_ROW_CAP to keep the LazyColumn item composition
        // bounded; per-row virtualisation is the Phase-3 follow-up.
        if (chunk.kind == WasmSectionKinds.DATA) {
            return buildDataRows(
                snapshot = snapshot,
                chunk = chunk,
                headerTokens = rawRows.firstOrNull() ?: emptyList(),
                viewMode = dataViewMode,
            )
        }
        // Custom-section body: header (offset + hex + name) + expander + raw
        // payload rows in the requested view mode — same foldable, multi-
        // encoding (u8/u16/u32/u64/ascii/utf8) representation as Data entries.
        if (chunk.kind == WasmSectionKinds.CUSTOM) {
            return buildCustomRows(
                snapshot = snapshot,
                chunk = chunk,
                headerTokens = rawRows.firstOrNull() ?: emptyList(),
                viewMode = dataViewMode,
            )
        }
        // Import-section rows: header (always shown — the single-line
        // `(import "mod" "name" (func (type N)))` form) + expander + 3
        // descriptor rows (module slice, symbol slice, type slice).
        if (chunk.kind == WasmSectionKinds.IMPORT) {
            return buildImportRows(
                snapshot = snapshot,
                chunk = chunk,
                headerTokens = rawRows.firstOrNull() ?: emptyList(),
            )
        }
        // Export-section rows: header + expander + 2 descriptor rows
        // (name slice + kind/index slice). The descriptor's first row
        // carries a FollowTarget for function exports.
        if (chunk.kind == WasmSectionKinds.EXPORT) {
            return buildExportRows(
                snapshot = snapshot,
                chunk = chunk,
                headerTokens = rawRows.firstOrNull() ?: emptyList(),
            )
        }
        // Function-section rows: one chunk per entry, single row, follow to
        // the Code body for that funcidx.
        if (chunk.kind == WasmSectionKinds.FUNCTION) {
            val funcIdx = provider.importFuncCount + chunk.indexInSection
            val target = FollowTarget(funcIdx = funcIdx, section = WasmSectionKinds.CODE)
            return rawRows.map { RenderedRow(tokens = it, followTarget = target) }
        }
        if (chunk.kind != WasmSectionKinds.CODE) {
            return rawRows.map { RenderedRow(tokens = it) }
        }
        val opMap = buildOpMapForCode(chunk, snapshot.byteSource())
        val localCount = computeLocalCount(chunk)
        val cascadeContext = computeCascadeContext(chunk, snapshot.byteSource())
        val localsRowMeta = computeLocalsRowMeta(chunk, snapshot.byteSource())
        // Phase F: detect redirect-stub bodies produced by the append+redirect
        // path. If matched, prepend a `(; redirected to func N ;)` comment row
        // so the user can tell at a glance that this isn't real code anymore.
        val redirectTarget: Int? = detectRedirectStubTarget(chunk)
        val redirectAnnotation: List<RenderedRow> = if (redirectTarget != null) {
            listOf(RenderedRow(tokens = redirectAnnotationRow(redirectTarget)))
        } else {
            emptyList()
        }
        val bodyRows = rawRows.flatMap { rowTokens ->
            val rowOpStart = rowTokens
                .firstOrNull { it.kind == RenderTokenKind.OFFSET_ANNOTATION }
                ?.sourceOffset
            val opInfo = rowOpStart?.let { opMap[it] }
            val editable = when {
                opInfo != null && EditableRows.isEditable(opInfo.op) -> EditableRowInfo(
                    op = opInfo.op,
                    opStart = opInfo.opStart,
                    opEncodedLength = opInfo.length,
                    watText = extractWatText(rowTokens),
                    kind = EditableRowInfo.Kind.OPCODE,
                    // Always attach for code rows — the encoder permits mnemonic
                    // swaps within the editable subset, so the user can turn any
                    // row into `local.{get,set,tee} N` and that index must be
                    // bounds-checked against the function's params + locals.
                    localCount = localCount,
                    cascade = cascadeContext,
                )
                localsRowMeta != null && isLocalsDeclRow(rowTokens) -> EditableRowInfo(
                    op = null,
                    opStart = localsRowMeta.byteStart,
                    opEncodedLength = localsRowMeta.byteLength,
                    watText = extractWatText(rowTokens),
                    kind = EditableRowInfo.Kind.LOCALS,
                    cascade = cascadeContext,
                )
                else -> null
            }
            // For `call N` rows: the row's follow target is the Function
            // section entry for N — Cmd+B chains call → declaration → body.
            val followTarget = (opInfo?.op as? OpCodes.Flow.Call)?.let { callOp ->
                val tgt = callOp.value.toLong()
                if (tgt in 0..Int.MAX_VALUE.toLong()) {
                    FollowTarget(funcIdx = tgt.toInt(), section = WasmSectionKinds.FUNCTION)
                } else {
                    null
                }
            }
            // call_indirect rows are read-only (no `editable`), so the type
            // immediate rides on the row for the candidates popup.
            val indirectCallTypeIdx = when (val op = opInfo?.op) {
                is OpCodes.Flow.CallIndirect -> op.value0
                is OpCodes.Flow.ReturnCallIndirect -> op.value0.toInt()
                else -> null
            }
            // `throw $tag` rows are read-only too; the tag index rides on the
            // row for the exception-flow (handler resolution) popup.
            val throwTagIdx = (opInfo?.op as? OpCodes.Flow.Trow)?.value?.toInt()
            val siteRow = RenderedRow(
                tokens = rowTokens,
                editable = editable,
                followTarget = followTarget,
                indirectCallTypeIdx = indirectCallTypeIdx,
                throwTagIdx = throwTagIdx,
            )
            if (inlineIndirectCandidates && indirectCallTypeIdx != null && rowOpStart != null) {
                listOf(siteRow) + indirectCandidateRows(indirectCallTypeIdx, siteKey = rowOpStart)
            } else {
                listOf(siteRow)
            }
        }
        return redirectAnnotation + bodyRows
    }

    /**
     * Returns the funcidx that this code chunk's body redirects to, or
     * `null` if the body doesn't match the redirect-stub shape produced
     * by `EditableRowEncoder.encodeAppendFunctionEdit`.
     *
     * Stub shape (after locals decl, in opcode order):
     *
     * ```
     * local.get 0       \
     * local.get 1        |  exactly N consecutive sequential local.gets
     * …                  |  where N = paramCount (≥ 0)
     * local.get N-1     /
     * call M             ← exactly one Call; M is the redirect target
     * nop                \
     * nop                 |  zero or more nops (padding to fit oldBodyLen)
     * …                  /
     * end
     * ```
     *
     * Anything else (a real handwritten function that happens to have a
     * `call` is rejected by the leading-local.gets-must-be-sequential
     * check or by any non-`Nop` opcode after the call). Heuristic — false
     * positives are possible for hand-crafted stubs, but the shape is
     * specific enough that real toolchain output never matches.
     */
    private fun detectRedirectStubTarget(chunk: BinaryChunk.Element): Int? {
        val codeRecord = runCatching { provider.parseCodeRecord(chunk.indexInSection) }.getOrNull()
            ?: return null
        val ops = codeRecord.opCodes
        if (ops.isEmpty()) return null

        // `parseOpCodes` consumes the trailing 0x0B (Flow.Terminal) without
        // appending it to the list — every well-formed function body ends
        // there implicitly — so the expected shape we match against is just
        //   (local.get N)* call M nop*
        // No explicit Terminal entry to look for at the end.

        // 1. Skip leading local.get N for N = 0, 1, 2, …
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op !is OpCodes.Local.Get) break
            if (op.value.toInt() != i) return null  // sequence broken → not a stub
            i++
        }

        // 2. Exactly one Call.
        val callOp = ops.getOrNull(i) as? OpCodes.Flow.Call ?: return null
        i++

        // 3. Everything from here to the end of `ops` must be Nop. (No explicit
        //    Terminal — see comment above.)
        while (i < ops.size) {
            if (ops[i] !is OpCodes.ParametricInstructions.Nop) return null
            i++
        }

        return callOp.value.toInt()
    }

    /**
     * Builds the synthetic comment row prepended above a redirect-stub
     * function body. The token kinds match what the panel's
     * `RenderedRowView` treats as a non-editable row (no editable info
     * payload is attached, so the comment is rendered as plain text).
     */
    private fun redirectAnnotationRow(targetFuncIdx: Int): List<RenderToken> = listOf(
        RenderToken("    ", RenderTokenKind.WHITESPACE),
        RenderToken("(; redirected to func $targetFuncIdx ;)", RenderTokenKind.COMMENT),
    )

    /**
     * Expander row + one clickable comment row per statically known dispatch
     * candidate for a `call_indirect` site (see [inlineIndirectCandidates]).
     * Candidate rows carry a [FollowTarget], so the panel's existing
     * follow-row rendering makes each one click-navigable. Static
     * under-approximation: runtime `table.set` writes are invisible.
     */
    private fun indirectCandidateRows(typeIdx: Int, siteKey: Long): List<RenderedRow> {
        val candidates = provider.dispatchCandidates?.candidatesForType(typeIdx) ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()
        val rows = ArrayList<RenderedRow>(candidates.size + 1)
        rows += RenderedRow(
            tokens = emptyList(),
            kind = RowKind.IndirectCandidatesExpander(siteKey = siteKey, count = candidates.size),
        )
        for (funcIdx in candidates) {
            val name = watSymbols.function(funcIdx)
            val label = if (name != null) "(; -> $funcIdx: $name ;)" else "(; -> func $funcIdx ;)"
            rows += RenderedRow(
                tokens = listOf(
                    RenderToken("        ", RenderTokenKind.WHITESPACE),
                    RenderToken(label, RenderTokenKind.COMMENT),
                ),
                followTarget = FollowTarget(funcIdx = funcIdx, section = WasmSectionKinds.FUNCTION),
                kind = RowKind.IndirectCandidate(siteKey = siteKey),
            )
        }
        return rows
    }

    /**
     * Locates the `(local …)` declaration row in a code chunk and returns
     * its byte range. Returns `null` when the function has no locals (the
     * renderer skips the row in that case) or when the parsed code record
     * is unavailable.
     */
    private fun computeLocalsRowMeta(chunk: BinaryChunk.Element, source: ByteSource): LocalsRowMeta? {
        if (chunk.kind != WasmSectionKinds.CODE) return null
        val codeRecord = runCatching { provider.parseCodeRecord(chunk.indexInSection) }.getOrNull()
            ?: return null
        if (codeRecord.locals.isEmpty()) return null
        val entryStart = chunk.byteRange.first
        val funcSizeULEBLen = decodeULEB128(source, entryStart).byteCount
        val localsByteStart = entryStart + funcSizeULEBLen.toLong()
        val initialCursor = (codeRecord.opCodes.firstOrNull() as? OpCodeWithOffset)
            ?.offset?.toLong()?.takeIf { it > 0L }
            ?: estimateBodyStart(codeRecord)
        return LocalsRowMeta(
            byteStart = localsByteStart,
            byteLength = (initialCursor - localsByteStart).toInt(),
        )
    }

    /**
     * The function header and locals rows both display offset = entryStart,
     * so we disambiguate by token content. The locals row contains a
     * KEYWORD token with text "local"; the function header has "func".
     */
    private fun isLocalsDeclRow(rowTokens: List<RenderToken>): Boolean =
        rowTokens.any { it.kind == RenderTokenKind.KEYWORD && it.text == "local" }

    private data class LocalsRowMeta(val byteStart: Long, val byteLength: Int)

    /**
     * Reads the surrounding-structure size prefixes for a code-chunk row so
     * the encoder can emit cascade ops on length-changing edits. Returns
     * `null` if the parsed code section isn't available.
     */
    private fun computeCascadeContext(
        chunk: BinaryChunk.Element,
        source: ByteSource,
    ): CascadeContext? {
        val codeSection = provider.wasmModule?.codeSection ?: return null
        val funcEntryStart = chunk.byteRange.first
        val funcSizeDecoded = decodeULEB128(source, funcEntryStart)
        val sectionSizePrefixStart = (codeSection.sectionOffset + 1).toLong()
        val sectionSizePrefixLength = codeSection.contentOffset - codeSection.sectionOffset - 1
        return CascadeContext(
            funcSizePrefixStart = funcEntryStart,
            funcSizePrefixLength = funcSizeDecoded.byteCount,
            currentBodyLength = funcSizeDecoded.value,
            sectionSizePrefixStart = sectionSizePrefixStart,
            sectionSizePrefixLength = sectionSizePrefixLength,
            currentSectionLength = codeSection.contentSize.toLong(),
        )
    }

    /**
     * Total params + locals count for the function whose body lives in
     * [chunk]. Returns `null` if the parsed structure is unavailable; the
     * caller treats null as "no semantic validation possible" (the encoder
     * accepts any u32 index in that case).
     */
    private fun computeLocalCount(chunk: BinaryChunk.Element): Int? {
        val codeIdx = chunk.indexInSection
        val record = runCatching { provider.parseCodeRecord(codeIdx) }.getOrNull() ?: return null
        val locals = record.locals.sumOf { it.local }
        val params = (provider.functions.getOrNull(codeIdx) as? WasmSectionFunctionTypeRecord)
            ?.params?.size ?: 0
        return params + locals
    }

    /**
     * Walks the function body's parsed opcodes and computes a
     * `(opStart → op + encoded length)` map. Mirrors [emitOps]'s
     * byte-offset bookkeeping; kept separate so [renderRows] can be added
     * without rewriting [renderCodeElement].
     */
    private fun buildOpMapForCode(
        chunk: BinaryChunk.Element,
        source: ByteSource,
    ): Map<Long, OpAtOffset> {
        val codeRecord = runCatching { provider.parseCodeRecord(chunk.indexInSection) }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<Long, OpAtOffset>()
        val initialCursor = (codeRecord.opCodes.firstOrNull() as? OpCodeWithOffset)
            ?.offset?.toLong()?.takeIf { it > 0L }
            ?: estimateBodyStart(codeRecord)
        walkOpsForOffsets(codeRecord.opCodes, initialCursor, source, out)
        return out
    }

    private fun walkOpsForOffsets(
        ops: List<OpCode>,
        startCursor: Long,
        source: ByteSource,
        out: MutableMap<Long, OpAtOffset>,
    ): Long {
        var cursor = startCursor
        for (op in ops) {
            val anchor = (op as? OpCodeWithOffset)?.offset?.toLong()?.takeIf { it > 0L }
            if (anchor != null && anchor >= cursor) cursor = anchor

            when (op) {
                is OpCodes.Flow.Block, is OpCodes.Flow.Loop, is OpCodes.Flow.If, is OpCodes.Flow.Else -> {
                    val children = @Suppress("UNCHECKED_CAST") (
                        ((op as OpCodeWithValue<*>).value as? List<OpCode>) ?: emptyList()
                    )
                    val headerLen = blockHeaderLength(source, cursor)
                    out[cursor] = OpAtOffset(op, cursor, headerLen)
                    cursor += headerLen
                    cursor = walkOpsForOffsets(children, cursor, source, out)
                    if (cursor < source.length) cursor += 1L // closing 0x0B end byte
                }
                else -> {
                    val length = opcodeLength(op, cursor, source).coerceAtLeast(1)
                    out[cursor] = OpAtOffset(op, cursor, length)
                    cursor += length
                }
            }
        }
        return cursor
    }

    /**
     * Splits a flat token stream into rows. Each `LINE_BREAK` ends a row
     * (the break itself is not included). A trailing partial row is
     * emitted when the stream doesn't end with a break.
     */
    private fun splitOnLineBreaks(tokens: List<RenderToken>): List<List<RenderToken>> {
        val rows = ArrayList<List<RenderToken>>()
        var current = ArrayList<RenderToken>()
        for (t in tokens) {
            if (t.kind == RenderTokenKind.LINE_BREAK) {
                rows += current
                current = ArrayList()
            } else {
                current += t
            }
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }

    /**
     * Strips the structural decoration (offset gutter, hex bytes column,
     * surrounding whitespace, leb-annotation comment) from a row's tokens
     * and returns just the editable mnemonic + immediates as a plain
     * string. Trims surrounding whitespace so a `BasicTextField` shows
     * `"i32.const 5"` rather than `"  i32.const 5  "`.
     */
    /**
     * Clean WAT content lines for a chunk — mnemonic + operands only, with the
     * offset gutter, hex column, and comments (incl. the `(; funcidx N ;)` header
     * comment) stripped. Blank lines are dropped. With [symbolicOperands] enabled
     * these are renumber-stable, which is what the WAT diff aligns and line-diffs.
     */
    public fun watContentLines(snapshot: BinaryDocumentSnapshot, chunk: BinaryChunk): List<String> =
        renderRows(snapshot, chunk).map { extractWatText(it.tokens) }.filter { it.isNotBlank() }

    private fun extractWatText(rowTokens: List<RenderToken>): String {
        val sb = StringBuilder()
        for (t in rowTokens) {
            when (t.kind) {
                RenderTokenKind.OFFSET_ANNOTATION,
                RenderTokenKind.RAW_BYTES,
                RenderTokenKind.COMMENT,
                RenderTokenKind.LINE_BREAK -> Unit
                else -> sb.append(t.text)
            }
        }
        return sb.toString().trim()
    }

    private data class OpAtOffset(val op: OpCode, val opStart: Long, val length: Int)

    private fun renderElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        when (chunk.kind) {
            WasmSectionKinds.CODE -> renderCodeElement(chunk, source, tokens)
            WasmSectionKinds.MEMORY -> renderMemoryElement(chunk, source, tokens)
            WasmSectionKinds.TABLE -> renderTableElement(chunk, source, tokens)
            WasmSectionKinds.GLOBAL -> renderGlobalElement(chunk, source, tokens)
            WasmSectionKinds.IMPORT -> renderImportElement(chunk, source, tokens)
            WasmSectionKinds.EXPORT -> renderExportElement(chunk, source, tokens)
            WasmSectionKinds.TYPE -> renderTypeElement(chunk, source, tokens)
            WasmSectionKinds.FUNCTION -> renderFunctionElement(chunk, source, tokens)
            WasmSectionKinds.DATA -> renderDataElement(chunk, source, tokens)
            WasmSectionKinds.CUSTOM -> renderCustomElement(chunk, source, tokens)
            else -> renderInlineHexElement(chunk, source, tokens)
        }
    }

    /**
     * Single-line header for a Data-section entry chunk. Body bytes are
     * rendered as separate rows by [renderRows]; this method only emits the
     * header you see when the chunk is collapsed:
     *
     * ```
     * 0x000020  0B 07 01 …  (; data[0] active mem 0 @ i32.const 0  →  1 byte ;)
     * ```
     *
     * For passive entries the mode + payload size is shown without an init
     * expression: `(; data[0] passive  →  N bytes ;)`.
     */
    private fun renderDataElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitOffsetGutter(chunk.byteRange.first, tokens)
        // Trim the hex preview to the descriptor portion only — the payload
        // bytes get their own dedicated body rows; mixing them into the
        // header obscures where the descriptor ends.
        val record = dataRecords.getOrNull(chunk.indexInSection)
        val descriptorEnd = if (record != null) dataPayloadStart(chunk, record) else chunk.byteRange.last + 1L
        emitHexBytes(chunk.byteRange.first, descriptorEnd, source, tokens)
        tokens += comment(dataHeaderComment(chunk))
    }

    /** Build the `(; data[i] … → N bytes ;)` summary used by both the chunk renderer and the header row. */
    private fun dataHeaderComment(chunk: BinaryChunk.Element): String {
        val record = dataRecords.getOrNull(chunk.indexInSection)
        val idx = chunk.indexInSection
        return when (record) {
            is WasmDataRecordActive ->
                "(; data[$idx] active mem 0 @ ${initExprWat(record.expression)}" +
                    "  →  ${formatByteCount(record.data.size)} ;)"
            is WasmDataRecordActiveWithMemoryIndex ->
                "(; data[$idx] active mem ${record.memoryIndex} @ ${initExprWat(record.expression)}" +
                    "  →  ${formatByteCount(record.data.size)} ;)"
            is WasmDataRecordPassive ->
                "(; data[$idx] passive  →  ${formatByteCount(record.data.size)} ;)"
            else -> "(; data[$idx] (unparsed) ;)"
        }
    }

    private fun formatByteCount(n: Int): String = if (n == 1) "1 byte" else "$n bytes"

    /**
     * Compact WAT rendering of a Data init expression (typically
     * `i32.const N; end`). Drops the trailing `end` and emits only the
     * expression's leading instruction for readability — every init expr
     * in real binaries is a single constant push.
     */
    private fun initExprWat(ops: List<OpCode>): String {
        // The parser strips the trailing `end` byte (it's an expression
        // terminator, not an instruction), so the first remaining opcode
        // is the actual init expression value.
        val first = ops.firstOrNull() ?: return "<empty>"
        return when (first) {
            is I32.Const -> "i32.const ${first.value}"
            is I64.Const -> "i64.const ${first.value}"
            is OpCodes.Global.Get -> "global.get ${(first as OpCodeWithValue<*>).value}"
            else -> first::class.simpleName ?: "?"
        }
    }

    /** Lazy access to the parsed Data records. Empty when the module has no data section. */
    private val dataRecords: List<WasmDataRecord> by lazy { provider.data }

    /**
     * Break the descriptor (everything before the payload bytes) into one
     * row per element:
     *   - mode byte
     *   - `memidx` ULEB (only mode 2)
     *   - each init-expression opcode (offset + hex + WAT mnemonic — same
     *     layout as code-section rows so users get a consistent reading
     *     experience)
     *   - synthetic `end` row for the init-expression terminator (active
     *     modes only)
     *   - payload-length ULEB
     *
     * Each row carries [RowKind.DataDescriptor] so the panel can hide
     * them when the chunk is collapsed.
     */
    private fun buildDescriptorRows(
        chunk: BinaryChunk.Element,
        record: WasmDataRecord,
        source: ByteSource,
    ): List<RenderedRow> {
        val out = ArrayList<RenderedRow>()
        var cursor = chunk.byteRange.first

        // 1. Mode byte.
        out += descRowWithComment(
            offset = cursor,
            length = 1,
            source = source,
            commentText = "(; ${modeCommentText(record)} ;)",
        )
        cursor += 1L

        // 2. Memidx ULEB (mode 2 only).
        if (record is WasmDataRecordActiveWithMemoryIndex) {
            val memidxLen = ulebByteLength(source, cursor)
            out += descRowWithComment(
                offset = cursor,
                length = memidxLen,
                source = source,
                commentText = "(; mem ${record.memoryIndex} ;)",
            )
            cursor += memidxLen.toLong()
        }

        // 3. Init expression opcodes + trailing `end`.
        val initExpr = when (record) {
            is WasmDataRecordActive -> record.expression
            is WasmDataRecordActiveWithMemoryIndex -> record.expression
            else -> emptyList()
        }
        for (op in initExpr) {
            val opLen = opcodeLength(op, cursor, source)
            out += initExprOpRow(cursor, opLen, source, op)
            cursor += opLen.toLong()
        }
        // The parser strips the trailing 0x0B `end` byte from
        // `record.expression`; render it as its own row for active modes
        // so the offsets stay continuous.
        val hasInitExpr = record is WasmDataRecordActive || record is WasmDataRecordActiveWithMemoryIndex
        if (hasInitExpr) {
            out += descRowWithMnemonic(
                offset = cursor,
                length = 1,
                source = source,
                mnemonic = "end",
            )
            cursor += 1L
        }

        // 4. Payload-length ULEB — distance from `cursor` to the first
        // payload byte. Always positive: byte count is record-local and
        // the chunk's byteRange covers the whole record.
        val payloadStart = dataPayloadStart(chunk, record)
        val lengthUlebLen = (payloadStart - cursor).toInt()
        if (lengthUlebLen > 0) {
            out += descRowWithComment(
                offset = cursor,
                length = lengthUlebLen,
                source = source,
                commentText = "(; payload: ${formatByteCount(record.data.size)} ;)",
            )
        }
        return out
    }

    private fun modeCommentText(record: WasmDataRecord): String = when (record) {
        is WasmDataRecordActive -> "active, mem 0"
        is WasmDataRecordActiveWithMemoryIndex -> "active, explicit memidx"
        is WasmDataRecordPassive -> "passive"
        else -> "mode unknown"
    }

    /** Decoded ULEB128 byte length at [offset]. */
    private fun ulebByteLength(source: ByteSource, offset: Long): Int {
        var n = 0
        while (true) {
            val b = source.get(offset + n).toInt() and 0xFF
            n++
            // Defensive: ULEB128 for a 32-bit value is at most 5 bytes.
            if (b and 0x80 == 0 || n >= 10) break
        }
        return n
    }

    /** Descriptor row with offset + hex + trailing `(; comment ;)`. */
    private fun descRowWithComment(
        offset: Long,
        length: Int,
        source: ByteSource,
        commentText: String,
    ): RenderedRow {
        val tokens = ArrayList<RenderToken>(4)
        emitOffsetGutter(offset, tokens)
        emitHexBytes(offset, offset + length.toLong(), source, tokens)
        tokens += comment(commentText)
        return RenderedRow(tokens = tokens, kind = RowKind.DataDescriptor)
    }

    /** Descriptor row with offset + hex + bare keyword (e.g. `end`). */
    private fun descRowWithMnemonic(
        offset: Long,
        length: Int,
        source: ByteSource,
        mnemonic: String,
    ): RenderedRow {
        val tokens = ArrayList<RenderToken>(4)
        emitOffsetGutter(offset, tokens)
        emitHexBytes(offset, offset + length.toLong(), source, tokens)
        tokens += keyword(mnemonic)
        return RenderedRow(tokens = tokens, kind = RowKind.DataDescriptor)
    }

    /**
     * Init-expression opcode row: same shape as a code-section opcode row
     * (offset + hex + mnemonic + immediate). Init expressions are
     * restricted by the spec to a small set of constant ops — we handle
     * the common cases (numeric consts, `global.get`, `ref.func`,
     * `ref.null`) explicitly and fall back to the op's simple class name
     * for anything more exotic.
     */
    private fun initExprOpRow(
        offset: Long,
        length: Int,
        source: ByteSource,
        op: OpCode,
    ): RenderedRow {
        val tokens = ArrayList<RenderToken>(6)
        emitOffsetGutter(offset, tokens)
        emitHexBytes(offset, offset + length.toLong(), source, tokens)
        val (mnemonic, immediate) = initExprWatPair(op)
        tokens += keyword(mnemonic)
        if (immediate != null) {
            tokens += space()
            tokens += RenderToken(immediate, RenderTokenKind.NUMBER)
        }
        // Append the raw-LEB annotation the code rows use — `(; uleb128 …
        // ;)` / `(; sleb128 … ;)` — when the immediate's encoded form is
        // wider than one byte. Single-byte encodings stay silent to avoid
        // noise on the common case (`i32.const 0` etc.).
        emitLebAnnotation(op, offset, offset + length.toLong(), source, tokens)
        return RenderedRow(tokens = tokens, kind = RowKind.DataDescriptor)
    }

    /**
     * Import-section per-entry row list: header + expander + module /
     * symbol / type descriptor rows. The breakout uses byte offsets
     * walked from `chunk.byteRange.first` (the start of the import
     * record); each descriptor row's offset + hex preview anchors back
     * to those bytes so the user can correlate the WAT shape with the
     * raw binary.
     */
    private fun buildImportRows(
        snapshot: BinaryDocumentSnapshot,
        chunk: BinaryChunk.Element,
        headerTokens: List<RenderToken>,
    ): List<RenderedRow> {
        val record = provider.imports.getOrNull(chunk.indexInSection)
            ?: return listOf(RenderedRow(tokens = headerTokens))
        val source = snapshot.byteSource()
        val recordEnd = chunk.byteRange.last + 1L
        val out = ArrayList<RenderedRow>(5)
        out += RenderedRow(tokens = headerTokens)
        out += RenderedRow(
            tokens = listOf(RenderToken("", RenderTokenKind.COMMENT)),
            kind = RowKind.ImportExpander,
        )
        var cursor = chunk.byteRange.first

        // 1. Module name slice: [length ULEB][bytes...]
        val moduleSliceLen = stringSliceLength(source, cursor)
        out += importDescriptorRows(
            offset = cursor,
            length = moduleSliceLen,
            source = source,
            label = "(; module \"${record.prefix}\" ;)",
        )
        cursor += moduleSliceLen.toLong()

        // 2. Symbol name slice: [length ULEB][bytes...]
        val symbolSliceLen = stringSliceLength(source, cursor)
        out += importDescriptorRows(
            offset = cursor,
            length = symbolSliceLen,
            source = source,
            label = "(; symbol \"${record.name}\" ;)",
        )
        cursor += symbolSliceLen.toLong()

        // 3. Descriptor slice: kind byte + type-specific body. Spans
        // whatever remains of the record.
        val typeLen = (recordEnd - cursor).toInt().coerceAtLeast(0)
        if (typeLen > 0) {
            out += importDescriptorRows(
                offset = cursor,
                length = typeLen,
                source = source,
                label = "(; ${importTypeText(record)} ;)",
            )
        }
        return out
    }

    /**
     * Total byte length of a `vec(byte)` string at [offset] — the ULEB128
     * length prefix plus the declared byte count. Used to advance the
     * cursor past WASM-encoded module / symbol names.
     */
    private fun stringSliceLength(source: ByteSource, offset: Long): Int {
        val ulebLen = ulebByteLength(source, offset)
        val maxBytes = ulebLen.coerceAtMost(10)
        val decoded = decodeULEB128(source, offset, maxBytes)
        return ulebLen + decoded.value.toInt()
    }

    /** Render the import's `type` field as a compact WAT-style snippet. */
    private fun importTypeText(record: WasmImportRecord): String = when (val t = record.type) {
        is WasmTypeIndexExternalType -> "func (type ${t.index})"
        is WasmTableExternalType -> "table ${limitsText(t.limits)} ${referenceTypeName(t.referenceType)}"
        is WasmMemType -> "memory ${limitsText(t.limits)}"
        is WasmGlobalType -> "global ${if (t.mutable) "(mut ${wasmValueTypeName(t.valueType)})" else wasmValueTypeName(t.valueType)}"
        else -> "tag / unknown"
    }

    private fun limitsText(limits: WasmLimits<*>): String {
        val min = limits.min
        val max = limits.max
        return if (max == null) "$min" else "$min $max"
    }

    /**
     * Emit a descriptor slice as one or more `xxd`-style rows — same
     * layout as the Data body's [DataViewMode.U8_HEX] rows so byte-level
     * editing can hook in here uniformly across sections. The slice's
     * [label] (`(; module "env" ;)` / `(; func 5 → $name ;)`) trails the
     * ASCII sidecar on the FIRST row only; continuation rows stay bare
     * so multi-line slices remain readable.
     *
     * The optional [followTarget] is attached to the **first** row only
     * so clicking / Cmd+B-ing the descriptor navigates to the linked
     * declaration (used for Export func descriptors → Function-section
     * row).
     */
    private fun xxdLabeledRows(
        offset: Long,
        length: Int,
        source: ByteSource,
        label: String,
        kind: RowKind,
        followTarget: FollowTarget? = null,
    ): List<RenderedRow> {
        if (length <= 0) return emptyList()
        val out = ArrayList<RenderedRow>()
        val rowCount = (length + DATA_BYTES_PER_ROW - 1) / DATA_BYTES_PER_ROW
        for (r in 0 until rowCount) {
            val rowStart = offset + (r.toLong() * DATA_BYTES_PER_ROW.toLong())
            val rowLen = minOf(DATA_BYTES_PER_ROW, length - r * DATA_BYTES_PER_ROW)
            val tokens = ArrayList<RenderToken>(uIntHexRow(rowStart, source, rowLen, bytesPerValue = 1))
            if (r == 0) {
                tokens += space()
                tokens += comment(label)
            }
            out += RenderedRow(
                tokens = tokens,
                kind = kind,
                followTarget = if (r == 0) followTarget else null,
            )
        }
        return out
    }

    private fun importDescriptorRows(
        offset: Long,
        length: Int,
        source: ByteSource,
        label: String,
    ): List<RenderedRow> = xxdLabeledRows(offset, length, source, label, RowKind.ImportDescriptor)

    /**
     * Export-section per-entry row list: header (the structured single
     * line) + expander + 2 descriptor rows (exported-name slice +
     * descriptor slice). The descriptor slice carries a [FollowTarget]
     * for function exports so Cmd+B on it jumps to the function's
     * declaration row (Function section, redirected to Import if the
     * func is imported).
     *
     * For non-function exports (table / memory / global / tag) the
     * descriptor row labels the kind + index but doesn't attach a follow
     * target — those sections don't yet have an import-count aware
     * navigator path.
     */
    private fun buildExportRows(
        snapshot: BinaryDocumentSnapshot,
        chunk: BinaryChunk.Element,
        headerTokens: List<RenderToken>,
    ): List<RenderedRow> {
        val record = provider.exports.getOrNull(chunk.indexInSection)
            ?: return listOf(RenderedRow(tokens = headerTokens))
        val source = snapshot.byteSource()
        val recordEnd = chunk.byteRange.last + 1L
        val out = ArrayList<RenderedRow>(4)
        // The header is the always-visible preview line. For function
        // exports we attach the follow target here, not on the descriptor
        // — clicking the preview is the primary way to navigate, and it
        // works the same whether the chunk is collapsed or expanded.
        out += RenderedRow(
            tokens = headerTokens,
            followTarget = exportFollowTarget(record),
        )
        out += RenderedRow(
            tokens = listOf(RenderToken("", RenderTokenKind.COMMENT)),
            kind = RowKind.ExportExpander,
        )
        var cursor = chunk.byteRange.first

        // 1. Exported-name slice: [length ULEB][bytes...]
        val nameSliceLen = stringSliceLength(source, cursor)
        out += xxdLabeledRows(
            offset = cursor,
            length = nameSliceLen,
            source = source,
            label = "(; symbol \"${record.name}\" ;)",
            kind = RowKind.ExportDescriptor,
        )
        cursor += nameSliceLen.toLong()

        // 2. Descriptor slice: kind byte + index ULEB. Pure byte preview
        // — no follow target; the header above is the navigation surface.
        val descLen = (recordEnd - cursor).toInt().coerceAtLeast(0)
        if (descLen > 0) {
            out += xxdLabeledRows(
                offset = cursor,
                length = descLen,
                source = source,
                label = exportDescriptorLabel(record),
                kind = RowKind.ExportDescriptor,
            )
        }
        return out
    }

    private fun exportDescriptorLabel(record: WasmExportRecordEntry): String {
        val idx = exportIndexOf(record)
        return when (record.externType.toInt()) {
            0 -> "(; func $idx ;)"
            1 -> "(; table $idx ;)"
            2 -> "(; memory $idx ;)"
            3 -> "(; global $idx ;)"
            4 -> "(; tag $idx ;)"
            else -> "(; extern ${record.externType} $idx ;)"
        }
    }

    private fun exportFollowTarget(record: WasmExportRecordEntry): FollowTarget? {
        // Phase 1: only function exports navigate. Other kinds get a label
        // but no click target until the navigator grows per-kind import
        // counts (table / memory / global imports also share index space).
        if (record.externType.toInt() != 0) return null
        val idx = exportIndexOf(record).toLong()
        if (idx !in 0L..Int.MAX_VALUE.toLong()) return null
        return FollowTarget(funcIdx = idx.toInt(), section = WasmSectionKinds.FUNCTION)
    }

    /** Reflect the per-subtype `index` UInt across all `WasmExportRecordEntry` variants. */
    private fun exportIndexOf(record: WasmExportRecordEntry): UInt = when (record) {
        is WasmFunctionExportRecordEntry -> record.index
        is org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry -> record.index
        is org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry -> record.index
        is org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry -> record.index
        is org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry -> record.index
        is org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry -> record.index
    }

    /** Mnemonic + optional immediate-as-string for an init-expression opcode. */
    private fun initExprWatPair(op: OpCode): Pair<String, String?> = when (op) {
        is I32.Const -> "i32.const" to op.value.toString()
        is I64.Const -> "i64.const" to op.value.toString()
        is F32.Const -> "f32.const" to op.value.toString()
        is F64.Const -> "f64.const" to op.value.toString()
        is OpCodes.Global.Get -> "global.get" to (op as OpCodeWithValue<*>).value.toString()
        else -> (op::class.simpleName?.lowercase() ?: "?") to null
    }

    /**
     * Returns the on-disk byte offset of the first payload byte for the
     * [chunk]'s Data entry. Every record encoding ends with `length ULEB +
     * payload bytes`, so the payload is anchored against the chunk's end
     * minus the payload size; the header (mode byte + optional memidx +
     * init-expr + length ULEB) precedes it.
     */
    private fun dataPayloadStart(chunk: BinaryChunk.Element, record: WasmDataRecord): Long =
        chunk.byteRange.last + 1L - record.data.size.toLong()

    /**
     * Phase-1 cap on the number of body rows rendered per data entry when
     * expanded. Skiko's `.rodata`-style data sections reach tens of MB,
     * which is millions of body rows; without a cap, expanding even once
     * composes them all into a single LazyColumn slot. Phase 2 will
     * virtualise body rows themselves so this can drop entirely.
     */
    private val DATA_BODY_ROW_CAP = 256

    private fun buildDataRows(
        snapshot: BinaryDocumentSnapshot,
        chunk: BinaryChunk.Element,
        headerTokens: List<RenderToken>,
        viewMode: DataViewMode,
    ): List<RenderedRow> {
        val record = dataRecords.getOrNull(chunk.indexInSection)
            ?: return listOf(RenderedRow(tokens = headerTokens, kind = RowKind.DataHeader))
        val out = ArrayList<RenderedRow>(2 + DATA_BODY_ROW_CAP)
        out += RenderedRow(tokens = headerTokens, kind = RowKind.DataHeader)
        val byteCount = record.data.size
        val source = snapshot.byteSource()
        if (byteCount == 0) {
            // No payload — still emit the descriptor rows so the user can
            // see mode + (memidx) + init-expr breakdown when expanded.
            out += RenderedRow(
                tokens = listOf(RenderToken("", RenderTokenKind.COMMENT)),
                kind = RowKind.DataExpander(0),
            )
            out += buildDescriptorRows(chunk, record, source)
            return out
        }
        out += RenderedRow(
            tokens = listOf(RenderToken("", RenderTokenKind.COMMENT)),
            kind = RowKind.DataExpander(byteCount),
        )
        // Descriptor rows (mode / memidx / init-expr opcodes / payload-len
        // ULEB) — between expander and body so the descriptor bytes are
        // visually split from the data bytes the user asked for.
        out += buildDescriptorRows(chunk, record, source)
        out += byteBodyRows(source, dataPayloadStart(chunk, record), byteCount, viewMode)
        return out
    }

    /**
     * Custom-section body rows, mirroring the Data-entry layout: header
     * (offset + hex + name comment) + an expander + raw content bytes in the
     * requested [viewMode]. Reuses the Data RowKinds so [ChunkRow] folds it
     * and shows the u8/u16/u32/u64/ascii/utf8 mode bar with no extra UI. The
     * whole section content (name string included) is shown as raw bytes;
     * the name is decoded into the header comment.
     */
    private fun buildCustomRows(
        snapshot: BinaryDocumentSnapshot,
        chunk: BinaryChunk.Element,
        headerTokens: List<RenderToken>,
        viewMode: DataViewMode,
    ): List<RenderedRow> {
        val out = ArrayList<RenderedRow>(2 + DATA_BODY_ROW_CAP)
        out += RenderedRow(tokens = headerTokens, kind = RowKind.DataHeader)
        val byteCount = (chunk.byteRange.last - chunk.byteRange.first + 1L).toInt().coerceAtLeast(0)
        if (byteCount == 0) return out
        out += RenderedRow(
            tokens = listOf(RenderToken("", RenderTokenKind.COMMENT)),
            kind = RowKind.DataExpander(byteCount),
        )
        out += byteBodyRows(snapshot.byteSource(), chunk.byteRange.first, byteCount, viewMode)
        return out
    }

    /**
     * Body rows for a contiguous byte range in the requested [viewMode], one
     * per [bytesPerRow], capped at [DATA_BODY_ROW_CAP] with an elision row.
     * Shared by Data entries and custom sections.
     */
    private fun byteBodyRows(
        source: ByteSource,
        start: Long,
        byteCount: Int,
        viewMode: DataViewMode,
    ): List<RenderedRow> {
        val out = ArrayList<RenderedRow>()
        val bytesPerRow = bytesPerRow(viewMode)
        val totalRows = (byteCount + bytesPerRow - 1) / bytesPerRow
        val renderedRows = minOf(totalRows, DATA_BODY_ROW_CAP)
        for (r in 0 until renderedRows) {
            val rowStart = start + (r.toLong() * bytesPerRow.toLong())
            val len = minOf(bytesPerRow, byteCount - r * bytesPerRow)
            out += RenderedRow(
                tokens = dataBodyRowTokens(rowStart, source, len, viewMode),
                kind = RowKind.DataBody,
            )
        }
        if (totalRows > DATA_BODY_ROW_CAP) {
            val elidedBytes = byteCount - DATA_BODY_ROW_CAP * bytesPerRow
            out += RenderedRow(
                tokens = listOf(
                    RenderToken(" ".repeat(GUTTER_WIDTH + GAP), RenderTokenKind.WHITESPACE),
                    comment("(; … ${formatByteCount(elidedBytes)} elided (Phase 3: row virtualisation) ;)"),
                ),
                kind = RowKind.DataBody,
            )
        }
        return out
    }

    private fun renderCustomElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitOffsetGutter(chunk.byteRange.first, tokens)
        emitChunkHexColumn(chunk, source, tokens)
        val byteCount = (chunk.byteRange.last - chunk.byteRange.first + 1L).toInt().coerceAtLeast(0)
        val name = chunk.displayName ?: "custom"
        tokens += comment("(; \"$name\"  ${formatByteCount(byteCount)} ;)")
    }

    private fun bytesPerRow(mode: DataViewMode): Int = when (mode) {
        DataViewMode.ASCII, DataViewMode.UTF8 -> DATA_BYTES_PER_ROW_TEXT
        else -> DATA_BYTES_PER_ROW
    }

    /** Dispatches to the per-mode row builder. */
    private fun dataBodyRowTokens(
        rowStart: Long,
        source: ByteSource,
        length: Int,
        mode: DataViewMode,
    ): List<RenderToken> = when (mode) {
        DataViewMode.U8_HEX -> uIntHexRow(rowStart, source, length, bytesPerValue = 1)
        DataViewMode.U16_HEX -> uIntHexRow(rowStart, source, length, bytesPerValue = 2)
        DataViewMode.U32_HEX -> uIntHexRow(rowStart, source, length, bytesPerValue = 4)
        DataViewMode.U64_HEX -> uIntHexRow(rowStart, source, length, bytesPerValue = 8)
        DataViewMode.ASCII -> asciiRow(rowStart, source, length)
        DataViewMode.UTF8 -> utf8Row(rowStart, source, length)
    }

    /**
     * Generic hex row for u8 / u16 / u32 / u64 modes. Each value is
     * [bytesPerValue] bytes packed little-endian, displayed MSB-first
     * (the conventional `0xAABBCCDD` form). Always 16 bytes consumed per
     * row + a 16-char ASCII sidecar on the right. Partial trailing values
     * (when the last row has fewer than 16 bytes) leave the missing high
     * bytes blank in the hex column, preserving column alignment.
     */
    private fun uIntHexRow(
        rowStart: Long,
        source: ByteSource,
        length: Int,
        bytesPerValue: Int,
    ): List<RenderToken> {
        val tokens = ArrayList<RenderToken>(4)
        emitOffsetGutter(rowStart, tokens)
        val valuesPerRow = DATA_BYTES_PER_ROW / bytesPerValue
        val hex = StringBuilder(valuesPerRow * (bytesPerValue * 2 + 1))
        for (v in 0 until valuesPerRow) {
            val valueStart = v * bytesPerValue
            for (i in (bytesPerValue - 1) downTo 0) {
                val byteIdx = valueStart + i
                if (byteIdx < length) {
                    val b = source.get(rowStart + byteIdx).toInt() and 0xFF
                    hex.append(b.toString(16).uppercase().padStart(2, '0'))
                } else {
                    hex.append("  ")
                }
            }
            hex.append(' ')
        }
        val ascii = StringBuilder(DATA_BYTES_PER_ROW)
        for (i in 0 until DATA_BYTES_PER_ROW) {
            if (i < length) {
                val b = source.get(rowStart + i).toInt() and 0xFF
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            } else {
                ascii.append(' ')
            }
        }
        tokens += RenderToken(hex.toString(), RenderTokenKind.RAW_BYTES, sourceOffset = rowStart)
        tokens += RenderToken(" | ", RenderTokenKind.WHITESPACE)
        tokens += RenderToken(ascii.toString(), RenderTokenKind.COMMENT)
        return tokens
    }

    /**
     * Plain ASCII row: 64 chars per row, printable bytes (0x20..0x7E) as
     * themselves, everything else as `.`. No hex column — the toggle is
     * specifically for reading the string content end-to-end.
     */
    private fun asciiRow(rowStart: Long, source: ByteSource, length: Int): List<RenderToken> {
        val tokens = ArrayList<RenderToken>(2)
        emitOffsetGutter(rowStart, tokens)
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val b = source.get(rowStart + i).toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        tokens += RenderToken(sb.toString(), RenderTokenKind.COMMENT, sourceOffset = rowStart)
        return tokens
    }

    /**
     * UTF-8 decode of the row's bytes, with control chars + the U+FFFD
     * replacement char folded to `.`. Limits per-row width to the same
     * 64-byte input window as [asciiRow] (UTF-8 expands to fewer chars,
     * never more), so rows stay aligned to the offset gutter.
     */
    private fun utf8Row(rowStart: Long, source: ByteSource, length: Int): List<RenderToken> {
        val tokens = ArrayList<RenderToken>(2)
        emitOffsetGutter(rowStart, tokens)
        val bytes = ByteArray(length) { source.get(rowStart + it) }
        val decoded = bytes.decodeToString(0, length, throwOnInvalidSequence = false)
        val sb = StringBuilder(decoded.length)
        for (ch in decoded) {
            val code = ch.code
            // Strip ISO controls (C0 + DEL + C1) and the U+FFFD replacement
            // glyph the decoder emits for invalid sequences so they don't
            // confuse offset-gutter alignment in fonts that render them wide.
            val isControl = code < 0x20 || code in 0x7F..0x9F
            val isReplacement = code == 0xFFFD
            sb.append(if (isControl || isReplacement) '.' else ch)
        }
        tokens += RenderToken(sb.toString(), RenderTokenKind.COMMENT, sourceOffset = rowStart)
        return tokens
    }


    /**
     * One row per Function-section entry: `offset  <hex>  (; func[i] :
     * type[N]  →  $name ;)`. The Function section is index-only metadata
     * (each entry is just a ULEB128 typeidx), so we surface it as the
     * declaration row — Cmd+B on a `call N` site lands here, then a second
     * Cmd+B (or a click) continues to the Code body.
     *
     * `name` falls back to `func<funcIdx>` when neither name section, DWARF,
     * nor exports resolved a real symbol — mirrors the placeholder the Code
     * section uses for the same entry, so the two rows visually pair up.
     */
    private fun renderFunctionElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitOffsetGutter(chunk.byteRange.first, tokens)
        emitChunkHexColumn(chunk, source, tokens)
        val funcIdx = provider.importFuncCount + chunk.indexInSection
        val typeIdx = funcTypeIdxs.getOrNull(chunk.indexInSection)
        val name = chunk.displayName ?: "func$funcIdx"
        val typeRef = typeIdx?.toString() ?: "?"
        tokens += comment("(; func[$funcIdx] : type[$typeRef]  →  \$$name ;)")
    }

    /** Cached list of `typeidx` values, one per Function-section entry. */
    private val funcTypeIdxs: List<Int> by lazy {
        val section = provider.wasmModule?.functionSection ?: return@lazy emptyList()
        with(section) { provider.bytes.parseFunctions() }
    }

    private fun renderSectionHeader(chunk: BinaryChunk.SectionHeader, tokens: MutableList<RenderToken>) {
        emitOffsetGutter(chunk.byteRange.first, tokens)
        val text = "(; ${WasmSectionKinds.displayName(chunk.kind)} section: " +
            "${chunk.elementCount} entries, ${formatBytes(chunk.totalSize)} ;)"
        tokens += RenderToken(text, RenderTokenKind.COMMENT, sourceOffset = chunk.byteRange.first)
    }

    /**
     * Single-line WAT rendering for [chunk]: offset, then a fixed-width hex
     * preview of the chunk's leading bytes, then the declaration produced by
     * [build]. Used by every structured element kind (Type / Memory / Table /
     * Global / Import / Export).
     */
    private inline fun renderStructuredElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
        build: (MutableList<RenderToken>) -> Unit,
    ) {
        emitOffsetGutter(chunk.byteRange.first, tokens)
        emitChunkHexColumn(chunk, source, tokens)
        build(tokens)
    }

    /**
     * Element rendering for unstructured kinds (Element / Data / DataCount /
     * Custom / Start / Function): the unified prefix `<offset><hex>` followed
     * by a `(; <kind>#<idx> ;)` label. Hex preview width matches code body
     * rows so columns line up across heterogeneous chunks.
     */
    private fun renderInlineHexElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitOffsetGutter(chunk.byteRange.first, tokens)
        emitChunkHexColumn(chunk, source, tokens)
        val label = chunk.displayName
            ?: "${WasmSectionKinds.displayName(chunk.kind)}#${chunk.indexInSection}"
        tokens += comment("(; $label ;)")
    }

    /**
     * Emits the hex column for an element chunk: up to [MAX_BYTES_DISPLAYED]
     * bytes from `chunk.byteRange.first`, padded to [BYTES_WIDTH] + a gap.
     * Same column geometry as code-body rows so all rows align vertically.
     */
    private fun emitChunkHexColumn(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitHexBytes(chunk.byteRange.first, chunk.byteRange.last + 1L, source, tokens)
    }

    private fun renderMemoryElement(chunk: BinaryChunk.Element, source: ByteSource, tokens: MutableList<RenderToken>) {
        val record = memoryRecords.getOrNull(chunk.indexInSection)
        renderStructuredElement(chunk, source, tokens) { out ->
            out += punct("(")
            out += keyword("memory")
            out += space()
            out += RenderToken("\$mem${chunk.indexInSection}", RenderTokenKind.IDENTIFIER, sourceOffset = chunk.byteRange.first)
            if (record != null) {
                appendLimits(out, record.limits)
            }
            out += punct(")")
        }
    }

    private fun renderTableElement(chunk: BinaryChunk.Element, source: ByteSource, tokens: MutableList<RenderToken>) {
        val record = tableRecords.getOrNull(chunk.indexInSection)
        renderStructuredElement(chunk, source, tokens) { out ->
            out += punct("(")
            out += keyword("table")
            out += space()
            out += RenderToken("\$table${chunk.indexInSection}", RenderTokenKind.IDENTIFIER, sourceOffset = chunk.byteRange.first)
            if (record != null) {
                appendLimits(out, record.tableType.limits)
                out += space()
                out += typeRef(referenceTypeName(record.tableType.referenceType))
            }
            out += punct(")")
        }
    }

    private fun renderGlobalElement(chunk: BinaryChunk.Element, source: ByteSource, tokens: MutableList<RenderToken>) {
        val record = globalRecords.getOrNull(chunk.indexInSection)
        renderStructuredElement(chunk, source, tokens) { out ->
            out += punct("(")
            out += keyword("global")
            out += space()
            out += RenderToken("\$global${chunk.indexInSection}", RenderTokenKind.IDENTIFIER, sourceOffset = chunk.byteRange.first)
            if (record != null) {
                out += space()
                if (record.type.mutable) {
                    out += punct("(")
                    out += keyword("mut")
                    out += space()
                    out += typeRef(wasmValueTypeName(record.type.valueType))
                    out += punct(")")
                } else {
                    out += typeRef(wasmValueTypeName(record.type.valueType))
                }
                // Init expression — full opcode rendering is a follow-up; for now,
                // surface the byte length as a comment.
                out += space()
                val initLen = (chunk.byteRange.last - record.offset.toLong()).coerceAtLeast(0L)
                out += comment("(; init expr, ${initLen}B ;)")
            }
            out += punct(")")
        }
    }

    private fun renderImportElement(chunk: BinaryChunk.Element, source: ByteSource, tokens: MutableList<RenderToken>) {
        val record = provider.imports.getOrNull(chunk.indexInSection)
        renderStructuredElement(chunk, source, tokens) { out ->
            out += punct("(")
            out += keyword("import")
            if (record != null) {
                out += space()
                out += RenderToken("\"${record.prefix}\"", RenderTokenKind.STRING)
                out += space()
                out += RenderToken("\"${record.name}\"", RenderTokenKind.STRING)
                out += space()
                appendImportDescriptor(out, record)
            }
            out += punct(")")
        }
    }

    private fun appendImportDescriptor(out: MutableList<RenderToken>, record: WasmImportRecord) {
        out += punct("(")
        when (val type = record.type) {
            is WasmTypeIndexExternalType -> {
                out += keyword("func")
                out += space()
                out += punct("(")
                out += keyword("type")
                out += space()
                out += RenderToken(type.index.toString(), RenderTokenKind.NUMBER)
                out += punct(")")
            }
            is WasmTableExternalType -> {
                out += keyword("table")
                appendLimits(out, type.limits)
                out += space()
                out += typeRef(referenceTypeName(type.referenceType))
            }
            is WasmMemType -> {
                out += keyword("memory")
                appendLimits(out, type.limits)
            }
            is WasmGlobalType -> {
                out += keyword("global")
                out += space()
                if (type.mutable) {
                    out += punct("(")
                    out += keyword("mut")
                    out += space()
                    out += typeRef(wasmValueTypeName(type.valueType))
                    out += punct(")")
                } else {
                    out += typeRef(wasmValueTypeName(type.valueType))
                }
            }
            is WasmTagType -> {
                out += keyword("tag")
                out += space()
                out += punct("(")
                out += keyword("type")
                out += space()
                out += RenderToken(type.index.index.toString(), RenderTokenKind.NUMBER)
                out += punct(")")
            }
        }
        out += punct(")")
    }

    private fun renderExportElement(chunk: BinaryChunk.Element, source: ByteSource, tokens: MutableList<RenderToken>) {
        val record = provider.exports.getOrNull(chunk.indexInSection)
        renderStructuredElement(chunk, source, tokens) { out ->
            out += punct("(")
            out += keyword("export")
            if (record != null) {
                out += space()
                out += RenderToken("\"${record.name}\"", RenderTokenKind.STRING)
                out += space()
                out += punct("(")
                out += keyword(exportKindKeyword(record))
                out += space()
                out += RenderToken(exportIndex(record).toString(), RenderTokenKind.NUMBER)
                out += punct(")")
            }
            out += punct(")")
        }
    }

    private fun exportKindKeyword(record: WasmExportRecordEntry): String = when (record) {
        is WasmFunctionExportRecordEntry -> "func"
        is WasmTableExportRecordEntry -> "table"
        is WasmMemoryExportRecordEntry -> "memory"
        is WasmGlobalExportRecordEntry -> "global"
        is WasmTagExportRecordEntry -> "tag"
        else -> "extern"
    }

    private fun exportIndex(record: WasmExportRecordEntry): UInt = when (record) {
        is WasmFunctionExportRecordEntry -> record.index
        is WasmTableExportRecordEntry -> record.index
        is WasmMemoryExportRecordEntry -> record.index
        is WasmGlobalExportRecordEntry -> record.index
        is WasmTagExportRecordEntry -> record.index
        else -> 0u
    }

    private fun renderTypeElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        val record = typeRecords.getOrNull(chunk.indexInSection)
        renderStructuredElement(chunk, source, tokens) { out ->
            out += punct("(")
            out += keyword("type")
            out += space()
            out += RenderToken("\$type${chunk.indexInSection}", RenderTokenKind.IDENTIFIER, sourceOffset = chunk.byteRange.first)
            if (record != null) {
                out += space()
                appendTypeRecordBody(out, record)
            }
            out += punct(")")
        }
    }

    private fun appendTypeRecordBody(out: MutableList<RenderToken>, record: WasmTypeRecord) {
        when (record) {
            is WasmSectionFunctionTypeRecord -> appendFuncType(out, record)
            is WasmStructTypeRecord -> appendStructType(out, record)
            is WasmArrayTypeRecord -> appendArrayType(out, record)
            is WasmRecursiveTypeRecord -> appendRecType(out, record)
        }
    }

    private fun appendFuncType(out: MutableList<RenderToken>, record: WasmSectionFunctionTypeRecord) {
        out += punct("(")
        out += keyword("func")
        if (record.params.isNotEmpty()) {
            out += space()
            out += punct("(")
            out += keyword("param")
            for (p in record.params) {
                out += space()
                out += typeRef(wasmValueTypeName(p))
            }
            out += punct(")")
        }
        if (record.retTypes.isNotEmpty()) {
            out += space()
            out += punct("(")
            out += keyword("result")
            for (r in record.retTypes) {
                out += space()
                out += typeRef(wasmValueTypeName(r))
            }
            out += punct(")")
        }
        out += punct(")")
    }

    private fun appendStructType(out: MutableList<RenderToken>, record: WasmStructTypeRecord) {
        out += punct("(")
        out += keyword("struct")
        for (field in record.fields) {
            out += space()
            appendFieldType(out, field)
        }
        out += punct(")")
        if (record.superTypes.isNotEmpty()) {
            out += space()
            val supers = record.superTypes.joinToString(",") { "\$type$it" }
            out += comment("(; sub $supers${if (record.isFinal) " final" else ""} ;)")
        }
    }

    private fun appendArrayType(out: MutableList<RenderToken>, record: WasmArrayTypeRecord) {
        out += punct("(")
        out += keyword("array")
        out += space()
        appendFieldType(out, record.fieldType)
        out += punct(")")
    }

    private fun appendRecType(out: MutableList<RenderToken>, record: WasmRecursiveTypeRecord) {
        out += punct("(")
        out += keyword("rec")
        for (entry in record.entries) {
            out += space()
            appendTypeRecordBody(out, entry)
        }
        out += punct(")")
    }

    private fun appendFieldType(out: MutableList<RenderToken>, field: WasmFieldType) {
        if (field.isMutable) {
            out += punct("(")
            out += keyword("mut")
            out += space()
            out += typeRef(storageTypeName(field.storageType))
            out += punct(")")
        } else {
            out += typeRef(storageTypeName(field.storageType))
        }
    }

    private fun storageTypeName(type: WasmValueType<*>): String = when (type) {
        is WasmPactTypeI8 -> "i8"
        is WasmPactTypeI16 -> "i16"
        is WasmPactType -> "pact?"
        else -> wasmValueTypeName(type)
    }

    // Inlined to avoid pulling `plugins-shared` into `binaryProvider` for one
    // helper. A member (not top-level) so reference types can route through
    // [referenceTypeName] for the full `(ref null $t)` / `funcref` / `structref`
    // / … spelling instead of falling back to the data-class `toString()`.
    private fun wasmValueTypeName(type: WasmValueType<*>): String = when (type) {
        is WasmI32Type -> "i32"
        is WasmI64Type -> "i64"
        is WasmF32Type -> "f32"
        is WasmF64Type -> "f64"
        is WasmVecType -> "v128"
        is WasmRefType -> referenceTypeName(type.code)
        else -> type.toString()
    }

    private fun appendLimits(out: MutableList<RenderToken>, limits: WasmLimits<*>) {
        out += space()
        out += RenderToken(limits.min.toString(), RenderTokenKind.NUMBER)
        when (limits) {
            is WasmFixedI32Limits -> {
                out += space()
                out += RenderToken(limits.max.toString(), RenderTokenKind.NUMBER)
                if (limits.shared) {
                    out += space()
                    out += keyword("shared")
                }
            }
            is WasmFixedI64Limits -> {
                out += space()
                out += RenderToken(limits.max.toString(), RenderTokenKind.NUMBER)
            }
            else -> Unit
        }
    }

    private fun referenceTypeName(ref: WasmReferenceType): String {
        val core = heapTypeName(ref.heapType)
        return when (ref) {
            is WasmNullableReferenceType -> "(ref null $core)"
            is WasmNonNullableReferenceType -> "(ref $core)"
            is WasmReferenceHeapType -> when (ref.heapType) {
                is WasmFuncHeapType -> "funcref"
                is WasmExternRefHeapType -> "externref"
                is WasmAnyRefHeapType -> "anyref"
                is WasmEqRefHeapType -> "eqref"
                is WasmI31RefHeapType -> "i31ref"
                is WasmStructRefHeapType -> "structref"
                is WasmArrayRefHeapType -> "arrayref"
                is WasmExnRefHeapType -> "exnref"
                is WasmNoneHeapType -> "nullref"
                is WasmNoExternRefHeapType -> "nullexternref"
                is WasmNoFuncHeapType -> "nullfuncref"
                is WasmNoExnHeapType -> "nullexnref"
                else -> core
            }
        }
    }

    private fun heapTypeName(heap: WasmHeapType): String = when (heap) {
        is WasmFuncHeapType -> "func"
        is WasmExternRefHeapType -> "extern"
        is WasmAnyRefHeapType -> "any"
        is WasmEqRefHeapType -> "eq"
        is WasmI31RefHeapType -> "i31"
        is WasmStructRefHeapType -> "struct"
        is WasmArrayRefHeapType -> "array"
        is WasmExnRefHeapType -> "exn"
        is WasmNoneHeapType -> "none"
        is WasmNoExternRefHeapType -> "noextern"
        is WasmNoFuncHeapType -> "nofunc"
        is WasmNoExnHeapType -> "noexn"
        is WasmIndexHeapType -> "\$type${heap.type}"
        is WasmAbsHeapType -> "abs?"
    }

    private fun renderCodeElement(
        chunk: BinaryChunk.Element,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        val codeIndex = chunk.indexInSection
        val funcIdx = importFuncCount + codeIndex
        val resolvedName = resolver.resolve(SymbolKind.FUNCTION, funcIdx)
        val displayName = resolvedName ?: "func$funcIdx"
        val signature = provider.functions.getOrNull(codeIndex) as? WasmSectionFunctionTypeRecord

        // Code-entry header: the size ULEB128 prefix that introduces every
        // function body. Show its bytes in the hex column so the encoding of
        // the entry header is visible alongside the (func …) signature.
        val entryStart = chunk.byteRange.first
        val sizeUlebLen = decodeULEB128(source, entryStart).byteCount
        emitOffsetGutter(entryStart, tokens)
        emitHexBytes(entryStart, entryStart + sizeUlebLen.toLong(), source, tokens)
        tokens += punct("(")
        tokens += keyword("func")
        tokens += space()
        tokens += RenderToken("\$$displayName", RenderTokenKind.FUNC_REF, sourceOffset = chunk.byteRange.first)
        if (signature != null) {
            if (signature.params.isNotEmpty()) {
                tokens += space()
                tokens += punct("(")
                tokens += keyword("param")
                for (p in signature.params) {
                    tokens += space()
                    tokens += typeRef(wasmValueTypeName(p))
                }
                tokens += punct(")")
            }
            if (signature.retTypes.isNotEmpty()) {
                tokens += space()
                tokens += punct("(")
                tokens += keyword("result")
                for (r in signature.retTypes) {
                    tokens += space()
                    tokens += typeRef(wasmValueTypeName(r))
                }
                tokens += punct(")")
            }
        }
        tokens += space()
        tokens += RenderToken("(; funcidx $funcIdx ;)", RenderTokenKind.COMMENT)
        tokens += linebreak()

        val codeRecord: WasmCodeRecord? = runCatching {
            provider.parseCodeRecord(codeIndex)
        }.getOrNull()

        if (codeRecord == null) {
            emitStructuralRowPrefix(chunk.byteRange.first, tokens)
            tokens += indent(1)
            tokens += RenderToken(
                "(; failed to parse function body ;)",
                RenderTokenKind.ERROR,
                sourceOffset = chunk.byteRange.first,
            )
            tokens += linebreak()
            emitStructuralRowPrefix(chunk.byteRange.last, tokens)
            tokens += punct(")")
            return
        }

        // First-opcode offset. Used as the upper bound for the locals
        // declaration byte range and as the cursor start for emitOps.
        val functionEnd = codeRecord.offset.toLong() + codeRecord.size.toLong() - 1L
        val initialCursor = (codeRecord.opCodes.firstOrNull() as? OpCodeWithOffset)
            ?.offset?.toLong()?.takeIf { it > 0L }
            ?: estimateBodyStart(codeRecord)

        // Locals: (local <type> ...) — RLE-expanded so each slot's type is visible.
        // Hex column shows the locals-decl bytes (count ULEB + per-group encoding).
        if (codeRecord.locals.isNotEmpty()) {
            val localsStart = entryStart + sizeUlebLen.toLong()
            emitOffsetGutter(localsStart, tokens)
            emitHexBytes(localsStart, initialCursor, source, tokens)
            tokens += indent(1)
            tokens += punct("(")
            tokens += keyword("local")
            for (group in codeRecord.locals) {
                repeat(group.local) {
                    tokens += space()
                    tokens += typeRef(wasmValueTypeName(group.type))
                }
            }
            tokens += punct(")")
            tokens += linebreak()
        }
        emitOps(
            ops = codeRecord.opCodes,
            depth = 1,
            startCursor = initialCursor,
            source = source,
            tokens = tokens,
        )

        // Closing line for the function: bytes column shows the 0x0B end byte.
        emitFunctionClose(functionEnd, source, tokens)
    }

    /**
     * Falls back when the first opcode is a singleton with offset 0. Walks
     * the locals declarations from `codeRecord.offset` to find the first
     * opcode byte. The `WasmCodeRecord.locals` list holds RLE pairs and the
     * record itself is preceded by a count ULEB128.
     */
    private fun estimateBodyStart(codeRecord: WasmCodeRecord): Long {
        // codeRecord.offset typically points at the locals-count ULEB128.
        // Walk: 1 ULEB128 (count) + N * (1 ULEB128 (run length) + 1 valuetype byte).
        // Using 4 bytes per group as a safe upper bound; if we overshoot the
        // emitted offset will simply be too high but won't crash.
        val groupBytes = codeRecord.locals.size.toLong() * 4L
        return codeRecord.offset.toLong() + 1L + groupBytes
    }

    /**
     * Walks [ops] in tree order, advancing a byte cursor by each opcode's
     * **encoded length** (computed by [opcodeLength]). Returns the cursor
     * position after the last child — for nested calls this is the offset of
     * the closing `0x0B` byte.
     *
     * Why not trust `op.offset`: numeric / parametric / control opcodes are
     * implemented as singleton `object`s in `OpCodes` (e.g. `I32.Add`,
     * `Drop`). Their `offset` field is shared and always 0. `op.offset` is
     * therefore used only as an *anchor* when non-zero (re-syncs the cursor
     * after a long-tail opcode whose length we don't know).
     */
    private fun emitOps(
        ops: List<OpCode>,
        depth: Int,
        startCursor: Long,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ): Long {
        var cursor = startCursor
        for (op in ops) {
            // Anchor to a real per-instance offset when the parser stored one.
            val anchor = (op as? OpCodeWithOffset)?.offset?.toLong()?.takeIf { it > 0L }
            if (anchor != null && anchor >= cursor) cursor = anchor

            when (op) {
                is OpCodes.Flow.Block, is OpCodes.Flow.Loop, is OpCodes.Flow.If, is OpCodes.Flow.Else -> {
                    val children = @Suppress("UNCHECKED_CAST") (
                        ((op as OpCodeWithValue<*>).value as? List<OpCode>) ?: emptyList()
                    )
                    val headerLen = blockHeaderLength(source, cursor)
                    val opStart = cursor
                    cursor += headerLen
                    emitOpcodeLine(op, opStart, cursor, depth, source, tokens)
                    cursor = emitOps(children, depth + 1, cursor, source, tokens)
                    // The byte at `cursor` should now be 0x0B (block's closing end).
                    if (cursor < source.length) {
                        emitSyntheticEnd(cursor, depth, source, tokens)
                    }
                    cursor += 1L
                }
                else -> {
                    val opStart = cursor
                    val length = opcodeLength(op, cursor, source).coerceAtLeast(1)
                    cursor += length
                    emitOpcodeLine(op, opStart, cursor, depth, source, tokens)
                }
            }
        }
        return cursor
    }

    private fun emitOpcodeLine(
        op: OpCode,
        opStart: Long,
        opEnd: Long,
        depth: Int,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitOffsetGutter(opStart, tokens)
        emitHexBytes(opStart, opEnd, source, tokens)
        emitIndent(depth, tokens)
        tokens += keyword(splitMnemonic(op).first)
        val rest = splitMnemonic(op).second
        if (rest.isNotEmpty()) {
            tokens += RenderToken(rest, RenderTokenKind.IDENTIFIER)
        }
        // Dual-rep annotation for ULEB/SLEB immediates with >1 encoded byte.
        emitLebAnnotation(op, opStart, opEnd, source, tokens)
        // Decode exception-handling operands: what tag an index refers to and
        // where a catch clause branches.
        emitExceptionAnnotation(op, tokens)
        tokens += linebreak()
    }

    private val exceptionTagsForAnnotation by lazy { provider.exceptionTags }

    /**
     * Trailing `(; … ;)` comment clarifying the tag/label indices of an
     * exception instruction: `throw N` gets the tag's payload signature, a
     * `try_table` decodes each catch clause (tag + payload, or catch_all, and
     * the branch label it targets), and `throw_ref` is marked dynamic.
     */
    private fun emitExceptionAnnotation(op: OpCode, tokens: MutableList<RenderToken>) {
        fun tagSuffix(tag: Int): String = exceptionTagsForAnnotation?.payloadText(tag)?.let { " $it" } ?: ""
        val text = when (op) {
            is OpCodes.Flow.Trow -> {
                val t = op.value.toInt()
                "(; tag $t${tagSuffix(t)} ;)"
            }
            is OpCodes.Flow.ThrowRef -> "(; rethrows a caught exception (dynamic) ;)"
            is OpCodes.Flow.TryTable -> {
                if (op.catches.isEmpty()) {
                    "(; no catch clauses — body exceptions propagate ;)"
                } else {
                    "(; " + op.catches.joinToString("  ") { catchAnnotation(it, ::tagSuffix) } + " ;)"
                }
            }
            else -> return
        }
        tokens += space()
        tokens += RenderToken(text, RenderTokenKind.COMMENT)
    }

    private fun catchAnnotation(c: OpCodes.Flow.CatchType, tagSuffix: (Int) -> String): String = when (c) {
        is OpCodes.Flow.CatchType.Catch -> "catch tag ${c.tagIdx}${tagSuffix(c.tagIdx.toInt())} -> label ${c.labelIdx}"
        is OpCodes.Flow.CatchType.CatchRef -> "catch_ref tag ${c.tagIdx}${tagSuffix(c.tagIdx.toInt())} -> label ${c.labelIdx}"
        is OpCodes.Flow.CatchType.CatchAll -> "catch_all -> label ${c.labelIdx}"
        is OpCodes.Flow.CatchType.CatchAllRef -> "catch_all_ref -> label ${c.labelIdx}"
    }

    private fun splitMnemonic(op: OpCode): Pair<String, String> {
        val full = watFor(op, if (symbolicOperands) watSymbols else null)
        val space = full.indexOf(' ')
        return if (space < 0) full to "" else full.substring(0, space) to full.substring(space)
    }

    private fun emitSyntheticEnd(
        endByteOffset: Long,
        depth: Int,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        emitOffsetGutter(endByteOffset, tokens)
        emitHexBytes(endByteOffset, endByteOffset + 1L, source, tokens)
        emitIndent(depth, tokens)
        tokens += keyword("end")
        tokens += linebreak()
    }

    private fun emitFunctionClose(endByteOffset: Long, source: ByteSource, tokens: MutableList<RenderToken>) {
        // The function's closing `0x0B` end byte goes in the hex column —
        // it's a code byte like every other instruction, so it follows the
        // code-row layout (gutter + hex + content) rather than the
        // structural-row layout used for `(func …)` / `(local …)`.
        emitOffsetGutter(endByteOffset, tokens)
        emitHexBytes(endByteOffset, endByteOffset + 1L, source, tokens)
        tokens += punct(")")
    }

    /**
     * Prefix for structural rows (function header, locals, fallback close):
     * offset gutter + a blank hex column padded to [BYTES_WIDTH]. Keeps these
     * rows column-aligned with code body rows even though no per-row bytes
     * are shown — the relevant bytes belong to a multi-byte declaration that
     * spans the whole function entry.
     */
    private fun emitStructuralRowPrefix(offset: Long, tokens: MutableList<RenderToken>) {
        emitOffsetGutter(offset, tokens)
        tokens += RenderToken(" ".repeat(BYTES_WIDTH), RenderTokenKind.WHITESPACE)
        tokens += RenderToken(" ".repeat(GAP), RenderTokenKind.WHITESPACE)
    }

    private fun emitOffsetGutter(offset: Long, tokens: MutableList<RenderToken>) {
        val text = if (offset < 0L) {
            " ".repeat(GUTTER_WIDTH) // unknown offset — render as blank gutter
        } else {
            ("0x" + offset.toString(16).uppercase().padStart(8, '0')).padEnd(GUTTER_WIDTH)
        }
        tokens += RenderToken(text, RenderTokenKind.OFFSET_ANNOTATION, sourceOffset = offset.takeIf { it >= 0L })
        tokens += RenderToken(" ".repeat(GAP), RenderTokenKind.WHITESPACE)
    }

    /**
     * Returns the encoded byte length of [op] when its first byte sits at
     * [atOffset] in [source]. Knows the encoding for every opcode whose
     * mnemonic [watFor] renders explicitly; falls through to length=1 for the
     * long-tail singletons (numeric Add/Sub/…/comparison ops, parametric)
     * since those are all 1-byte instructions.
     *
     * For opcodes outside our coverage where the length would be wrong,
     * the cursor will desync and subsequent offsets will be off — but
     * [emitHexBytes] is now defensive so this can't crash.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun opcodeLength(op: OpCode, atOffset: Long, source: ByteSource): Int {
        if (atOffset < 0L || atOffset >= source.length) return 1

        // Prefix-byte fast path: bulk memory (0xFC), SIMD (0xFD), atomics
        // (0xFE), GC (0xFB) all encode multi-byte and would desync the
        // cursor if we relied on op-type-based dispatch (the parser may
        // produce singletons or instances; we trust the bytes instead).
        val firstByte = source.get(atOffset).toInt() and 0xFF
        if (firstByte == 0xFB || firstByte == 0xFC || firstByte == 0xFD || firstByte == 0xFE) {
            return prefixedOpcodeLength(firstByte, source, atOffset)
        }

        return when (op) {
            // -- Memory load/store (1 + 2 ULEB128: align, offset) -----------
            is OpCodes.Memory.i32.Load, is OpCodes.Memory.i32.Load8_S, is OpCodes.Memory.i32.Load8_U,
            is OpCodes.Memory.i32.Load16_S, is OpCodes.Memory.i32.Load16_U,
            is OpCodes.Memory.i32.Store, is OpCodes.Memory.i32.Store8, is OpCodes.Memory.i32.Store16,
            is OpCodes.Memory.i64.Load, is OpCodes.Memory.i64.Load8_S, is OpCodes.Memory.i64.Load8_U,
            is OpCodes.Memory.i64.Load16_S, is OpCodes.Memory.i64.Load16_U,
            is OpCodes.Memory.i64.Load32_S, is OpCodes.Memory.i64.Load32_U,
            is OpCodes.Memory.i64.Store, is OpCodes.Memory.i64.Store8,
            is OpCodes.Memory.i64.Store16, is OpCodes.Memory.i64.Store32,
            is OpCodes.Memory.f32.Load, is OpCodes.Memory.f32.Store,
            is OpCodes.Memory.f64.Load, is OpCodes.Memory.f64.Store -> twoUleb128Length(source, atOffset + 1)

            // -- memory.size / memory.grow: 1 + 1 ULEB128 (memidx, usually 0)
            is OpCodes.Memory.Size, is OpCodes.Memory.Grow ->
                1 + decodeULEB128(source, atOffset + 1).byteCount

            // -- Variable / single-ULEB128 immediate ------------------------
            is OpCodes.Local.Get, is OpCodes.Local.Set, is OpCodes.Local.Tee,
            is OpCodes.Global.Get, is OpCodes.Global.Set,
            is OpCodes.Flow.Call, is OpCodes.Flow.ReturnCall,
            is OpCodes.Flow.CallRef, is OpCodes.Flow.ReturnCallRef,
            is OpCodes.Flow.Br, is OpCodes.Flow.BrIf,
            is OpCodes.Flow.BrOnNull, is OpCodes.Flow.BrOnNonNull,
            is OpCodes.Flow.Trow ->
                1 + decodeULEB128(source, atOffset + 1).byteCount

            // -- call_indirect: 1 + 2 ULEB128 (type idx, table idx) ---------
            is OpCodes.Flow.CallIndirect, is OpCodes.Flow.ReturnCallIndirect ->
                twoUleb128Length(source, atOffset + 1)

            // -- br_table: 1 + ULEB128 count + count*ULEB128 + ULEB128 default
            is OpCodes.Flow.BrTable -> brTableLength(source, atOffset + 1)

            // -- Numeric constants ------------------------------------------
            is I32.Const -> 1 + decodeSLEB128(source, atOffset + 1).byteCount
            is I64.Const -> 1 + decodeSLEB128(source, atOffset + 1).byteCount
            is F32.Const -> 5 // 1 opcode + 4 bytes IEEE 754 single
            is F64.Const -> 9 // 1 opcode + 8 bytes IEEE 754 double

            // -- Block / Loop / If header -----------------------------------
            is OpCodes.Flow.Block, is OpCodes.Flow.Loop, is OpCodes.Flow.If ->
                blockHeaderLength(source, atOffset)

            // -- 1-byte singletons (parametric, control, all numeric except Const)
            else -> 1
        }
    }

    /**
     * Length of `block`/`loop`/`if` opcode + its block-type encoding.
     *
     * Block type is one of:
     * - `0x40` = empty
     * - shorthand value/heap type byte (numtypes 0x7C–0x7F, v128 0x7B,
     *   reftypes 0x69–0x74 per `WasmSectionTypes.kt`)
     * - signed LEB128 (s33) type index
     */
    private fun blockHeaderLength(source: ByteSource, atOffset: Long): Int {
        if (atOffset + 1L >= source.length) return 1
        val typeByte = source.get(atOffset + 1L).toInt() and 0xFF
        return if (typeByte == 0x40 ||
            typeByte in 0x69..0x74 || // exnref…noexnref (full GC heaptype range)
            typeByte in 0x7B..0x7F   // v128, f64, f32, i64, i32
        ) {
            2 // opcode byte + 1-byte block-type
        } else {
            1 + decodeSLEB128(source, atOffset + 1L).byteCount
        }
    }

    private fun twoUleb128Length(source: ByteSource, atOffset: Long): Int {
        val a = decodeULEB128(source, atOffset)
        val b = decodeULEB128(source, atOffset + a.byteCount)
        return 1 + a.byteCount + b.byteCount
    }

    private fun brTableLength(source: ByteSource, atOffset: Long): Int {
        val count = decodeULEB128(source, atOffset)
        var total = count.byteCount
        repeat(count.value.toInt().coerceAtLeast(0)) {
            val entry = decodeULEB128(source, atOffset + total)
            total += entry.byteCount
        }
        val default = decodeULEB128(source, atOffset + total)
        total += default.byteCount
        return 1 + total
    }

    private fun emitHexBytes(opStart: Long, opEnd: Long, source: ByteSource, tokens: MutableList<RenderToken>) {
        val rawLength = (opEnd - opStart).coerceAtLeast(0L).toInt()
        // Defensive: never read past the end of the source. If the cursor has
        // drifted (long-tail opcode with unknown length, or off-by-one edge),
        // the bytes column simply renders empty for that row.
        val available = (source.length - opStart).coerceAtLeast(0L).toInt()
        val length = if (opStart < 0L) 0 else minOf(rawLength, available)
        val capped = length.coerceAtMost(MAX_BYTES_DISPLAYED)
        val hex = StringBuilder(BYTES_WIDTH)
        for (i in 0 until capped) {
            val b = source.get(opStart + i)
            if (i > 0) hex.append(' ')
            hex.append(((b.toInt() and 0xFF).toString(16).uppercase()).padStart(2, '0'))
        }
        if (length > MAX_BYTES_DISPLAYED) {
            hex.append(" …")
        }
        val padded = hex.toString().padEnd(BYTES_WIDTH)
        tokens += RenderToken(padded, RenderTokenKind.RAW_BYTES, sourceOffset = opStart.takeIf { it >= 0L })
        tokens += RenderToken(" ".repeat(GAP), RenderTokenKind.WHITESPACE)
    }

    private fun emitIndent(depth: Int, tokens: MutableList<RenderToken>) {
        if (depth > 0) tokens += RenderToken(" ".repeat(depth * 2), RenderTokenKind.WHITESPACE)
    }

    /**
     * If the opcode carries a ULEB128 or SLEB128 immediate that takes more than
     * one byte to encode, append a `(; uleb128 HH HH ;)` / `(; sleb128 HH HH ;)`
     * annotation showing the encoded form. The decoded value is already on the
     * mnemonic line; this surfaces the bytewise encoding next to it.
     *
     * Skips opcodes whose immediate is multi-component (e.g. CallIndirect's two
     * indices) — those are PR 2c work.
     */
    private fun emitLebAnnotation(
        op: OpCode,
        opStart: Long,
        opEnd: Long,
        source: ByteSource,
        tokens: MutableList<RenderToken>,
    ) {
        val immStart = opStart + 1L
        if (immStart >= opEnd) return

        val isSigned = op is I32.Const || op is I64.Const
        val isUnsignedSimple = op is OpCodes.Local.Get ||
            op is OpCodes.Local.Set ||
            op is OpCodes.Local.Tee ||
            op is OpCodes.Global.Get ||
            op is OpCodes.Global.Set ||
            op is OpCodes.Flow.Call ||
            op is OpCodes.Flow.ReturnCall ||
            op is OpCodes.Flow.CallRef ||
            op is OpCodes.Flow.ReturnCallRef ||
            op is OpCodes.Flow.Br ||
            op is OpCodes.Flow.BrIf ||
            op is OpCodes.Flow.BrOnNull ||
            op is OpCodes.Flow.BrOnNonNull

        if (!isSigned && !isUnsignedSimple) return

        val decoded = if (isSigned) {
            decodeSLEB128(source, immStart, maxBytes = (opEnd - immStart).toInt().coerceAtMost(10))
        } else {
            decodeULEB128(source, immStart, maxBytes = (opEnd - immStart).toInt().coerceAtMost(10))
        }
        if (decoded.byteCount <= 1) return

        val tag = if (isSigned) "sleb128" else "uleb128"
        tokens += RenderToken("  ", RenderTokenKind.WHITESPACE)
        tokens += RenderToken(
            "(; $tag ${decoded.rawBytes.toHexBytes()} ;)",
            RenderTokenKind.COMMENT,
        )
    }

    private fun keyword(s: String) = RenderToken(s, RenderTokenKind.KEYWORD)
    private fun punct(s: String) = RenderToken(s, RenderTokenKind.PUNCTUATION)
    private fun typeRef(s: String) = RenderToken(s, RenderTokenKind.TYPE_REF)
    private fun comment(s: String) = RenderToken(s, RenderTokenKind.COMMENT)
    private fun space() = RenderToken(" ", RenderTokenKind.WHITESPACE)
    private fun indent(level: Int) = RenderToken(" ".repeat(level * 2), RenderTokenKind.WHITESPACE)
    private fun linebreak() = RenderToken("\n", RenderTokenKind.LINE_BREAK)

    private fun formatBytes(size: Long): String = when {
        size < 1024 -> "${size}B"
        size < 1024 * 1024 -> "${size / 1024}KB"
        else -> "${size / (1024 * 1024)}MB"
    }

    public companion object {
        /** "0x" + 8 hex digits. */
        public const val GUTTER_WIDTH: Int = 10

        /** Gap (in monospace columns) between offset, hex, and content columns. */
        public const val GAP: Int = 2

        /** Total width of the hex-bytes column: 6 byte pairs + ellipsis + slack. */
        public const val BYTES_WIDTH: Int = 20

        /** Max bytes shown in the hex column before truncating with `…`. */
        public const val MAX_BYTES_DISPLAYED: Int = 6

        /** Max bytes shown in the long inline-hex preview (currently unused
         *  by the renderer — body rows use [MAX_BYTES_DISPLAYED]). Kept here
         *  in case a future inline-hex pane wants a wider column. */
        public const val INLINE_HEX_MAX_BYTES: Int = 32

        /** Bytes shown per row in a Data-section payload body (xxd-style). */
        public const val DATA_BYTES_PER_ROW: Int = 16

        /** Bytes per row in [DataViewMode.ASCII] / [DataViewMode.UTF8] modes. */
        public const val DATA_BYTES_PER_ROW_TEXT: Int = 64
    }
}
