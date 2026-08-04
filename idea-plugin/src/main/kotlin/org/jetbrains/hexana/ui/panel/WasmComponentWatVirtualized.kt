package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.componentDefTypeWat
import org.jetbrains.hexana.shared.componentAliasDefWat
import org.jetbrains.hexana.shared.componentExternDescriptionWat
import org.jetbrains.hexana.shared.componentSortIndexWat
import org.jetbrains.hexana.shared.coreTypeWat
import org.jetbrains.hexana.shared.instanceCoreExpressionWat
import org.jetbrains.hexana.shared.instanceExpressionWat
import org.jetbrains.hexana.wasm.components.WasmAliasSection
import org.jetbrains.hexana.wasm.components.WasmCanonSection
import org.jetbrains.hexana.wasm.components.WasmComponentCoreTypeSection
import org.jetbrains.hexana.wasm.components.WasmComponentExportSection
import org.jetbrains.hexana.wasm.components.WasmComponentImportSection
import org.jetbrains.hexana.wasm.components.WasmComponentModuleSection
import org.jetbrains.hexana.wasm.components.WasmComponentSection
import org.jetbrains.hexana.wasm.components.WasmComponentStartSection
import org.jetbrains.hexana.wasm.components.WasmComponentTypesSection
import org.jetbrains.hexana.wasm.components.WasmCoreInstanceSection
import org.jetbrains.hexana.wasm.components.WasmInstanceSection
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom
import org.jetbrains.jewel.ui.component.CircularProgressIndicator

// ---------------------------------------------------------------------------
// Virtualized WAT for the component LEVEL of a component-model binary: one
// chunk per decoded record (import, export, type, alias, instance, embedded
// module/component), grouped under per-section sticky headers, rendered by
// the shared [VirtualizedChunkPanel] (search, fold, offset click-to-hex).
//
// Renders from the provider's byte buffer only — no file access — so it works
// for nested in-memory views. Sections the parser cannot decode per-record
// (canon, start, value) degrade to a single not-decoded row instead of
// failing the whole view; so does any section whose record scan throws.
// ---------------------------------------------------------------------------

internal object ComponentSectionKinds {
    fun displayName(kind: SectionKind): String = when (kind.id.substringBefore('#')) {
        "component.header" -> "Header"
        "component.module" -> "Core Module"
        "component.core_instance" -> "Core Instance"
        "component.core_type" -> "Core Type"
        "component.component" -> "Component"
        "component.instance" -> "Instance"
        "component.alias" -> "Alias"
        "component.type" -> "Types"
        "component.canon" -> "Canonical"
        "component.start" -> "Start"
        "component.import" -> "Import"
        "component.export" -> "Export"
        "component.custom" -> "Custom"
        else -> "Section"
    }
}

/** Chunks plus the pre-rendered single token line for each element chunk. */
internal class ComponentWatChunkData(
    val chunkTree: ChunkTree,
    val linesByChunk: Map<ChunkId, List<RenderToken>>,
)

private class ListChunkTree(private val chunks: List<BinaryChunk>) : ChunkTree {
    private val idIndex = HashMap<ChunkId, Int>(chunks.size).apply {
        chunks.forEachIndexed { i, c -> put(c.id, i) }
    }
    override val totalChunks: Int get() = chunks.size
    override fun chunkAt(index: Int): BinaryChunk = chunks[index]
    override fun chunkContainingByte(offset: Long): Int? =
        chunks.indexOfFirst { offset in it.byteRange }.takeIf { it >= 0 }
    override fun chunkForId(id: ChunkId): Int? = idIndex[id]
}

private const val OFFSET_GUTTER = 10 // "0x" + 8 hex digits

/** One rendered line: clickable offset gutter (or padding), then lightly lexed WAT text. */
private fun tokenLine(offset: Long?, wat: String): List<RenderToken> = buildList {
    if (offset != null) {
        val text = "0x" + offset.toString(16).uppercase().padStart(8, '0')
        add(RenderToken(text, RenderTokenKind.OFFSET_ANNOTATION, sourceOffset = offset))
    } else {
        add(RenderToken(" ".repeat(OFFSET_GUTTER), RenderTokenKind.WHITESPACE))
    }
    add(RenderToken("  ", RenderTokenKind.WHITESPACE))
    addAll(watLineTokens(wat))
}

/**
 * Light single-line WAT lexer for highlighting: strings, `(; ;)` comments,
 * `$`-identifiers, numbers, parens, and keywords. Not a grammar — display only.
 */
internal fun watLineTokens(line: String): List<RenderToken> = buildList {
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        when {
            c == '(' && i + 1 < n && line[i + 1] == ';' -> {
                val end = line.indexOf(";)", i + 2).let { if (it < 0) n else it + 2 }
                add(RenderToken(line.substring(i, end), RenderTokenKind.COMMENT))
                i = end
            }
            c == '(' || c == ')' -> {
                add(RenderToken(c.toString(), RenderTokenKind.PUNCTUATION))
                i++
            }
            c == '"' -> {
                var end = i + 1
                while (end < n && line[end] != '"') end++
                end = (end + 1).coerceAtMost(n)
                add(RenderToken(line.substring(i, end), RenderTokenKind.STRING))
                i = end
            }
            c == ' ' -> {
                var end = i
                while (end < n && line[end] == ' ') end++
                add(RenderToken(line.substring(i, end), RenderTokenKind.WHITESPACE))
                i = end
            }
            else -> {
                var end = i
                while (end < n && line[end] !in " ()\"") end++
                val word = line.substring(i, end)
                val kind = when {
                    word.startsWith("$") -> RenderTokenKind.IDENTIFIER
                    word.first().isDigit() -> RenderTokenKind.NUMBER
                    else -> RenderTokenKind.KEYWORD
                }
                add(RenderToken(word, kind))
                i = end
            }
        }
    }
}

internal fun buildComponentWatChunks(provider: WasmComponentBinaryProvider): ComponentWatChunkData {
    val component = provider.wasmModule
        ?: return ComponentWatChunkData(ListChunkTree(emptyList()), emptyMap())
    val chunks = mutableListOf<BinaryChunk>()
    val lines = HashMap<ChunkId, List<RenderToken>>()
    // Repeated section ids get a #N suffix so every ChunkId stays unique.
    val kindCounts = HashMap<String, Int>()

    fun sectionKind(base: String): SectionKind {
        val seen = kindCounts.getOrElse(base) { 0 }
        kindCounts[base] = seen + 1
        return SectionKind(if (seen == 0) base else "$base#$seen")
    }

    fun sectionRange(section: WasmSection): LongRange =
        section.sectionOffset.toLong()..(section.sectionOffset.toLong() + section.sectionSize - 1)

    fun addElement(kind: SectionKind, index: Int, range: LongRange, name: String?, offset: Long?, wat: String) {
        val id = ChunkId.Indexed(kind, index)
        chunks += BinaryChunk.Element(
            id = id, byteRange = range, estimatedLines = 1, kind = kind,
            indexInSection = index, displayName = name,
        )
        lines[id] = tokenLine(offset, wat)
    }

    fun addHeader(kind: SectionKind, section: WasmSection, elementCount: Int) {
        chunks += BinaryChunk.SectionHeader(
            id = ChunkId.Header(kind), byteRange = sectionRange(section), estimatedLines = 1,
            kind = kind, elementCount = elementCount, totalSize = section.sectionSize.toLong(),
        )
    }

    /** One section whose records we can enumerate: header + one element per record. */
    fun <T> recordSection(
        section: WasmSection,
        base: String,
        scan: () -> List<T>,
        elementName: (Int, T) -> String?,
        elementOffset: (T) -> Long?,
        elementRange: (T) -> LongRange?,
        wat: (T) -> String,
    ) {
        val records = runCatching(scan).getOrNull()
        val kind = sectionKind(base)
        if (records == null) {
            // The parser has TODO() holes for rare encodings — degrade to a
            // single raw row instead of losing the whole view.
            addHeader(kind, section, 1)
            addElement(
                kind, 0, sectionRange(section), null, section.contentOffset.toLong(),
                "(; ${section.name} section: ${section.contentSize} bytes, decode failed ;)",
            )
            return
        }
        addHeader(kind, section, records.size)
        records.forEachIndexed { i, record ->
            addElement(
                kind, i,
                elementRange(record) ?: (section.contentOffset.toLong()..(section.contentOffset.toLong() + section.contentSize - 1)),
                elementName(i, record), elementOffset(record), wat(record),
            )
        }
    }

    fun rawSection(section: WasmSection, base: String, note: String) {
        val kind = sectionKind(base)
        addHeader(kind, section, 1)
        addElement(kind, 0, sectionRange(section), null, section.contentOffset.toLong(), note)
    }

    // Preamble: \0asm, version 0x0d, layer 1.
    val headerKind = sectionKind("component.header")
    addElement(headerKind, 0, 0L..7L, "component", 0L, "(component (; \\0asm version 0xd, layer ${component.layer} ;)")

    for (section in component.sections) {
        when (section) {
            is WasmComponentImportSection -> recordSection(
                section, "component.import",
                scan = { with(section) { provider.bytes.scan() } },
                elementName = { _, r -> r.importName },
                elementOffset = { it.offset.toLong() },
                elementRange = { it.offset.toLong()..(it.offset.toLong() + it.size - 1) },
                wat = { "(import \"${it.importName}${it.version.orEmpty()}\" ${componentExternDescriptionWat(it.externDescription)})" },
            )
            is WasmComponentExportSection -> recordSection(
                section, "component.export",
                scan = { with(section) { provider.bytes.scan() } },
                elementName = { _, r -> r.exportName },
                elementOffset = { it.offset.toLong() },
                elementRange = { it.offset.toLong()..(it.offset.toLong() + it.size - 1) },
                wat = { record ->
                    buildString {
                        append("(export \"${record.exportName}${record.version.orEmpty()}\" ")
                        append(componentSortIndexWat(record.sortIndex))
                        record.externDescription?.let { append(" ").append(componentExternDescriptionWat(it)) }
                        append(")")
                    }
                },
            )
            is WasmComponentTypesSection -> recordSection(
                section, "component.type",
                scan = { with(section) { provider.bytes.scanTypes() } },
                elementName = { i, _ -> "type $i" },
                elementOffset = { null },
                elementRange = { null },
                wat = { "(type ${componentDefTypeWat(it)})" },
            )
            is WasmComponentCoreTypeSection -> recordSection(
                section, "component.core_type",
                scan = { with(section) { provider.bytes.scanTypes() } },
                elementName = { i, _ -> "core type $i" },
                elementOffset = { null },
                elementRange = { null },
                wat = { coreTypeWat(it) },
            )
            is WasmAliasSection -> recordSection(
                section, "component.alias",
                scan = { with(section) { provider.bytes.scanAliases() } },
                elementName = { i, _ -> "alias $i" },
                elementOffset = { null },
                elementRange = { null },
                wat = { componentAliasDefWat(it) },
            )
            is WasmInstanceSection -> recordSection(
                section, "component.instance",
                scan = { with(section) { provider.bytes.parseInstances() } },
                elementName = { i, _ -> "instance $i" },
                elementOffset = { null },
                elementRange = { null },
                wat = { instanceExpressionWat(it) },
            )
            is WasmCoreInstanceSection -> recordSection(
                section, "component.core_instance",
                scan = { with(section) { provider.bytes.parseCoreInstances() } },
                elementName = { i, _ -> "core instance $i" },
                elementOffset = { null },
                elementRange = { null },
                wat = { instanceCoreExpressionWat(it) },
            )
            is WasmComponentModuleSection -> {
                val kind = sectionKind("component.module")
                addHeader(kind, section, 1)
                addElement(
                    kind, 0, sectionRange(section), "core module",
                    section.contentOffset.toLong(),
                    "(core module (; ${section.contentSize} bytes, open via the Modules tab ;))",
                )
            }
            is WasmComponentSection -> {
                val kind = sectionKind("component.component")
                addHeader(kind, section, 1)
                addElement(
                    kind, 0, sectionRange(section), "nested component",
                    section.contentOffset.toLong(),
                    "(component (; ${section.contentSize} bytes, open via the Components tab ;))",
                )
            }
            is WasmSectionCustom -> {
                val kind = sectionKind("component.custom")
                addHeader(kind, section, 1)
                addElement(
                    kind, 0, sectionRange(section), section.name,
                    section.contentOffset.toLong(), "(@custom \"${section.name}\")",
                )
            }
            is WasmCanonSection -> rawSection(
                section, "component.canon",
                "(; canon section: ${section.contentSize} bytes, not decoded ;)",
            )
            is WasmComponentStartSection -> rawSection(
                section, "component.start",
                "(; start section: ${section.contentSize} bytes, not decoded ;)",
            )
            else -> rawSection(
                section, "component.section",
                "(; ${section.name} section: ${section.contentSize} bytes, not decoded ;)",
            )
        }
    }

    return ComponentWatChunkData(ListChunkTree(chunks), lines)
}

private fun componentWatSearchResults(tree: ChunkTree, query: String): List<ChunkSearchResult> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val results = ArrayList<ChunkSearchResult>()
    for (i in 0 until tree.totalChunks) {
        if (results.size >= 30) break
        val chunk = tree.chunkAt(i) as? BinaryChunk.Element ?: continue
        val name = chunk.displayName ?: continue
        if (q in name.lowercase()) {
            results += SimpleChunkSearchResult(
                label = name,
                detail = "${ComponentSectionKinds.displayName(chunk.kind)} @ 0x${chunk.byteRange.first.toString(16).uppercase()}",
                chunkId = chunk.id,
            )
        }
    }
    return results
}

/**
 * Component-level virtualized WAT: used when a component embeds no core
 * modules (glue-only components); the embedded-module case renders the core
 * [WasmPanelWatVirtualized] per module instead.
 */
@Composable
internal fun WasmComponentPanelWatVirtualizedGlue(
    provider: WasmComponentBinaryProvider,
    state: HexViewStateHolder,
) {
    val dataState = produceState<ComponentWatChunkData?>(initialValue = null, provider) {
        value = withContext(Dispatchers.IO) { buildComponentWatChunks(provider) }
    }
    val data = dataState.value
    if (data == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    val config = remember(data) {
        VirtualizedChunkPanelConfig(
            sectionDisplayName = ComponentSectionKinds::displayName,
            searchPlaceholder = "Search by name or offset",
            computeSearchResults = { query -> componentWatSearchResults(data.chunkTree, query) },
            onOffsetClicked = { offset -> state.scrollHexViewTo(offset, setCursor = true) },
        )
    }
    VirtualizedChunkPanel(
        chunkTree = data.chunkTree,
        config = config,
        elementContent = { chunk, _ ->
            item(key = chunk.id) {
                SelectableTokenRow(
                    tokens = data.linesByChunk[chunk.id] ?: emptyList(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                )
            }
        },
    )
}