package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.document.format.SymbolKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom
import org.jetbrains.hexana.wasm.sections.WasmSectionData
import org.jetbrains.hexana.wasm.sections.WasmSectionDataCount
import org.jetbrains.hexana.wasm.sections.WasmSectionElement
import org.jetbrains.hexana.wasm.sections.WasmSectionException
import org.jetbrains.hexana.wasm.sections.WasmSectionExport
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctions
import org.jetbrains.hexana.wasm.sections.WasmSectionGlobal
import org.jetbrains.hexana.wasm.sections.WasmSectionImport
import org.jetbrains.hexana.wasm.sections.WasmSectionMemory
import org.jetbrains.hexana.wasm.sections.WasmSectionStart
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved

public class WasmChunkTree internal constructor(
    private val chunks: List<BinaryChunk>,
) : ChunkTree {
    override val totalChunks: Int get() = chunks.size

    override fun chunkAt(index: Int): BinaryChunk = chunks[index]

    override fun chunkContainingByte(offset: Long): Int? {
        val idx = chunks.binarySearch { chunk ->
            when {
                chunk.byteRange.last < offset -> -1
                chunk.byteRange.first > offset -> 1
                else -> 0
            }
        }
        return if (idx >= 0) idx else null
    }

    private val idIndex: Map<ChunkId, Int> by lazy {
        chunks.withIndex().associate { (i, c) -> c.id to i }
    }

    override fun chunkForId(id: ChunkId): Int? = idIndex[id]
}

/**
 * Builds the [ChunkTree] for a parsed WASM binary.
 *
 * Order: module header (8 bytes), then sections in their binary order
 * (preserves on-disk position of custom sections relative to typed sections).
 *
 * Per [Decision 5][PR 1]: the Function section emits a single header chunk
 * with no per-entry breakout — it is index-only metadata, rarely scrolled.
 *
 * For the Code section, [WasmBinaryProvider.codeOffsets] is used directly to
 * avoid eagerly parsing all 14k+ function bodies of Skiko-class binaries.
 */
public fun buildWasmChunkTree(parsed: WasmParsedBinary): WasmChunkTree {
    val provider = parsed.provider
    val module = provider.wasmModule ?: return WasmChunkTree(emptyList())
    val resolver = parsed.symbolResolver
    val chunks = mutableListOf<BinaryChunk>()

    // Module header: 4 bytes magic + 4 bytes version. Not a section in the spec
    // but rendered as one for navigation.
    chunks += BinaryChunk.SectionHeader(
        id = ChunkId.Header(WasmSectionKinds.HEADER),
        byteRange = 0L..7L,
        estimatedLines = 2,
        kind = WasmSectionKinds.HEADER,
        elementCount = 0,
        totalSize = 8L,
    )

    var customCounter = 0
    for (section in module.sections) {
        val kind = sectionKindOf(section)
        val records = if (kind == WasmSectionKinds.CODE || kind == WasmSectionKinds.FUNCTION) {
            // Skip getRecordRanges for code (use codeOffsets) and function (single chunk).
            emptyList()
        } else {
            provider.getRecords(section)
        }

        val headerId: ChunkId = if (kind == WasmSectionKinds.CUSTOM) {
            // Multiple custom sections coexist — disambiguate by name (fall back to running counter).
            val customName = (section as? WasmSectionCustom)?.name?.takeIf { it.isNotEmpty() }
                ?: "custom#${customCounter++}"
            ChunkId.Symbolic(WasmSectionKinds.CUSTOM, customName)
        } else {
            ChunkId.Header(kind)
        }

        chunks += BinaryChunk.SectionHeader(
            id = headerId,
            byteRange = sectionByteRange(section),
            estimatedLines = 1,
            kind = kind,
            elementCount = elementCountFor(provider, section, kind, records),
            totalSize = section.sectionSize.toLong(),
        )

        when (kind) {
            WasmSectionKinds.FUNCTION ->
                appendFunctionChunks(chunks, provider, resolver, section as WasmSectionFunctions)
            WasmSectionKinds.CODE -> appendCodeChunks(chunks, provider, resolver)
            else -> appendGenericRecords(chunks, kind, records)
        }
    }

    return WasmChunkTree(chunks)
}

/**
 * One chunk per Function-section entry. Each entry is a single ULEB128
 * `typeidx`; the chunk's byteRange spans those bytes so the renderer can
 * emit a per-entry row (offset gutter + hex column + `(; func[i] : type[N]
 * → $name ;)` description). Symbolic ChunkId uses the resolved function
 * name (same as the Code section) so cross-section navigation can pair
 * them up by id space.
 */
private fun appendFunctionChunks(
    chunks: MutableList<BinaryChunk>,
    provider: WasmBinaryProvider,
    resolver: WasmSymbolResolver,
    section: WasmSectionFunctions,
) {
    val importFuncCount = provider.importFuncCount
    val ranges = with(section) { provider.bytes.scanRanges() }
    ranges.forEachIndexed { i, r ->
        val funcIdx = importFuncCount + i
        val name = resolver.resolve(SymbolKind.FUNCTION, funcIdx)
        chunks += BinaryChunk.Element(
            // Distinct from the CODE chunk's id (SectionKind is part of
            // Symbolic / Indexed equality) so per-section nav targets stay
            // unambiguous.
            id = name
                ?.let { ChunkId.Symbolic(WasmSectionKinds.FUNCTION, it) }
                ?: ChunkId.Indexed(WasmSectionKinds.FUNCTION, funcIdx),
            byteRange = r.offset.toLong()..(r.offset + r.size - 1).toLong(),
            estimatedLines = 1,
            kind = WasmSectionKinds.FUNCTION,
            indexInSection = i,
            displayName = name,
        )
    }
}

private fun appendCodeChunks(
    chunks: MutableList<BinaryChunk>,
    provider: WasmBinaryProvider,
    resolver: WasmSymbolResolver,
) {
    val importFuncCount = provider.importFuncCount
    provider.codeOffsets.forEachIndexed { i, off ->
        val funcIdx = importFuncCount + i
        val name = resolver.resolve(SymbolKind.FUNCTION, funcIdx)
        chunks += BinaryChunk.Element(
            id = name
                ?.let { ChunkId.Symbolic(WasmSectionKinds.CODE, it) }
                ?: ChunkId.Indexed(WasmSectionKinds.CODE, funcIdx),
            byteRange = off.offset.toLong()..(off.offset + off.size - 1).toLong(),
            estimatedLines = estimateFunctionLines(off.size),
            kind = WasmSectionKinds.CODE,
            indexInSection = i,
            displayName = name,
        )
    }
}

private fun appendGenericRecords(
    chunks: MutableList<BinaryChunk>,
    kind: SectionKind,
    records: List<org.jetbrains.hexana.wasm.WasmNamedRange>,
) {
    records.forEachIndexed { i, named ->
        val nameOrNull = named.name.takeIf { it.isNotEmpty() }
        chunks += BinaryChunk.Element(
            id = nameOrNull
                ?.let { ChunkId.Symbolic(kind, it) }
                ?: ChunkId.Indexed(kind, i),
            byteRange = named.range.offset.toLong()..(named.range.offset + named.range.size - 1).toLong(),
            estimatedLines = 1,
            kind = kind,
            indexInSection = i,
            displayName = nameOrNull,
        )
    }
}

private fun elementCountFor(
    provider: WasmBinaryProvider,
    section: WasmSection,
    kind: SectionKind,
    records: List<org.jetbrains.hexana.wasm.WasmNamedRange>,
): Int = when (kind) {
    WasmSectionKinds.CODE -> provider.codeOffsets.size
    WasmSectionKinds.FUNCTION -> (section as? WasmSectionFunctions)?.let { provider.functions.size } ?: 0
    else -> records.size
}

private fun sectionByteRange(section: WasmSection): LongRange =
    section.sectionOffset.toLong()..(section.sectionOffset + section.sectionSize - 1).toLong()

private fun estimateFunctionLines(byteSize: Int): Int {
    // Heuristic: ~3 bytes/instruction + 4 lines of header (signature, locals, brace).
    return maxOf(3, byteSize / 3 + 4)
}

private fun sectionKindOf(section: WasmSection): SectionKind = when (section) {
    is WasmSectionTypes -> WasmSectionKinds.TYPE
    is WasmSectionImport -> WasmSectionKinds.IMPORT
    is WasmSectionFunctions -> WasmSectionKinds.FUNCTION
    is WasmSectionTable -> WasmSectionKinds.TABLE
    is WasmSectionMemory -> WasmSectionKinds.MEMORY
    is WasmSectionGlobal -> WasmSectionKinds.GLOBAL
    is WasmSectionExport -> WasmSectionKinds.EXPORT
    is WasmSectionStart -> WasmSectionKinds.START
    is WasmSectionElement -> WasmSectionKinds.ELEMENT
    is WasmSectionDataCount -> WasmSectionKinds.DATA_COUNT
    is WasmSectionCode -> WasmSectionKinds.CODE
    is WasmSectionData -> WasmSectionKinds.DATA
    is WasmSectionCustom -> WasmSectionKinds.CUSTOM
    is WasmSectionException -> WasmSectionKinds.EXCEPTION
    is WasmSectionUnresolved -> WasmSectionKinds.UNKNOWN
    else -> WasmSectionKinds.UNKNOWN
}
