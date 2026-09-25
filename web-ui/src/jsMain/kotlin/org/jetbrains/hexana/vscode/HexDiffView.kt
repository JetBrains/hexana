package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.shared.diff.ByteChange
import org.jetbrains.hexana.shared.diff.DiffSection
import org.jetbrains.hexana.shared.diff.DiffSections
import org.jetbrains.hexana.shared.diff.HexDiff
import org.jetbrains.hexana.shared.diff.HexDiffLayout
import org.jetbrains.hexana.shared.diff.HexDiffStructure
import org.jetbrains.hexana.shared.diff.MergedSection
import org.jetbrains.hexana.shared.diff.SectionStatus
import org.jetbrains.hexana.shared.diff.TableTabDiff
import org.jetbrains.hexana.shared.diff.diffSections
import org.jetbrains.hexana.shared.diff.mergeSections
import org.jetbrains.hexana.shared.diff.sectionAt
import org.jetbrains.hexana.shared.diff.sectionLabelsIn

/** Which of the two diff views a diff opens on. */
enum class DiffViewMode {
    /** The positional hex diff, with sections and table tabs; works for any pair. */
    HEX,

    /** The structural diff of a [org.jetbrains.hexana.shared.diff.BinaryDiffProvider]; same-kind pairs only. */
    HEXANA,
}

/**
 * A diff with a switch between the hex diff and the Hexana (structural) diff — the webview
 * counterpart of the viewer switcher IntelliJ's diff window offers.
 *
 * Each view is mounted the first time it is shown and then only hidden, so switching back
 * keeps its computed diff, scroll position and selection. The size and functions diff is offered only
 * for pairs a provider compares; for any other pair the hex diff is all there is.
 */
@Composable
fun DiffModeView(input: BinaryDiffInput, initialMode: DiffViewMode) {
    val structuralAvailable = remember(input) { BinaryDiffProviderRegistry.forInput(input) != null }
    var mode by remember(input) {
        mutableStateOf(if (initialMode == DiffViewMode.HEXANA && structuralAvailable) DiffViewMode.HEXANA else DiffViewMode.HEX)
    }
    val mounted = remember(input) { mutableStateSetOf(mode) }
    val hexState = remember(input) { HexDiffState(input) }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
            property("font-family", "var(--vscode-editor-font-family, 'Menlo', 'Monaco', 'Courier New', monospace)")
            property("font-size", "12px")
            property("color", "var(--hexana-fg, #ccc)")
            property("background", "var(--vscode-editor-background, #1e1e1e)")
        }
    }) {
        ModeSwitch(mode, structuralAvailable) {
            mode = it
            mounted += it
        }
        for (view in DiffViewMode.entries) {
            if (view !in mounted) continue
            // Keyed: mounting the other view inserts it before or after this one, and without
            // a key this view's state would be matched by position and recreated.
            key(view) { Div({
                style {
                    display(if (view == mode) DisplayStyle.Flex else DisplayStyle.None)
                    property("flex-direction", "column")
                    property("flex", "1")
                    property("min-height", "0")
                    property("overflow", "hidden")
                }
            }) {
                when (view) {
                    DiffViewMode.HEX -> HexDiffView(hexState)
                    DiffViewMode.HEXANA -> BinaryDiffView(input)
                }
            } }
        }
    }
}

@Composable
private fun ModeSwitch(mode: DiffViewMode, structuralAvailable: Boolean, onSelect: (DiffViewMode) -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "4px")
            property("padding", "6px 12px")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("flex-shrink", "0")
        }
    }) {
        SegmentButton("Hex", selected = mode == DiffViewMode.HEX, enabled = true) { onSelect(DiffViewMode.HEX) }
        SegmentButton(
            "Size, modules and functions",
            selected = mode == DiffViewMode.HEXANA,
            enabled = structuralAvailable,
            tooltip = if (structuralAvailable) null else "Only binaries of the same kind (two WASM modules, two components, two native binaries) have a size and functions diff.",
        ) { onSelect(DiffViewMode.HEXANA) }
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, enabled: Boolean, tooltip: String? = null, onClick: () -> Unit) {
    Span({
        attr("role", "button")
        tooltip?.let { title(it) }
        style {
            property("padding", "2px 10px")
            property("border-radius", "4px")
            property("user-select", "none")
            property("cursor", if (enabled) "pointer" else "default")
            property("opacity", if (enabled) "1" else "0.45")
            property("border", "1px solid var(--hexana-border, #444)")
            if (selected) {
                property("background", "var(--vscode-button-background, #0e639c)")
                property("color", "var(--vscode-button-foreground, #fff)")
            }
        }
        if (enabled) onClick { onClick() }
    }) { Text(label) }
}

/**
 * Everything the hex diff computes or remembers, kept outside the composition so hiding the
 * view (switching to the size and functions diff) loses nothing.
 */
internal class HexDiffState(val input: BinaryDiffInput) {
    var diff: HexDiff? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var sections: DiffSections by mutableStateOf(DiffSections.EMPTY)

    /** Whether the tabs' space is reserved; decided once both sides are parsed. */
    var tabsExpected: Boolean by mutableStateOf(false)

    /** The diffed table tabs; `null` while being computed. */
    var tabs: List<TableTabDiff>? by mutableStateOf(null)
    var selectedTab: Int by mutableStateOf(0)
        private set

    /** Tabs shown at least once; they stay mounted (hidden) so each keeps its sort, search and row. */
    val visitedTabs = mutableStateSetOf<Int>()
    var tabsHeightPx: Int by mutableStateOf(280)

    var collapseUnchanged: Boolean by mutableStateOf(true)
    val expandedFolds = mutableStateSetOf<Int>()
    var reveal: ListReveal? by mutableStateOf(null)

    /** The changed block the previous / next buttons last moved to; -1 before any. */
    var currentBlock: Int by mutableStateOf(-1)
    val selection = HexSelectionState()
    var started = false
    private var revealRequests = 0

    val layout: HexDiffLayout? by derivedStateOf {
        diff?.let { HexDiffLayout.build(it, collapseUnchanged, expandedFolds.toSet()) }
    }

    /** Scrolls to [offset], keeping the context rows above a change in view. */
    fun goToOffset(offset: Long) {
        val diff = diff ?: return
        val layout = layout ?: return
        val row = (offset / diff.bytesPerRow).toInt()
        val target = if (diff.isRowChanged(row)) (row - HexDiffLayout.CONTEXT_ROWS).coerceAtLeast(0) else row
        reveal = ListReveal(layout.itemOf(target), ++revealRequests)
    }

    fun selectTab(index: Int) {
        selectedTab = index
        visitedTabs += index
    }

    fun goToBlock(block: Int) {
        val diff = diff ?: return
        if (block !in 0 until diff.blockCount) return
        currentBlock = block
        goToOffset(diff.blockStarts[block].toLong() * diff.bytesPerRow)
    }

    /**
     * Compares the bytes, then parses both sides for their sections and table tabs. The
     * webview has one thread: each step lets the browser paint first, and the table tabs
     * yield between providers.
     */
    suspend fun compute() {
        try {
            awaitAnimationFrame()
            val diff = withContext(Dispatchers.Default) { HexDiff.compute(input.left.duplicate(), input.right.duplicate()) }
            this.diff = diff
            awaitAnimationFrame()
            val left = providerOf(input.left, input.leftName)
            val right = providerOf(input.right, input.rightName)
            if (!HexDiffStructure.areComparable(left, right)) {
                // Different formats: their sections and tables describe unrelated things.
                tabs = emptyList()
                return
            }
            tabsExpected = HexDiffStructure.expectsTabs(left, right)
            val sections = withContext(Dispatchers.Default) {
                mergeSections(sectionsOf(diff, left, diff.leftSize), sectionsOf(diff, right, diff.rightSize))
            }
            this.sections = sections
            awaitAnimationFrame()
            val tabs = HexDiffStructure.diffTableTabs(
                left, right, input.leftName, input.rightName,
                onError = { console.warn("[Hexana] A table tab failed in the hex diff:", it.message) },
                between = { awaitAnimationFrame() },
                onRowClick = { l, r -> listOfNotNull(l, r).minOrNull()?.let { goToOffset(it.toLong()) } },
            )
            selectTab(tabs.indexOfFirst { it.changed > 0 }.coerceAtLeast(0))
            this.tabs = tabs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Could not compare: ${e.message ?: e::class.simpleName}"
            tabs = emptyList()
        }
    }

    private fun providerOf(bytes: CommonByteBuffer, name: String): BinaryProvider? = try {
        createBinaryProvider(bytes.duplicate(), sourcePath = null, fileName = name)
    } catch (e: Exception) {
        null
    }

    private fun sectionsOf(diff: HexDiff, provider: BinaryProvider?, size: Int): List<DiffSection> = try {
        provider?.let { diffSections(diff, it.structureRanges, size) }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
internal fun HexDiffView(state: HexDiffState) {
    LaunchedEffect(state) {
        if (!state.started) {
            state.started = true
            state.compute()
        }
    }
    val diff = state.diff
    val layout = state.layout
    val tabs = state.tabs
    val showTabs = if (tabs == null) state.tabsExpected else tabs.isNotEmpty()

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("flex", "1")
            property("min-height", "0")
            property("overflow", "hidden")
        }
    }) {
        DiffToolbar(state)
        SideTitles(state)
        if (state.sections.merged.isNotEmpty()) SectionBar(state.sections.merged, state::goToOffset)
        when {
            state.error != null -> Message(state.error!!)
            diff == null || layout == null -> Message("Comparing…")
            else -> DiffRows(state, diff, layout)
        }
        if (showTabs) {
            ResizeDivider { delta -> state.tabsHeightPx = (state.tabsHeightPx - delta.toInt()).coerceIn(80, 2000) }
            Div({
                style {
                    property("flex", "0 0 ${state.tabsHeightPx}px")
                    display(DisplayStyle.Flex)
                    property("flex-direction", "column")
                    property("overflow", "hidden")
                    property("border-top", "1px solid var(--hexana-border, #333)")
                }
            }) {
                if (tabs == null) TabsLoading() else DiffTabsPanel(state, tabs)
            }
        }
    }
}

@Composable
private fun DiffToolbar(state: HexDiffState) {
    val diff = state.diff
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "8px")
            property("padding", "4px 12px")
            property("flex-shrink", "0")
        }
    }) {
        val blocks = diff?.blockCount ?: 0
        ToolbarButton("↑ Previous difference", enabled = blocks > 0 && state.currentBlock > 0) {
            state.goToBlock(state.currentBlock - 1)
        }
        ToolbarButton("↓ Next difference", enabled = blocks > 0 && state.currentBlock < blocks - 1) {
            state.goToBlock(state.currentBlock + 1)
        }
        ToolbarButton(if (state.collapseUnchanged) "Expand unchanged rows" else "Collapse unchanged rows", enabled = diff != null) {
            state.collapseUnchanged = !state.collapseUnchanged
            if (state.collapseUnchanged) state.expandedFolds.clear()
        }
        Span({ style { property("margin-left", "auto"); property("opacity", "0.75") } }) {
            Text(statusText(state))
        }
    }
}

@Composable
private fun ToolbarButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Span({
        attr("role", "button")
        style {
            property("padding", "1px 8px")
            property("border-radius", "3px")
            property("user-select", "none")
            property("cursor", if (enabled) "pointer" else "default")
            property("opacity", if (enabled) "1" else "0.4")
            property("background", "var(--vscode-button-secondaryBackground, #3a3d41)")
            property("color", "var(--vscode-button-secondaryForeground, #ccc)")
        }
        if (enabled) onClick { onClick() }
    }) { Text(label) }
}

private fun statusText(state: HexDiffState): String {
    val diff = state.diff ?: return if (state.error != null) "Comparison failed" else "Comparing…"
    if (diff.isIdentical) return "Contents are identical"
    val bytes = "${formatCount(diff.changedBytes)} ${if (diff.changedBytes == 1L) "byte differs" else "bytes differ"}"
    val blocks = "${formatCount(diff.blockCount.toLong())} ${if (diff.blockCount == 1) "block" else "blocks"}"
    val merged = state.sections.merged
    val sections = if (merged.isEmpty()) "" else {
        val modified = merged.count { it.status != SectionStatus.UNCHANGED }
        " · ${formatCount(modified.toLong())} of ${formatCount(merged.size.toLong())} ${if (merged.size == 1) "section" else "sections"} modified"
    }
    return "$bytes in $blocks$sections"
}

@Composable
private fun SideTitles(state: HexDiffState) {
    val diff = state.diff
    Div({ style { display(DisplayStyle.Flex); property("padding", "2px 12px"); property("font-weight", "bold"); property("flex-shrink", "0") } }) {
        SideCellBox { Text(title(state.input.leftName, diff?.leftSize)) }
        SideCellBox { Text(title(state.input.rightName, diff?.rightSize)) }
    }
}

private fun title(name: String, size: Int?) = if (size == null) name else "$name  ·  ${formatSize(size)}"

/**
 * Both sides' sections as one wrapping bar of chips, paired by name: modified ones marked
 * with their changed-byte count, sections only one side has marked removed / added,
 * unchanged ones dimmed. A click scrolls to the section's first change, or its start.
 */
@Composable
private fun SectionBar(sections: List<MergedSection>, onNavigate: (Long) -> Unit) {
    // Archives and captures can have tens of thousands of entries; past a cap only the
    // changed ones are listed, since those are what the bar is for.
    val shown = remember(sections) {
        if (sections.size <= MAX_BAR_SECTIONS) sections
        else sections.filter { it.status != SectionStatus.UNCHANGED }.take(MAX_BAR_SECTIONS)
    }
    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-wrap", "wrap")
            property("gap", "4px")
            property("padding", "2px 12px 6px")
            property("max-height", "88px")
            property("overflow-y", "auto")
            property("flex-shrink", "0")
        }
    }) {
        for (section in shown) SectionChip(section) { onNavigate(section.target) }
        if (shown.size < sections.size) {
            Span({ style { property("opacity", "0.6"); property("font-size", "10px") } }) {
                Text("+${formatCount((sections.size - shown.size).toLong())} more")
            }
        }
    }
}

@Composable
private fun SectionChip(section: MergedSection, onClick: () -> Unit) {
    val (text, border) = when (section.status) {
        SectionStatus.UNCHANGED -> section.label to null
        SectionStatus.MODIFIED -> "● ${section.label}  ${formatSizeLong(section.changedBytes)}" to MODIFIED_BORDER
        SectionStatus.LEFT_ONLY -> "− ${section.label}" to REMOVED_BORDER
        SectionStatus.RIGHT_ONLY -> "+ ${section.label}" to ADDED_BORDER
    }
    Span({
        attr("role", "link")
        style {
            property("font-size", "10px")
            property("padding", "0 6px")
            property("border-radius", "4px")
            property("white-space", "nowrap")
            property("cursor", "pointer")
            property("background", SECTION_CHIP_COLORS[section.colorIndex % SECTION_CHIP_COLORS.size])
            property("border", "1px solid ${border ?: "transparent"}")
            property("opacity", if (border != null) "1" else "0.65")
            if (border != null) property("font-weight", "bold")
        }
        onClick { onClick() }
    }) { Text(text) }
}

@Composable
private fun DiffRows(state: HexDiffState, diff: HexDiff, layout: HexDiffLayout) {
    Div({ style { display(DisplayStyle.Flex); property("padding", "0 12px"); property("opacity", "0.8"); property("flex-shrink", "0") } }) {
        val header = remember(diff.bytesPerRow) { (0 until diff.bytesPerRow).joinToString(" ") { hex(it) } }
        repeat(2) {
            SideCellBox {
                Span({ style { property("display", "inline-block"); property("min-width", OFFSET_WIDTH) } }) {}
                Span({ style { property("white-space", "pre") } }) { Text(header) }
                Span({ style { property("margin-left", "16px") } }) { Text("ASCII") }
            }
        }
    }
    val sections = state.sections
    VirtualizedList(itemCount = layout.itemCount, itemHeightPx = ROW_HEIGHT_PX, reveal = state.reveal) { index ->
        when (val item = layout.itemAt(index)) {
            is HexDiffLayout.Item.Row -> key(item.key) { DiffRow(diff, item.row, sections) }
            is HexDiffLayout.Item.Fold -> key(item.key) {
                FoldRow(item, diff.bytesPerRow, sections) { state.expandedFolds += item.startRow }
            }
        }
    }
}

@Composable
private fun DiffRow(diff: HexDiff, row: Int, sections: DiffSections) {
    val changed = diff.isRowChanged(row)
    Div({
        style {
            display(DisplayStyle.Flex)
            height(100.percent)
            property("align-items", "center")
            property("padding", "0 12px")
            property("white-space", "pre")
            if (changed) property("background", ROW_TINT)
        }
    }) {
        for (left in listOf(true, false)) {
            val line = remember(diff, row, left, sections) {
                sideLine(diff, if (left) diff.left else diff.right, row, left, if (left) sections.left else sections.right)
            }
            SideCellBox {
                Span({ style { property("display", "inline-block"); property("min-width", OFFSET_WIDTH); property("opacity", "0.6") } }) {
                    Text(line.offset)
                }
                Runs(line.hex)
                Span({ style { property("margin-left", "16px") } }) { Runs(line.ascii) }
            }
        }
    }
}

@Composable
private fun Runs(runs: List<Run>) {
    for (run in runs) {
        if (run.background == null && !run.changed) {
            Text(run.text)
        } else {
            Span({
                style {
                    run.background?.let { property("background", it) }
                    if (run.changed) {
                        property("font-weight", "bold")
                        property("color", "var(--vscode-editor-foreground, #fff)")
                    }
                }
            }) { Text(run.text) }
        }
    }
}

@Composable
private fun FoldRow(fold: HexDiffLayout.Item.Fold, bytesPerRow: Int, sections: DiffSections, onExpand: () -> Unit) {
    val rows = fold.endRow - fold.startRow
    val from = fold.startRow.toLong() * bytesPerRow
    val to = fold.endRow.toLong() * bytesPerRow
    val inSections = remember(fold, sections) {
        val names = (sectionLabelsIn(sections.left, from, to) + sectionLabelsIn(sections.right, from, to)).distinct()
        when {
            names.isEmpty() -> ""
            names.size <= MAX_FOLD_SECTIONS -> "  in ${names.joinToString(", ")}"
            else -> "  in ${names.take(MAX_FOLD_SECTIONS).joinToString(", ")} +${names.size - MAX_FOLD_SECTIONS} more"
        }
    }
    Div({
        attr("role", "button")
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            height(100.percent)
            property("padding", "0 12px")
            property("cursor", "pointer")
            property("white-space", "nowrap")
            property("overflow", "hidden")
            property("opacity", "0.75")
            property("background", "rgba(128, 128, 128, 0.12)")
        }
        onClick { onExpand() }
    }) {
        Text(
            "⋯  ${formatCount(rows.toLong())} unchanged ${if (rows == 1) "row" else "rows"}" +
                "  (${offsetText(from)} – ${offsetText(to - 1)})$inSections  ·  click to expand",
        )
    }
}

@Composable
private fun SideCellBox(content: @Composable () -> Unit) {
    Div({
        style {
            property("flex", "1")
            property("min-width", "0")
            property("overflow", "hidden")
            property("white-space", "pre")
        }
    }) { content() }
}

@Composable
private fun DiffTabsPanel(state: HexDiffState, tabs: List<TableTabDiff>) {
    val selected = state.selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    // Scrolls sideways when the tabs do not fit.
    Div({
        style {
            display(DisplayStyle.Flex)
            property("overflow-x", "auto")
            property("flex-shrink", "0")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
        }
    }) {
        for ((i, tab) in tabs.withIndex()) {
            val active = i == selected
            Div({
                style {
                    property("padding", "5px 14px")
                    property("cursor", "pointer")
                    property("user-select", "none")
                    property("white-space", "nowrap")
                    property("border-bottom", if (active) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                    property("opacity", if (active || tab.changed > 0) "1" else "0.6")
                }
                onClick { state.selectTab(i) }
            }) {
                if (tab.changed > 0) {
                    Span({ style { property("color", MODIFIED_BORDER) } }) { Text("● ") }
                    Span({ style { property("font-weight", "bold") } }) { Text(tab.name) }
                    Span({ style { property("opacity", "0.7") } }) { Text("  ${formatCount(tab.changed.toLong())}") }
                } else {
                    Text(tab.name)
                }
            }
        }
    }
    // Every visited tab stays mounted and only the current one is shown, so switching back finds
    // its table as it was left. Keyed by tab: visiting a new tab inserts it among the others, and
    // without a key their state would be matched by position.
    for ((index, tab) in tabs.withIndex()) {
        if (index !in state.visitedTabs) continue
        key(index) {
            Div({
                style {
                    property("flex", "1")
                    property("min-height", "0")
                    display(if (index == selected) DisplayStyle.Flex else DisplayStyle.None)
                    property("flex-direction", "column")
                    property("overflow", "hidden")
                    property("padding", "6px 12px")
                }
            }) {
                TableTabContent(tab.descriptor, state.selection)
            }
        }
    }
}

@Composable
private fun TabsLoading() {
    remember { ensureSpinnerStyle() }
    Div({
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("justify-content", "center")
            property("gap", "8px")
            property("flex", "1")
            property("opacity", "0.75")
        }
    }) {
        Span({ classes(SPINNER_CLASS) }) {}
        Text("Comparing tables…")
    }
}

@Composable
private fun Message(message: String) {
    Div({ style { property("padding", "8px 12px") } }) { Text(message) }
}

/** A run of adjacent characters sharing one style. */
private class Run(val text: String, val background: String?, val changed: Boolean)

private class SideLine(val offset: String, val hex: List<Run>, val ascii: List<Run>)

/**
 * One side of one row, as styled runs. Bytes the other side lacks or holds differently are
 * tinted — modified on both sides, removed on the left, added on the right; the rest carry
 * their section's tint, which also fills the gap between two bytes of the same section so a
 * section reads as one band. Offsets past this side's end are left blank.
 */
private fun sideLine(diff: HexDiff, buffer: CommonByteBuffer, row: Int, left: Boolean, sections: List<DiffSection>): SideLine {
    val size = buffer.limit()
    val from = row * diff.bytesPerRow
    val count = (size - from).coerceIn(0, diff.bytesPerRow)
    val rowSections = Array(count) { sectionAt(sections, (from + it).toLong()) }
    fun sectionTint(section: DiffSection?) = section?.let { SECTION_COLORS[it.colorIndex % SECTION_COLORS.size] }
    fun diffTint(offset: Int): String? = when (diff.changeAt(offset)) {
        ByteChange.NONE -> null
        ByteChange.MODIFIED -> MODIFIED_BG
        ByteChange.REMOVED -> REMOVED_BG.takeIf { left }
        ByteChange.ADDED -> ADDED_BG.takeIf { !left }
    }

    val hex = RunBuilder()
    val ascii = RunBuilder()
    for (i in 0 until diff.bytesPerRow) {
        if (i > 0) {
            val joined = i < count && rowSections[i] != null && rowSections[i] === rowSections[i - 1]
            hex.add(" ", if (joined) sectionTint(rowSections[i]) else null, changed = false)
        }
        if (i >= count) {
            hex.add("  ", null, false)
            ascii.add(" ", null, false)
            continue
        }
        val offset = from + i
        val byte = buffer[offset]
        val tint = diffTint(offset)
        val background = tint ?: sectionTint(rowSections[i])
        hex.add(hex(byte.toInt() and 0xFF), background, changed = tint != null)
        ascii.add(asciiChar(byte), background, changed = tint != null)
    }
    return SideLine(if (from < size) offsetText(from.toLong()) else "", hex.finish(), ascii.finish())
}

/** Collects characters into [Run]s, starting a new run whenever the style changes. */
private class RunBuilder {
    private val runs = ArrayList<Run>()
    private val text = StringBuilder()
    private var background: String? = null
    private var changed = false

    fun add(chunk: String, background: String?, changed: Boolean) {
        if (text.isNotEmpty() && (background != this.background || changed != this.changed)) flush()
        this.background = background
        this.changed = changed
        text.append(chunk)
    }

    fun finish(): List<Run> {
        if (text.isNotEmpty()) flush()
        return runs
    }

    private fun flush() {
        runs += Run(text.toString(), background, changed)
        text.clear()
    }
}

private fun asciiChar(byte: Byte): String {
    val c = byte.toInt() and 0xFF
    return if (c in 0x20..0x7E) c.toChar().toString() else "."
}

private fun hex(value: Int): String = value.toString(16).padStart(2, '0').uppercase()

private fun offsetText(offset: Long): String = offset.toString(16).padStart(8, '0').uppercase()

/** `1234567` → `1,234,567`, without `String.format`, which JS lacks. */
private fun formatCount(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()

private const val SPINNER_CLASS = "hexana-diff-spinner"

/** Adds the spinner's keyframes once per document; the webview has no stylesheet for it. */
private fun ensureSpinnerStyle() {
    if (document.getElementById(SPINNER_CLASS) != null) return
    val style = document.createElement("style")
    style.id = SPINNER_CLASS
    style.textContent = """
        @keyframes hexana-diff-spin { to { transform: rotate(360deg); } }
        .$SPINNER_CLASS { width: 12px; height: 12px; border-radius: 50%;
            border: 2px solid rgba(128,128,128,0.35); border-top-color: var(--vscode-progressBar-background, #0e70c0);
            animation: hexana-diff-spin 0.8s linear infinite; display: inline-block; }
    """.trimIndent()
    document.head?.appendChild(style)
}

// Diff colours: amber modified, red removed, green added — as in the IDE and VS Code's own diff.
private const val MODIFIED_BG = "rgba(210, 153, 34, 0.45)"
private const val REMOVED_BG = "rgba(248, 81, 73, 0.45)"
private const val ADDED_BG = "rgba(46, 160, 67, 0.45)"
private const val MODIFIED_BORDER = "rgba(210, 153, 34, 0.95)"
private const val REMOVED_BORDER = "rgba(248, 81, 73, 0.95)"
private const val ADDED_BORDER = "rgba(46, 160, 67, 0.95)"
private const val ROW_TINT = "rgba(210, 153, 34, 0.07)"

/**
 * Section tints. Unlike the single-file hex view's palette they avoid the diff's own hues —
 * amber, red, green — and keep to blues, purples, teal and slate, so a tinted section never
 * reads as a change.
 */
private val SECTION_COLORS = listOf(
    "rgba(100, 149, 237, 0.17)",  // cornflower blue
    "rgba(186, 147, 214, 0.17)",  // lavender
    "rgba(90, 200, 215, 0.17)",   // cyan teal
    "rgba(150, 160, 190, 0.17)",  // slate
    "rgba(125, 115, 235, 0.17)",  // indigo
)
private val SECTION_CHIP_COLORS = listOf(
    "rgba(100, 149, 237, 0.40)",
    "rgba(186, 147, 214, 0.40)",
    "rgba(90, 200, 215, 0.40)",
    "rgba(150, 160, 190, 0.40)",
    "rgba(125, 115, 235, 0.40)",
)

private const val OFFSET_WIDTH = "80px"
private const val MAX_BAR_SECTIONS = 400
private const val MAX_FOLD_SECTIONS = 3
