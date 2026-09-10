package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.DWARF_GROUP_TAB_NAME
import org.jetbrains.hexana.shared.DwarfOutlineNode
import org.jetbrains.hexana.shared.DwarfOutlineSection
import org.jetbrains.hexana.shared.DwarfSearchIndex
import org.jetbrains.hexana.shared.dwarfOutlineSections
import org.jetbrains.hexana.shared.dwarfSearchIndex
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor

/**
 * The DWARF section tabs, mirroring the IntelliJ plugin's `DwarfPanels.kt`: `.debug_abbrev`,
 * `.debug_info` and `.debug_line` folded behind one **DWARF** tab, each an expandable tree with a
 * find-and-jump search bar.
 *
 * Rows, labels, node ids, byte ranges and the search index all come from `plugins-shared`'s
 * `DwarfOutline.kt`; this file only draws them.
 */
internal fun dwarfExtraTabs(provider: BinaryProvider): List<PanelTab> {
    val wasmProvider = provider as? WasmBinaryProvider ?: return emptyList()
    val module = wasmProvider.wasmModule as? BaseModule ?: return emptyList()
    // Cheap: probes for section presence only. The tab list is built during composition on the
    // webview's single thread, so nothing here may parse a section - that happens in the tab body,
    // which only composes once the tab is opened.
    val sections = dwarfOutlineSections(WasmDwarfAccessor(module), wasmProvider.bytes)
    return groupPanelTabs(
        DWARF_GROUP_TAB_NAME,
        sections.map { section ->
            PanelTab(section.name, padding = false) { selection -> DwarfOutlineTab(section, selection) }
        },
    )
}

/** Height of one outline row, in px. [VirtualizedList] needs it fixed and exact. */
private const val DWARF_ROW_HEIGHT_PX = 18

/** Indentation per tree level, matching the tree table's `depth * 16`. */
private const val DWARF_INDENT_PX = 16

private const val CHEVRON_RIGHT = "▶"
private const val CHEVRON_DOWN = "▼"

/** A row of the flattened outline: the node plus the depth it is drawn at. */
internal class DwarfRow(val node: DwarfOutlineNode, val depth: Int)

/**
 * Flattens "roots, plus the children of expanded nodes" into an addressable list.
 *
 * Children are pulled through [childrenCache] rather than [DwarfOutlineNode.children] directly:
 * the model's providers are pure and un-memoized, so without the cache every re-flatten would
 * re-parse - and for `.debug_line` that means re-decoding a line program on every keystroke.
 */
internal fun flattenDwarfRows(
    roots: List<DwarfOutlineNode>,
    expanded: Set<String>,
    childrenCache: MutableMap<String, List<DwarfOutlineNode>>,
): List<DwarfRow> = buildList {
    fun visit(node: DwarfOutlineNode, depth: Int) {
        add(DwarfRow(node, depth))
        if (!node.hasChildren || node.id !in expanded) return
        val children = childrenCache.getOrPut(node.id) { node.children() }
        children.forEach { visit(it, depth + 1) }
    }
    roots.forEach { visit(it, 0) }
}

/**
 * Holds the search index, built on first use and then reused.
 *
 * Not a `lazy {}`: the value has to be recreated per opened section, and the holder makes the
 * deferral explicit at the call site. Same shape as [LazySearchIndex] in `DexEditorContent.kt`.
 */
internal class LazyDwarfSearchIndex(private val build: () -> DwarfSearchIndex) {
    private var index: DwarfSearchIndex? = null

    fun get(): DwarfSearchIndex = index ?: build().also { index = it }
}

@Composable
private fun DwarfOutlineTab(section: DwarfOutlineSection, selection: HexSelectionState) {
    val roots = remember(section) { section.roots() }
    if (roots.isEmpty()) {
        Div({ style { property("padding", "8px") } }) { Text("No entries") }
        return
    }

    // Searching subnodes means labelling every row, including the ones inside collapsed subtrees,
    // which forces the model's lazy children for the whole tree. Deferred to the first search so
    // that merely opening `.debug_line` does not decode every line program.
    val searchIndex = remember(roots) { LazyDwarfSearchIndex { dwarfSearchIndex(roots) } }
    val childrenCache = remember(roots) { mutableMapOf<String, List<DwarfOutlineNode>>() }

    var expanded by remember(roots) { mutableStateOf(setOf<String>()) }
    var query by remember(roots) { mutableStateOf("") }
    var matches by remember(roots) { mutableStateOf(emptyList<String>()) }
    var currentMatch by remember(roots) { mutableStateOf(0) }
    var reveal by remember(roots) { mutableStateOf<ListReveal?>(null) }
    var revealRequests by remember(roots) { mutableStateOf(0) }

    // Remembered on the inputs it actually depends on. Recomputing per recomposition would rebuild
    // a row per visible node on every keystroke, and an expanded `.debug_line` unit is very wide.
    val rows = remember(roots, expanded) { flattenDwarfRows(roots, expanded, childrenCache) }

    fun goToMatch(target: Int) {
        if (matches.isEmpty()) return
        val index = target.mod(matches.size)
        currentMatch = index
        val id = matches[index]
        // Reveal the match: a hit inside a collapsed subtree is useless until its ancestors open.
        val opened = expanded + searchIndex.get().ancestorsOf(id) + id
        // Rows for the newly opened set: `rows` above is a frame behind, and reusing it would
        // scroll to a stale index. Unchanged expansion means the cached list is already right.
        val revealed = if (opened == expanded) rows else flattenDwarfRows(roots, opened, childrenCache)
        expanded = opened
        revealRequests += 1
        reveal = revealed.indexOfFirst { it.node.id == id }
            .takeIf { it >= 0 }
            ?.let { ListReveal(it, revealRequests) }
    }

    fun search(text: String) {
        query = text
        val needle = text.trim()
        matches = if (needle.isEmpty()) emptyList() else searchIndex.get().idsMatching(needle)
        currentMatch = 0
        if (matches.isNotEmpty()) goToMatch(0)
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        DwarfSearchBar(
            query = query,
            matchCount = matches.size,
            currentMatch = currentMatch,
            onQueryChange = ::search,
            onNavigate = { delta -> goToMatch(currentMatch + delta) },
        )

        VirtualizedList(
            itemCount = rows.size,
            itemHeightPx = DWARF_ROW_HEIGHT_PX,
            reveal = reveal,
        ) { index ->
            val row = rows.getOrNull(index) ?: return@VirtualizedList
            DwarfOutlineRow(
                row = row,
                expanded = row.node.id in expanded,
                isCurrentMatch = matches.getOrNull(currentMatch) == row.node.id,
                query = query.trim(),
                onToggle = {
                    expanded = if (row.node.id in expanded) expanded - row.node.id else expanded + row.node.id
                },
                onSelect = { row.node.offset?.let { selection.selectRange(it, row.node.size) } },
            )
        }
    }
}

@Composable
private fun DwarfOutlineRow(
    row: DwarfRow,
    expanded: Boolean,
    isCurrentMatch: Boolean,
    query: String,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("height", "${DWARF_ROW_HEIGHT_PX}px")
            property("line-height", "${DWARF_ROW_HEIGHT_PX}px")
            property("font-family", "var(--vscode-editor-font-family, monospace)")
            property("font-size", "12px")
            property("white-space", "nowrap")
            property("cursor", "pointer")
            property("padding-left", "${row.depth * DWARF_INDENT_PX}px")
            if (isCurrentMatch) {
                property("background", "var(--vscode-list-activeSelectionBackground, rgba(255,255,255,0.12))")
            }
        }
        onClick { onSelect() }
    }) {
        Span({
            style {
                property("width", "14px")
                property("flex", "0 0 14px")
                property("text-align", "center")
                property("opacity", if (row.node.hasChildren) "0.8" else "0")
            }
            if (row.node.hasChildren) {
                onClick { event ->
                    // Otherwise the row's own handler also fires and moves the hex view.
                    event.stopPropagation()
                    onToggle()
                }
            }
        }) {
            if (row.node.hasChildren) Text(if (expanded) CHEVRON_DOWN else CHEVRON_RIGHT)
        }
        DwarfHighlightedLabel(row.node.label, query)
    }
}

/** Draws [label] with every case-insensitive occurrence of [query] highlighted. */
@Composable
private fun DwarfHighlightedLabel(label: String, query: String) {
    if (query.isEmpty()) {
        Text(label)
        return
    }
    var cursor = 0
    var from = label.indexOf(query, ignoreCase = true)
    if (from < 0) {
        Text(label)
        return
    }
    while (from >= 0) {
        if (from > cursor) Text(label.substring(cursor, from))
        Span({
            style {
                property("background", "var(--vscode-editor-findMatchHighlightBackground, rgba(234,92,0,0.33))")
            }
        }) {
            Text(label.substring(from, from + query.length))
        }
        cursor = from + query.length
        from = label.indexOf(query, cursor, ignoreCase = true)
    }
    if (cursor < label.length) Text(label.substring(cursor))
}

@Composable
private fun DwarfSearchBar(
    query: String,
    matchCount: Int,
    currentMatch: Int,
    onQueryChange: (String) -> Unit,
    onNavigate: (Int) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "4px")
            property("padding", "4px 8px")
            property("flex", "0 0 auto")
        }
    }) {
        Input(InputType.Text) {
            value(query)
            placeholder("Search...")
            style {
                property("flex", "1")
                property("min-width", "0")
                property("background", "var(--vscode-input-background)")
                property("color", "var(--vscode-input-foreground)")
                property("border", "1px solid var(--vscode-input-border, transparent)")
                property("padding", "2px 6px")
                property("font-size", "12px")
            }
            onInput { onQueryChange(it.value) }
            onKeyDown { event ->
                when {
                    event.key == "Enter" -> onNavigate(if (event.shiftKey) -1 else 1)
                    event.key == "Escape" && query.isNotEmpty() -> onQueryChange("")
                }
            }
        }

        if (query.isNotBlank()) {
            Span({
                style {
                    property("font-size", "11px")
                    property("opacity", "0.7")
                    property("white-space", "nowrap")
                }
            }) {
                Text(if (matchCount == 0) "No matches" else "${currentMatch + 1} of $matchCount")
            }
        }

        DwarfNavButton("↑", "Previous match", enabled = matchCount > 0) { onNavigate(-1) }
        DwarfNavButton("↓", "Next match", enabled = matchCount > 0) { onNavigate(1) }
    }
}

@Composable
private fun DwarfNavButton(glyph: String, title: String, enabled: Boolean, onClick: () -> Unit) {
    Span({
        attr("title", title)
        style {
            property("padding", "0 4px")
            property("font-size", "12px")
            property("user-select", "none")
            property("cursor", if (enabled) "pointer" else "default")
            property("opacity", if (enabled) "0.8" else "0.3")
        }
        if (enabled) onClick { onClick() }
    }) {
        Text(glyph)
    }
}
