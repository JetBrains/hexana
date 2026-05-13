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

    private val typeRecords: List<WasmTypeRecord> by lazy {
        provider.wasmModule?.typesSection?.let { section ->
            with(section) { provider.bytes.parseTypes() }
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
            else -> renderInlineHexElement(chunk, source, tokens)
        }
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
            out += comment("(; sub ${record.superTypes.joinToString(",")}${if (record.isFinal) " final" else ""} ;)")
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
        is WasmIndexHeapType -> heap.type.toString()
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
        tokens += linebreak()
    }

    private fun splitMnemonic(op: OpCode): Pair<String, String> {
        val full = watFor(op)
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

        /** Total width of the hex-bytes column: ~6 byte pairs + slack. */
        public const val BYTES_WIDTH: Int = 18

        /** Max bytes shown in the hex column before truncating with `…`. */
        public const val MAX_BYTES_DISPLAYED: Int = 6

        /** Max bytes shown in the long inline-hex preview (currently unused
         *  by the renderer — body rows use [MAX_BYTES_DISPLAYED]). Kept here
         *  in case a future inline-hex pane wants a wider column. */
        public const val INLINE_HEX_MAX_BYTES: Int = 32
    }
}

/** Inlined to avoid pulling `plugins-shared` into `binaryProvider` for one helper. */
private fun wasmValueTypeName(type: WasmValueType<*>): String = when (type) {
    is WasmI32Type -> "i32"
    is WasmI64Type -> "i64"
    is WasmF32Type -> "f32"
    is WasmF64Type -> "f64"
    is WasmVecType -> "v128"
    else -> type.toString()
}
