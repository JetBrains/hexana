package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.pe.clr.ClrPortablePdb
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.shared.CLR_IL_TAB_NAME
import org.jetbrains.hexana.shared.ClrIlRow
import org.jetbrains.hexana.shared.ClrIlType
import org.jetbrains.hexana.shared.clrIlSearchIndex
import org.jetbrains.hexana.shared.clrIlTypeRows
import org.jetbrains.hexana.shared.clrIlTypes

/**
 * Builds the **IL Disassembly** tab for a .NET assembly, mirroring the IntelliJ plugin's `ClrIlPanel`. Rows,
 * operand resolution and exception clauses all come from `plugins-shared`'s `ClrIlText.kt`;
 * this file only draws them, the same way [dexSmaliExtraTabs] draws smali.
 */
internal fun clrIlExtraTabs(metadata: ClrMetadata, bytes: CommonByteBuffer, pdb: ClrPortablePdb? = null): List<PanelTab> = listOf(
    PanelTab(CLR_IL_TAB_NAME, padding = false) { selection -> ClrIlTab(metadata, bytes, pdb, selection) },
)

/** Height of one IL line, in px. [VirtualizedList] needs it fixed and exact. */
private const val IL_ROW_HEIGHT_PX = 18

/** One line of the flattened listing. */
internal sealed interface ClrIlItem {
    data class Header(val type: ClrIlType, val expanded: Boolean) : ClrIlItem

    data class Row(val typeIndex: Int, val row: ClrIlRow) : ClrIlItem
}

/**
 * Flattens "type headers, plus the rows of the expanded ones" into one addressable list for
 * [VirtualizedList] without materializing a wrapper per row; see [DexSmaliItemIndex].
 */
internal class ClrIlItemIndex(
    private val types: List<ClrIlType>,
    /** Rows of the expanded types, keyed by [ClrIlType.index]. */
    private val rowsByType: Map<Int, List<ClrIlRow>>,
) {
    private val headerAt = IntArray(types.size)

    val size: Int

    init {
        var cursor = 0
        for ((i, type) in types.withIndex()) {
            headerAt[i] = cursor
            cursor += 1 + (rowsByType[type.index]?.size ?: 0)
        }
        size = cursor
    }

    fun at(itemIndex: Int): ClrIlItem? {
        if (itemIndex < 0 || itemIndex >= size) return null
        var low = 0
        var high = headerAt.size - 1
        var slot = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (headerAt[mid] <= itemIndex) {
                slot = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val type = types[slot]
        val offsetInType = itemIndex - headerAt[slot]
        if (offsetInType == 0) return ClrIlItem.Header(type, expanded = rowsByType.containsKey(type.index))
        val row = rowsByType[type.index]?.getOrNull(offsetInType - 1) ?: return null
        return ClrIlItem.Row(type.index, row)
    }
}

@Composable
private fun ClrIlTab(metadata: ClrMetadata, bytes: CommonByteBuffer, pdb: ClrPortablePdb?, selection: HexSelectionState) {
    val types = remember(metadata) { clrIlTypes(metadata) }

    var searchQuery by remember(metadata) { mutableStateOf("") }
    var debouncedQuery by remember(metadata) { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    // Method names are already decoded, so the index is cheap; it is still built on first search
    // so opening the tab costs nothing beyond the type list.
    val searchIndex by remember(metadata) { lazy { clrIlSearchIndex(metadata, types) } }
    val visibleTypes = remember(types, debouncedQuery) {
        val needle = debouncedQuery.trim().lowercase()
        if (needle.isEmpty()) {
            types
        } else {
            val matched = searchIndex.filter { needle in it.label.lowercase() }.mapTo(HashSet()) { it.typeIndex }
            types.filter { it.index in matched }
        }
    }

    // Collapsed initially, so opening the tab decodes no IL — same as the IntelliJ panel.
    var expanded by remember(metadata) { mutableStateOf(emptySet<Int>()) }
    val rowCache = remember(metadata, pdb) { HashMap<Int, List<ClrIlRow>>() }

    val itemIndex = remember(visibleTypes, expanded) {
        val rows = HashMap<Int, List<ClrIlRow>>(expanded.size)
        for (type in visibleTypes) {
            if (type.index !in expanded) continue
            rows[type.index] = rowCache.getOrPut(type.index) { clrIlTypeRows(metadata, bytes, type, pdb) }
        }
        ClrIlItemIndex(visibleTypes, rows)
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
            attr("placeholder", "Search types and methods…")
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
                Text(if (searchQuery.isNotEmpty()) "No types match the search." else "(no types)")
            }
            return@Div
        }

        VirtualizedList(itemCount = itemIndex.size, itemHeightPx = IL_ROW_HEIGHT_PX, naturalWidthRows = true) { index ->
            when (val item = itemIndex.at(index)) {
                is ClrIlItem.Header -> ClrTypeHeaderRow(item) {
                    expanded = if (item.type.index in expanded) expanded - item.type.index else expanded + item.type.index
                }
                is ClrIlItem.Row -> ClrIlRowView(item.row, selection)
                null -> Unit
            }
        }
    }
}

@Composable
private fun ClrTypeHeaderRow(item: ClrIlItem.Header, onToggle: () -> Unit) {
    Div({
        style {
            ilRowBase()
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
            Text("0x" + item.type.byteRange.first.toString(16).padStart(6, '0'))
        }
        Span({ style { property("color", "var(--vscode-editorLineNumber-foreground, #858585)") } }) {
            Text(if (item.expanded) "▼" else "▶")
        }
        Span { Text("${item.type.declaration} ${item.type.name}") }
        Span({
            style {
                property("color", cssColorFor(RenderTokenKind.COMMENT))
                property("font-weight", "400")
            }
        }) {
            val members = "${item.type.memberCount} member${if (item.type.memberCount == 1) "" else "s"}"
            Text(listOfNotNull(item.type.extends?.let { "extends $it" }, members).joinToString(", "))
        }
    }
}

@Composable
private fun ClrIlRowView(row: ClrIlRow, selection: HexSelectionState) {
    Div({
        style {
            ilRowBase()
            property("white-space", "pre")
            property("padding", "0 12px")
        }
    }) {
        for (token in row.tokens) {
            if (token.kind == RenderTokenKind.LINE_BREAK) continue
            val color = cssColorFor(token.kind)
            // The gutter offset is the only clickable token; it selects every byte the row covers.
            val clickTarget = token.sourceOffset?.takeIf { token.kind == RenderTokenKind.OFFSET_ANNOTATION }
            Span({
                style {
                    if (color.isNotEmpty()) property("color", color)
                    if (clickTarget != null) {
                        property("cursor", "pointer")
                        property("text-decoration", "underline dotted")
                    }
                }
                if (clickTarget != null) {
                    onClick { selection.selectRange(clickTarget.toInt(), row.byteSize.coerceAtLeast(1)) }
                }
            }) {
                Text(token.text)
            }
        }
    }
}

/** Shared metrics of every line, so [VirtualizedList]'s fixed height is exact. */
private fun StyleScope.ilRowBase() {
    property("font-family", "monospace")
    property("font-size", "12px")
    property("line-height", "${IL_ROW_HEIGHT_PX}px")
    height(IL_ROW_HEIGHT_PX.px)
    property("box-sizing", "border-box")
}
