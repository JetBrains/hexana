package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.diff.BinaryDiffSession
import org.jetbrains.hexana.shared.diff.DiffDetailSource
import org.jetbrains.hexana.shared.diff.DiffItemRef
import org.jetbrains.hexana.shared.diff.DiffOpenRequest
import org.jetbrains.hexana.shared.diff.DiffTabContent
import org.jetbrains.hexana.shared.diff.DiffTabContext
import org.jetbrains.hexana.shared.diff.DiffTextLine
import org.jetbrains.hexana.shared.diff.SideBySideDiff
import org.jetbrains.hexana.shared.diff.SideBySideRow
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.VerticalSplitPanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/** Loading state of a diff tab computed off the EDT. */
internal sealed interface DiffLoadable<out T> {
    data object Loading : DiffLoadable<Nothing>
    data class Error(val message: String) : DiffLoadable<Nothing>
    data class Ready<T>(val value: T) : DiffLoadable<T>
}

/**
 * Editor-scoped state of one open diff: every tab's content, the banner, and the
 * selection feeding the detail pane.
 *
 * Owned by [org.jetbrains.hexana.HexanaFileEditor] rather than the composition, so
 * switching the inner tabs — or the IDE editor tab, which can tear the composition
 * down — re-uses what is already computed. Each tab is computed once, concurrently,
 * on [scope]; the diff is recomputed only when the editor is closed and reopened.
 */
internal class BinaryDiffModel(
    val session: BinaryDiffSession,
    scope: CoroutineScope,
    open: ((DiffOpenRequest) -> Unit)?,
) {
    val selected = MutableStateFlow<DiffItemRef?>(null)
    val banner = MutableStateFlow<List<DiffTextLine>>(emptyList())
    val tabs: List<StateFlow<DiffLoadable<DiffTabContent>>>

    init {
        val context = DiffTabContext(select = { selected.value = it }, open = open)
        tabs = session.tabs.map { tab ->
            val flow = MutableStateFlow<DiffLoadable<DiffTabContent>>(DiffLoadable.Loading)
            scope.launch {
                flow.value = try {
                    DiffLoadable.Ready(tab.compute(context))
                } catch (e: Exception) {
                    DiffLoadable.Error(e.message ?: "Failed to compare: ${e::class.simpleName}")
                }
            }
            flow
        }
        scope.launch { banner.value = runCatching { session.banner() }.getOrDefault(emptyList()) }
    }
}

/**
 * The diff editor for every format: a tab strip over the [BinaryDiffModel]'s tabs and,
 * when the format has one, a bottom split rendering the selected row side by side.
 *
 * Which tabs exist and what they contain is decided by the format's
 * [org.jetbrains.hexana.shared.diff.BinaryDiffProvider] in `plugins-shared`; nothing
 * here is format-specific. [state] only backs the table bridge, whose rows here act
 * through `rowClickAction` rather than the hex view.
 */
@Composable
internal fun BinaryDiffEditorView(model: BinaryDiffModel, state: HexViewStateHolder) {
    val detail = model.session.detail
    if (detail == null) {
        DiffTabs(model, state)
        return
    }
    var topFraction by remember { mutableFloatStateOf(0.6f) }
    val selected by model.selected.collectAsState()
    VerticalSplitPanel(
        topFraction = topFraction,
        onDragFraction = { delta -> topFraction = (topFraction + delta).coerceIn(0.2f, 0.9f) },
        northPanel = { DiffTabs(model, state) },
        southPanel = { SideBySideDetailPanel(detail, selected) },
    )
}

@Composable
private fun DiffTabs(model: BinaryDiffModel, state: HexViewStateHolder) {
    var selectedTab by remember { mutableStateOf(0) }
    val banner by model.banner.collectAsState()
    val tabs = model.session.tabs

    Column(modifier = Modifier.fillMaxSize()) {
        if (tabs.size > 1) {
            TabStrip(
                tabs = tabs.mapIndexed { index, tab ->
                    TabData.Default(
                        selected = index == selectedTab,
                        content = { Text(tab.name) },
                        closable = false,
                        onClick = { selectedTab = index },
                    )
                },
                style = JewelTheme.defaultTabStyle,
            )
            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        }
        if (banner.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) { TextLines(banner) }
            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        }
        val tab = tabs.getOrNull(selectedTab) ?: return@Column
        val content by model.tabs[selectedTab].collectAsState()
        Box(modifier = Modifier.weight(1f)) {
            when (val c = content) {
                DiffLoadable.Loading -> Message(tab.loadingMessage)
                is DiffLoadable.Error -> Message(c.message)
                is DiffLoadable.Ready -> DiffTabBody(c.value, state)
            }
        }
    }
}

@Composable
private fun DiffTabBody(content: DiffTabContent, state: HexViewStateHolder) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (content.header.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) { TextLines(content.header) }
            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        }
        val table = content.table
        if (table == null) {
            content.emptyMessage?.let { Message(it) }
            return@Column
        }
        Box(modifier = Modifier.weight(1f)) {
            TableTabContent(table, state)
        }
        content.footer?.let {
            Text(it, style = MONOSPACE, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Bottom detail pane: renders the selected item side by side (base | head), each
 * selection off the EDT, with removed lines tinted on the left and added on the right.
 */
@Composable
private fun SideBySideDetailPanel(detail: DiffDetailSource, selected: DiffItemRef?) {
    var rendered by remember { mutableStateOf<SideBySideDiff?>(null) }
    LaunchedEffect(selected) {
        rendered = null
        if (selected != null) {
            rendered = withContext(Dispatchers.IO) { detail.render(selected) }
        }
    }
    val diff = rendered
    when {
        selected == null -> Message(detail.placeholder)
        diff == null -> Message("Rendering…")
        else -> Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                diff.title,
                style = MONOSPACE.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            val empty = diff.emptyMessage
            if (empty != null) {
                Message(empty)
            } else {
                SideBySideRows(diff.rows)
            }
        }
    }
}

@Composable
private fun SideBySideRows(rows: List<SideBySideRow>) {
    val removedBg = HexanaTheme.diffRemovedColor
    val addedBg = HexanaTheme.diffAddedColor
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows.size) { i ->
            val row = rows[i]
            Row(modifier = Modifier.fillMaxWidth()) {
                SideCell(if (row.leftRemoved) "-" else " ", row.left, if (row.leftRemoved) removedBg else Color.Transparent, Modifier.weight(0.5f))
                SideCell(if (row.rightAdded) "+" else " ", row.right, if (row.rightAdded) addedBg else Color.Transparent, Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun SideCell(gutter: String, text: String?, background: Color, modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth().background(background).padding(end = 8.dp)) {
        Text(if (text == null) "" else "$gutter $text", style = MONOSPACE)
    }
}

@Composable
private fun TextLines(lines: List<DiffTextLine>) {
    for (line in lines) {
        Text(line.text, style = if (line.bold) MONOSPACE.copy(fontWeight = FontWeight.Bold) else MONOSPACE)
    }
}

@Composable
private fun Message(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = MONOSPACE)
    }
}

private val MONOSPACE = TextStyle(fontFamily = FontFamily.Monospace)
