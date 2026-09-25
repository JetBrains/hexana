package org.jetbrains.hexana.diff

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.asASCII
import org.jetbrains.jewel.ui.component.Text

/**
 * State of one hex diff viewer, shared by the Compose view and the diff window's Swing
 * toolbar actions (collapse toggle, previous / next difference).
 */
internal class HexDiffViewState(val leftTitle: String, val rightTitle: String) {
    /** `null` while the comparison is still running (or failed, see [error]). */
    var diff: HexDiff? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var collapseUnchanged: Boolean by mutableStateOf(true)

    /** Start rows of folds the user expanded by clicking them. */
    val expandedFolds = mutableStateSetOf<Int>()

    /**
     * Each side's sections, offset-sorted; empty until parsed (off the EDT, after the diff)
     * or when the format has no structure Hexana knows.
     */
    var sections: DiffSections by mutableStateOf(DiffSections.EMPTY)

    val listState = LazyListState()

    /** List indices to bring into view; collected by the composition, which owns the frame clock. */
    val scrollRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    val layout: HexDiffLayout? by derivedStateOf {
        diff?.let { HexDiffLayout.build(it, collapseUnchanged, expandedFolds.toSet()) }
    }

    /**
     * Scrolls to [offset], keeping the context rows above it in view. A row inside a fold
     * lands on the fold, which already names the sections it hides.
     */
    fun goToOffset(offset: Long) {
        val diff = diff ?: return
        val layout = layout ?: return
        val row = (offset / diff.bytesPerRow).toInt()
        val target = if (diff.isRowChanged(row)) (row - HexDiffLayout.CONTEXT_ROWS).coerceAtLeast(0) else row
        scrollRequests.tryEmit(layout.itemOf(target))
    }

    fun canGo(next: Boolean): Boolean = targetBlockItem(next) != null

    fun go(next: Boolean) {
        targetBlockItem(next)?.let { scrollRequests.tryEmit(it) }
    }

    /**
     * The list index to scroll to for the next / previous changed block: the block's first
     * row, less the context rows kept above it, so the change lands near the top.
     */
    private fun targetBlockItem(next: Boolean): Int? {
        val diff = diff ?: return null
        val layout = layout ?: return null
        val current = listState.firstVisibleItemIndex
        fun target(block: Int) = layout.itemOf((diff.blockStarts[block] - HexDiffLayout.CONTEXT_ROWS).coerceAtLeast(0))
        // Targets ascend with the block index, so binary-search the first one past the top row;
        // toolbar updates call this repeatedly on diffs with many blocks.
        var low = 0
        var high = diff.blockCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (target(mid) > current) high = mid else low = mid + 1
        }
        val block = if (next) low else generateSequence(low - 1) { it - 1 }.takeWhile { it >= 0 }
            .firstOrNull { target(it) < current } ?: -1
        return if (block in 0 until diff.blockCount) target(block) else null
    }
}

@Composable
internal fun HexDiffView(state: HexDiffViewState) {
    val diff = state.diff
    val layout = state.layout
    Column(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            SideTitle(state.leftTitle, diff?.leftSize, Modifier.weight(1f))
            Box(Modifier.width(SIDE_GAP))
            SideTitle(state.rightTitle, diff?.rightSize, Modifier.weight(1f))
        }
        val sections = state.sections
        if (sections.merged.isNotEmpty()) {
            SectionBar(sections.merged, state::goToOffset)
        }
        when {
            state.error != null -> Message(state.error!!)
            diff == null || layout == null -> Message("Comparing…")
            else -> DiffRows(state, diff, layout)
        }
    }
}

@Composable
private fun DiffRows(state: HexDiffViewState, diff: HexDiff, layout: HexDiffLayout) {
    val listState = state.listState
    LaunchedEffect(state) {
        state.scrollRequests.collect { listState.animateScrollToItem(it) }
    }
    val colors = DiffColors(
        modified = HexanaTheme.diffModifiedColor.copy(alpha = 0.55f),
        removed = HexanaTheme.diffRemovedColor.copy(alpha = 0.55f),
        added = HexanaTheme.diffAddedColor.copy(alpha = 0.55f),
        changedText = HexanaTheme.textColor,
        rowTint = HexanaTheme.diffModifiedColor.copy(alpha = 0.10f),
        sections = HexanaTheme.diffStructureColors,
    )
    val header = remember(diff.bytesPerRow) { columnHeader(diff.bytesPerRow) }

    Row(modifier = Modifier.fillMaxWidth()) {
        HeaderCell(header, Modifier.weight(1f))
        Box(Modifier.width(SIDE_GAP))
        HeaderCell(header, Modifier.weight(1f))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = layout.itemCount) { index ->
                when (val item = layout.itemAt(index)) {
                    is HexDiffLayout.Item.Row -> DiffRow(diff, item.row, colors, state.sections)
                    is HexDiffLayout.Item.Fold -> FoldRow(item, diff.bytesPerRow, state.sections) {
                        state.expandedFolds.add(item.startRow)
                    }
                }
            }
        }
        val scrollbarColor = HexanaTheme.hexTextColor
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(18.dp).padding(end = 8.dp),
            adapter = rememberScrollbarAdapter(scrollState = listState),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = scrollbarColor.copy(alpha = 0.50f),
                hoverColor = scrollbarColor.copy(alpha = 0.80f),
            ),
        )
    }
}

@Composable
private fun DiffRow(
    diff: HexDiff,
    row: Int,
    colors: DiffColors,
    sections: DiffSections,
) {
    val tint = if (diff.isRowChanged(row)) colors.rowTint else Color.Transparent
    val hexColor = HexanaTheme.hexTextColor
    Row(modifier = Modifier.fillMaxWidth()) {
        for (side in DiffSide.entries) {
            if (side == DiffSide.RIGHT) Box(Modifier.width(SIDE_GAP))
            val buffer = if (side == DiffSide.LEFT) diff.left else diff.right
            val sideSections = if (side == DiffSide.LEFT) sections.left else sections.right
            val line = remember(diff, row, side, colors, sideSections) {
                sideLine(diff, buffer, row, side, colors, sideSections)
            }
            Row(modifier = Modifier.weight(1f).background(tint).clipToBounds().padding(horizontal = 8.dp)) {
                Text(
                    line.offset,
                    style = MONOSPACE.copy(color = hexColor),
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.width(OFFSET_WIDTH),
                )
                Text(line.hex, style = MONOSPACE.copy(color = hexColor), softWrap = false, maxLines = 1)
                Text(
                    line.ascii,
                    style = MONOSPACE.copy(color = hexColor),
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun FoldRow(
    fold: HexDiffLayout.Item.Fold,
    bytesPerRow: Int,
    sections: DiffSections,
    onExpand: () -> Unit,
) {
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
    val label = "⋯  ${"%,d".format(rows)} unchanged ${if (rows == 1) "row" else "rows"}" +
        "  (${offsetText(from)} – ${offsetText(to - 1)})$inSections  ·  click to expand"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(HexanaTheme.hexTextColor.copy(alpha = 0.12f))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onExpand)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MONOSPACE.copy(color = HexanaTheme.hexTextColor), softWrap = false, maxLines = 1)
    }
}

/**
 * Both sides' sections as one wrapping bar of chips, paired by name: modified ones marked
 * with their changed-byte count, sections only one side has marked removed / added,
 * unchanged ones dimmed. Clicking a chip scrolls to the section's first change, or to its
 * start when nothing in it changed. Grows to a few lines, then scrolls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionBar(sections: List<MergedSection>, onNavigate: (Long) -> Unit) {
    val palette = HexanaTheme.diffStructureColors
    // Archives and captures can have tens of thousands of entries; past a cap only the
    // changed ones are listed, since those are what the bar is for.
    val shown = remember(sections) {
        if (sections.size <= MAX_BAR_SECTIONS) sections
        else sections.filter { it.status != SectionStatus.UNCHANGED }.take(MAX_BAR_SECTIONS)
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SECTION_BAR_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (section in shown) {
            SectionChip(section, palette) { onNavigate(section.target) }
        }
        if (shown.size < sections.size) {
            Text(
                "+${"%,d".format(sections.size - shown.size)} more",
                style = MONOSPACE.copy(color = HexanaTheme.hexTextColor, fontSize = 10.sp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun SectionChip(section: MergedSection, palette: List<Color>, onClick: () -> Unit) {
    val color = palette[section.colorIndex % palette.size]
    val shape = RoundedCornerShape(4.dp)
    val (text, border) = when (section.status) {
        SectionStatus.UNCHANGED -> section.label to null
        SectionStatus.MODIFIED ->
            "● ${section.label}  ${StringUtil.formatFileSize(section.changedBytes)}" to HexanaTheme.diffModifiedColor
        SectionStatus.LEFT_ONLY -> "− ${section.label}" to HexanaTheme.diffRemovedColor
        SectionStatus.RIGHT_ONLY -> "+ ${section.label}" to HexanaTheme.diffAddedColor
    }
    val changed = border != null
    Text(
        text,
        modifier = Modifier
            .background(color.copy(alpha = (color.alpha * 3f).coerceAtMost(1f)), shape)
            .then(if (border != null) Modifier.border(1.dp, border.copy(alpha = 0.9f), shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        softWrap = false,
        maxLines = 1,
        style = MONOSPACE.copy(
            color = HexanaTheme.textColor.copy(alpha = if (changed) 0.95f else 0.6f),
            fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
            fontSize = 10.sp,
        ),
    )
}

@Composable
private fun SideTitle(title: String, size: Int?, modifier: Modifier) {
    val text = if (size == null) title else "$title  ·  ${StringUtil.formatFileSize(size.toLong())}"
    Text(
        text,
        style = MONOSPACE.copy(fontWeight = FontWeight.Bold),
        softWrap = false,
        maxLines = 1,
        modifier = modifier.clipToBounds().padding(horizontal = 8.dp),
    )
}

@Composable
private fun HeaderCell(header: String, modifier: Modifier) {
    Row(modifier = modifier.clipToBounds().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Box(Modifier.width(OFFSET_WIDTH))
        Text(header, style = MONOSPACE, softWrap = false, maxLines = 1)
        Text("ASCII", style = MONOSPACE, softWrap = false, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun Message(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = MONOSPACE)
    }
}

private enum class DiffSide { LEFT, RIGHT }

private data class DiffColors(
    val modified: Color,
    val removed: Color,
    val added: Color,
    val changedText: Color,
    val rowTint: Color,
    val sections: List<Color>,
)

private class SideLine(
    val offset: String,
    val hex: AnnotatedString,
    val ascii: AnnotatedString,
)

/**
 * One side of one row. Bytes the other side lacks or holds differently are tinted —
 * modified on both sides, removed on the left, added on the right; the rest carry their
 * section's tint, which also fills the gap between two bytes of the same section so a
 * section reads as one band. Offsets past this side's end are left blank so both columns
 * stay aligned.
 */
private fun sideLine(
    diff: HexDiff,
    buffer: CommonByteBuffer,
    row: Int,
    side: DiffSide,
    colors: DiffColors,
    sections: List<DiffSection>,
): SideLine {
    val size = buffer.limit()
    val from = row * diff.bytesPerRow
    val count = (size - from).coerceIn(0, diff.bytesPerRow)
    val rowSections = Array(count) { sectionAt(sections, (from + it).toLong()) }
    fun sectionTint(section: DiffSection?): Color? = section?.let { colors.sections[it.colorIndex % colors.sections.size] }

    val hex = buildAnnotatedString {
        for (i in 0 until diff.bytesPerRow) {
            if (i > 0) {
                val joined = i < count && rowSections[i] != null && rowSections[i] === rowSections[i - 1]
                appendStyled(" ", if (joined) sectionTint(rowSections[i]) else null, changed = false, colors)
            }
            if (i >= count) {
                append("  ")
                continue
            }
            val offset = from + i
            appendByte(hexByte(buffer[offset]), diff.changeAt(offset), side, colors, sectionTint(rowSections[i]))
        }
    }
    val ascii = buildAnnotatedString {
        for (i in 0 until diff.bytesPerRow) {
            if (i >= count) {
                append(' ')
                continue
            }
            val offset = from + i
            appendByte(buffer[offset].asASCII() ?: ".", diff.changeAt(offset), side, colors, sectionTint(rowSections[i]))
        }
    }
    val offset = if (from < size) offsetText(from.toLong()) else ""
    return SideLine(offset, hex, ascii)
}

private fun AnnotatedString.Builder.appendByte(
    text: String,
    change: ByteChange,
    side: DiffSide,
    colors: DiffColors,
    sectionTint: Color?,
) {
    val diffBackground = when (change) {
        ByteChange.NONE -> null
        ByteChange.MODIFIED -> colors.modified
        ByteChange.REMOVED -> colors.removed.takeIf { side == DiffSide.LEFT }
        ByteChange.ADDED -> colors.added.takeIf { side == DiffSide.RIGHT }
    }
    appendStyled(text, diffBackground ?: sectionTint, changed = diffBackground != null, colors)
}

private fun AnnotatedString.Builder.appendStyled(text: String, background: Color?, changed: Boolean, colors: DiffColors) {
    when {
        changed -> withStyle(SpanStyle(background = background!!, color = colors.changedText, fontWeight = FontWeight.Bold)) {
            append(text)
        }
        background != null -> withStyle(SpanStyle(background = background)) { append(text) }
        else -> append(text)
    }
}

private fun columnHeader(bytesPerRow: Int): String =
    (0 until bytesPerRow).joinToString(" ") { it.toString(16).padStart(2, '0').uppercase() }

private fun hexByte(byte: Byte): String = (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()

private fun offsetText(offset: Long): String = offset.toString(16).padStart(8, '0').uppercase()

private val MONOSPACE = TextStyle(fontFamily = FontFamily.Monospace)
private val OFFSET_WIDTH = 84.dp
private val SIDE_GAP = 12.dp
private const val MAX_FOLD_SECTIONS = 3
private const val MAX_BAR_SECTIONS = 400
private val SECTION_BAR_MAX_HEIGHT = 96.dp
