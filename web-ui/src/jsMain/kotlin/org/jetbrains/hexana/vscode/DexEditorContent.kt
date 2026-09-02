package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.DexSmaliClass
import org.jetbrains.hexana.shared.DexSmaliRow
import org.jetbrains.hexana.shared.DexSmaliSearchEntry
import org.jetbrains.hexana.shared.dexSmaliClassRows
import org.jetbrains.hexana.shared.dexSmaliClasses
import org.jetbrains.hexana.shared.dexSmaliSearchIndex

/**
 * Builds the extra [PanelTab] entries for a parsed Android `.dex` file — the
 * **Smali** tab, mirroring the IntelliJ plugin's `DexSmaliPanel`. The Header and
 * Classes tables come from the shared `DexTableTabsProvider`.
 *
 * Rows, register naming, reference resolution and payload decoding all come from
 * `plugins-shared`'s `DexSmaliText.kt`; this file only draws them.
 */
internal fun dexSmaliExtraTabs(dex: DexFile, bytes: CommonByteBuffer): List<PanelTab> = listOf(
    PanelTab(DEX_SMALI_TAB_NAME, padding = false) { selection -> DexSmaliTab(dex, bytes, selection) },
)

/** Height of one smali line, in px. [VirtualizedList] needs it fixed and exact. */
private const val SMALI_ROW_HEIGHT_PX = 18

/** One line of the flattened listing. */
internal sealed interface DexSmaliItem {
    /** A class's foldable header line. */
    data class Header(val cls: DexSmaliClass, val expanded: Boolean) : DexSmaliItem

    /** One rendered line of an expanded class. */
    data class Row(val classIndex: Int, val row: DexSmaliRow) : DexSmaliItem
}

/**
 * Flattens "class headers, plus the rows of the expanded ones" into a single
 * addressable list for [VirtualizedList].
 *
 * Deliberately an index rather than a materialized `List<DexSmaliItem>`: a
 * medium DEX renders to a few hundred thousand rows, and rebuilding a wrapper
 * object per row on every fold toggle would be the most expensive thing the
 * webview's single thread does. Here a toggle only rebuilds one `IntArray`.
 */
internal class DexSmaliItemIndex(
    private val classes: List<DexSmaliClass>,
    /** Rows of the expanded classes, keyed by [DexSmaliClass.index]. */
    private val rowsByClass: Map<Int, List<DexSmaliRow>>,
) {
    /** Item index of each class's header line. */
    private val headerAt = IntArray(classes.size)

    val size: Int

    init {
        var cursor = 0
        for ((i, cls) in classes.withIndex()) {
            headerAt[i] = cursor
            cursor += 1 + (rowsByClass[cls.index]?.size ?: 0)
        }
        size = cursor
    }

    /** The line at [itemIndex], or `null` when out of range. */
    fun at(itemIndex: Int): DexSmaliItem? {
        if (itemIndex < 0 || itemIndex >= size) return null
        val slot = lastHeaderAtOrBefore(itemIndex)
        val cls = classes[slot]
        val offsetInClass = itemIndex - headerAt[slot]
        if (offsetInClass == 0) {
            return DexSmaliItem.Header(cls, expanded = rowsByClass.containsKey(cls.index))
        }
        val row = rowsByClass[cls.index]?.getOrNull(offsetInClass - 1) ?: return null
        return DexSmaliItem.Row(cls.index, row)
    }

    private fun lastHeaderAtOrBefore(itemIndex: Int): Int {
        var low = 0
        var high = headerAt.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (headerAt[mid] <= itemIndex) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

/** Classes whose descriptor, or one of whose indexed methods, matches [query]. */
internal fun filterDexSmaliClasses(
    classes: List<DexSmaliClass>,
    searchIndex: List<DexSmaliSearchEntry>,
    query: String,
): List<DexSmaliClass> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return classes
    val matched = HashSet<Int>()
    for (entry in searchIndex) {
        if (needle in entry.label.lowercase()) matched += entry.classIndex
    }
    return classes.filter { it.index in matched }
}

@Composable
private fun DexSmaliTab(dex: DexFile, bytes: CommonByteBuffer, selection: HexSelectionState) {
    val classes = remember(dex) { dexSmaliClasses(dex) }
    val searchIndex = remember(dex) { dexSmaliSearchIndex(dex, bytes, classes) }

    var searchQuery by remember(dex) { mutableStateOf("") }
    var debouncedQuery by remember(dex) { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val visibleClasses = remember(classes, searchIndex, debouncedQuery) {
        filterDexSmaliClasses(classes, searchIndex, debouncedQuery)
    }

    // Collapsed initially, so opening the tab reads no class_data_item and no
    // code_item — same behaviour as the IntelliJ panel.
    var expanded by remember(dex) { mutableStateOf(emptySet<Int>()) }

    // Rendered rows, kept across fold toggles. A plain map, not snapshot state:
    // it is only read while building the index below, which recomputes whenever
    // `expanded` changes.
    val rowCache = remember(dex) { HashMap<Int, List<DexSmaliRow>>() }

    val itemIndex = remember(visibleClasses, expanded) {
        val rows = HashMap<Int, List<DexSmaliRow>>(expanded.size)
        for (cls in visibleClasses) {
            if (cls.index !in expanded) continue
            rows[cls.index] = rowCache.getOrPut(cls.index) { dexSmaliClassRows(dex, bytes, cls) }
        }
        DexSmaliItemIndex(visibleClasses, rows)
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        Input(org.jetbrains.compose.web.attributes.InputType.Text) {
            classes("hexana-search-input")
            value(searchQuery)
            attr("placeholder", "Search classes and methods…")
            onInput { searchQuery = it.value }
            style {
                property("margin", "4px 12px")
                property("padding", "4px 8px")
                property("font-size", "12px")
                property("background", "var(--vscode-input-background, #3c3c3c)")
                property("color", "var(--hexana-fg)")
                property("border", "1px solid var(--hexana-border, #333)")
                property("border-radius", "3px")
                property("outline", "none")
                property("flex", "none")
            }
        }

        if (itemIndex.size == 0) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                    property("padding", "4px 12px")
                }
            }) {
                Text(
                    if (searchQuery.isNotEmpty()) "No classes match the search."
                    else "(no classes)",
                )
            }
            return@Div
        }

        VirtualizedList(itemCount = itemIndex.size, itemHeightPx = SMALI_ROW_HEIGHT_PX) { index ->
            when (val item = itemIndex.at(index)) {
                is DexSmaliItem.Header -> DexClassHeaderRow(item) {
                    expanded = if (item.cls.index in expanded) {
                        expanded - item.cls.index
                    } else {
                        expanded + item.cls.index
                    }
                }
                is DexSmaliItem.Row -> DexSmaliRowView(item.row, selection)
                null -> Unit
            }
        }
    }
}

@Composable
private fun DexClassHeaderRow(item: DexSmaliItem.Header, onToggle: () -> Unit) {
    Div({
        style {
            rowBase()
            property("font-weight", "600")
            property("background", "var(--hexana-tab-bg, rgba(255,255,255,0.05))")
            property("cursor", "pointer")
            property("user-select", "none")
            property("padding", "0 12px")
            display(DisplayStyle.Flex)
            property("gap", "8px")
        }
        onClick { onToggle() }
    }) {
        Span({ style { property("color", cssColorFor(RenderTokenKind.OFFSET_ANNOTATION)) } }) {
            Text(formatDexOffset(item.cls.byteRange.first))
        }
        Span({ style { property("color", "var(--vscode-editorLineNumber-foreground, #858585)") } }) {
            Text(if (item.expanded) "▼" else "▶")
        }
        Span {
            val flags = renderDexClassAccessFlags(item.cls.accessFlags)
            Text(if (flags.isEmpty()) item.cls.descriptor else "$flags ${item.cls.descriptor}")
        }
        item.cls.superDescriptor?.let { superDescriptor ->
            Span({
                style {
                    property("color", cssColorFor(RenderTokenKind.COMMENT))
                    property("font-weight", "400")
                }
            }) {
                Text("extends $superDescriptor")
            }
        }
    }
}

@Composable
private fun DexSmaliRowView(row: DexSmaliRow, selection: HexSelectionState) {
    Div({
        style {
            rowBase()
            property("white-space", "pre")
            property("padding", "0 12px")
        }
    }) {
        for (token in row.tokens) {
            if (token.kind == RenderTokenKind.LINE_BREAK) continue
            val color = cssColorFor(token.kind)
            // The gutter offset is the only clickable token: it carries a
            // sourceOffset, while the in-row instruction address does not.
            val clickTarget = token.sourceOffset
                ?.takeIf { token.kind == RenderTokenKind.OFFSET_ANNOTATION }
            Span({
                style {
                    if (color.isNotEmpty()) property("color", color)
                    if (clickTarget != null) {
                        property("cursor", "pointer")
                        property("text-decoration", "underline dotted")
                    }
                }
                if (clickTarget != null) {
                    onClick { selection.selectRange(clickTarget.toInt(), CODE_UNIT_BYTES) }
                }
            }) {
                Text(token.text)
            }
        }
    }
}

/** One Dalvik code unit, the granularity a row's offset points at. */
private const val CODE_UNIT_BYTES = 2

/** Shared metrics of every line, so [VirtualizedList]'s fixed height is exact. */
private fun StyleScope.rowBase() {
    property("font-family", "monospace")
    property("font-size", "12px")
    property("line-height", "${SMALI_ROW_HEIGHT_PX}px")
    height(SMALI_ROW_HEIGHT_PX.px)
    property("box-sizing", "border-box")
}

private fun formatDexOffset(offset: Long): String = "0x" + offset.toString(16).padStart(6, '0')
