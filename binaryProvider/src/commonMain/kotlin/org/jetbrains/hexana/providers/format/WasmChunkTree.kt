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
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
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
    val seenCustomNames = mutableSetOf<String>()
    for (section in module.sections) {
        val kind = sectionKindOf(section)
        val records = if (kind == WasmSectionKinds.CODE ||
            kind == WasmSectionKinds.FUNCTION ||
            kind == WasmSectionKinds.TYPE
        ) {
            // Skip getRecordRanges for code (use codeOffsets), function (single
            // chunk), and type (appendTypeChunks builds rec-group sub-headers).
            emptyList()
        } else {
            provider.getRecords(section)
        }

        val headerId: ChunkId = if (kind == WasmSectionKinds.CUSTOM) {
            // Custom-section names are not guaranteed unique (e.g. two "name" sections, or
            // tool-emitted duplicates). Disambiguate empty AND colliding names with a running
            // counter so the header id stays unique (LazyColumn key + idIndex contract).
            val rawName = (section as? WasmSectionCustom)?.name?.takeIf { it.isNotEmpty() }
                ?: "custom#${customCounter++}"
            val uniqueName = if (rawName in seenCustomNames) "$rawName#${customCounter++}" else rawName
            seenCustomNames += uniqueName
            ChunkId.Symbolic(WasmSectionKinds.CUSTOM, uniqueName)
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
            WasmSectionKinds.TYPE -> appendTypeChunks(chunks, provider, section as WasmSectionTypes)
            WasmSectionKinds.CUSTOM -> appendCustomBody(chunks, headerId, section)
            else -> appendGenericRecords(chunks, kind, records)
        }
    }

    return WasmChunkTree(chunks)
}

/**
 * Unique [ChunkId] for a within-section entry. Prefers [ChunkId.Symbolic] when [name] is present and
 * unique within the section (so name-stable fold/scroll state survives renumbering); falls back to
 * [ChunkId.Indexed] for unnamed entries and for names that repeat within the section.
 *
 * Kotlin/Wasm writes the same name (`<init>`, `<get-x>`, `$default`, overloads) at many function
 * indices, so a name-only Symbolic id is not unique: it collides as a LazyColumn `key` — crashing the
 * virtualized WAT view with "Key … was already used" — and silently collapses `idIndex` and the
 * folded-sections set (folding one entry would fold every same-named one). [index] must be unique
 * within the section (the funcidx for code/function chunks, the entry ordinal otherwise).
 */
internal fun uniqueChunkId(kind: SectionKind, index: Int, name: String?, nameCounts: Map<String, Int>): ChunkId =
    if (name != null && (nameCounts[name] ?: 0) == 1) {
        ChunkId.Symbolic(kind, name)
    } else {
        ChunkId.Indexed(kind, index)
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
    val names = ranges.indices.map { resolver.resolve(SymbolKind.FUNCTION, importFuncCount + it) }
    val nameCounts = names.asSequence().filterNotNull().groupingBy { it }.eachCount()
    ranges.forEachIndexed { i, r ->
        val funcIdx = importFuncCount + i
        val name = names[i]
        chunks += BinaryChunk.Element(
            // Distinct from the CODE chunk's id (SectionKind is part of Symbolic / Indexed equality)
            // so per-section nav targets stay unambiguous; uniqueChunkId guarantees within-section
            // uniqueness when names repeat (Kotlin/Wasm).
            id = uniqueChunkId(WasmSectionKinds.FUNCTION, funcIdx, name, nameCounts),
            byteRange = r.offset.toLong()..(r.offset + r.size - 1).toLong(),
            estimatedLines = 1,
            kind = WasmSectionKinds.FUNCTION,
            indexInSection = i,
            displayName = name,
        )
    }
}

/**
 * One chunk per type *index*. A WasmGC `(rec …)` group declares many types
 * under one top-level record; it gets a [WasmSectionKinds.TYPE_REC] header
 * chunk (a collapsible sub-group marker) followed by one [WasmSectionKinds.TYPE]
 * member chunk per entry, each tagged with the header's id as its `groupId` so
 * the panel can fold the whole group to a single line. Standalone (non-rec)
 * types emit one [WasmSectionKinds.TYPE] chunk with no group. `indexInSection`
 * is the global type index — it matches the flattened `typeRecords` list the
 * renderer indexes by, so a member renders as `(type $N …)`.
 */
private fun appendTypeChunks(
    chunks: MutableList<BinaryChunk>,
    provider: WasmBinaryProvider,
    section: WasmSectionTypes,
) {
    val records = with(section) { provider.bytes.parseTypes() }
    var typeIndex = 0
    var recOrdinal = 0
    for (record in records) {
        if (record is WasmRecursiveTypeRecord) {
            val recId = ChunkId.Indexed(WasmSectionKinds.TYPE_REC, recOrdinal)
            // Header range covers only the rec prefix (tag + count), ending
            // before the first member — keeps chunk byteRanges non-overlapping
            // so chunkContainingByte's binary search stays valid.
            val headerStart = record.offset.toLong()
            val headerLast = record.entries.firstOrNull()
                ?.let { (it.offset.toLong() - 1).coerceAtLeast(headerStart) }
                ?: (record.offset + record.size - 1).toLong()
            chunks += BinaryChunk.Element(
                id = recId,
                byteRange = headerStart..headerLast,
                estimatedLines = 1,
                kind = WasmSectionKinds.TYPE_REC,
                indexInSection = recOrdinal,
                displayName = record.entries.size.toString(),
            )
            recOrdinal++
            for (entry in record.entries) {
                chunks += BinaryChunk.Element(
                    id = ChunkId.Indexed(WasmSectionKinds.TYPE, typeIndex),
                    byteRange = entry.offset.toLong()..(entry.offset + entry.size - 1).toLong(),
                    estimatedLines = 1,
                    kind = WasmSectionKinds.TYPE,
                    indexInSection = typeIndex,
                    displayName = "type $typeIndex",
                    groupId = recId,
                )
                typeIndex++
            }
        } else {
            chunks += BinaryChunk.Element(
                id = ChunkId.Indexed(WasmSectionKinds.TYPE, typeIndex),
                byteRange = record.offset.toLong()..(record.offset + record.size - 1).toLong(),
                estimatedLines = 1,
                kind = WasmSectionKinds.TYPE,
                indexInSection = typeIndex,
                displayName = "type $typeIndex",
            )
            typeIndex++
        }
    }
}

/**
 * One Element per custom section, covering the section content (name + raw
 * payload). Rendered like a Data entry — `offset hex name` header plus a
 * foldable body with the u8/u16/u32/u64/ascii/utf8 encodings — by the
 * renderer's `buildCustomRows`. The id is keyed on [sectionOffset] (unique per
 * section, distinct from the section header's Symbolic id so LazyColumn keys
 * don't collide); the renderer addresses the body via byteRange, not index.
 */
private fun appendCustomBody(
    chunks: MutableList<BinaryChunk>,
    headerId: ChunkId,
    section: WasmSection,
) {
    if (section.contentSize <= 0) return
    val name = (headerId as? ChunkId.Symbolic)?.name
        ?: WasmSectionKinds.displayName(WasmSectionKinds.CUSTOM)
    chunks += BinaryChunk.Element(
        id = ChunkId.Indexed(WasmSectionKinds.CUSTOM, section.sectionOffset),
        byteRange = section.contentOffset.toLong()..(section.contentOffset + section.contentSize - 1).toLong(),
        estimatedLines = 1,
        kind = WasmSectionKinds.CUSTOM,
        indexInSection = 0,
        displayName = name,
    )
}

private fun appendCodeChunks(
    chunks: MutableList<BinaryChunk>,
    provider: WasmBinaryProvider,
    resolver: WasmSymbolResolver,
) {
    val importFuncCount = provider.importFuncCount
    val names = provider.codeOffsets.indices.map { resolver.resolve(SymbolKind.FUNCTION, importFuncCount + it) }
    val nameCounts = names.asSequence().filterNotNull().groupingBy { it }.eachCount()
    provider.codeOffsets.forEachIndexed { i, off ->
        val funcIdx = importFuncCount + i
        val name = names[i]
        chunks += BinaryChunk.Element(
            id = uniqueChunkId(WasmSectionKinds.CODE, funcIdx, name, nameCounts),
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
    val nameCounts = records.asSequence().map { it.name }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
    records.forEachIndexed { i, named ->
        val nameOrNull = named.name.takeIf { it.isNotEmpty() }
        chunks += BinaryChunk.Element(
            id = uniqueChunkId(kind, i, nameOrNull, nameCounts),
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
    WasmSectionKinds.TYPE -> (section as? WasmSectionTypes)?.let { s ->
        with(s) { provider.bytes.parseTypes() }
            .sumOf { if (it is WasmRecursiveTypeRecord) it.entries.size else 1 }
    } ?: 0
    WasmSectionKinds.CUSTOM -> if (section.contentSize > 0) 1 else 0
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
