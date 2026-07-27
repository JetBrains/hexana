package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.WasmDispatchSurfaceDiffResult
import org.jetbrains.hexana.providers.format.WasmEntityDiffResult
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.ui.VerticalSplitPanel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Top-level view for a WASM module diff: a tab strip over the size-impact and
 * entity comparisons (top), with a bottom split that shows the side-by-side WAT
 * of the function selected in either table (rendered on demand).
 *
 * The expensive size/entity diffs are NOT computed here. They are computed once,
 * off the EDT, by [org.jetbrains.hexana.HexanaFileEditor] and supplied as
 * [sizeModelFlow]/[entityModelFlow] — editor-scoped state that lives for the lifetime
 * of the editor TAB. This view only collects them, so switching the inner tabs (or the
 * IDE editor tab, which can tear down and rebuild this composition) re-uses the
 * already-computed result; the diff is recomputed only when the tab is closed and
 * reopened (a fresh editor).
 *
 * [leftSymbolMap]/[rightSymbolMap] are `--emit-symbol-map` sidecars (when present) that
 * restore original names on a minified module so matching name-anchors; here they only
 * drive the banner's "names restored" wording. A banner reports the minified state.
 * Hosted for a [org.jetbrains.hexana.HexanaWasmDiffVirtualFile].
 */
@Composable
internal fun WasmDiffEditorView(
    leftBuffer: CommonByteBuffer,
    rightBuffer: CommonByteBuffer,
    leftName: String,
    rightName: String,
    sizeModelFlow: StateFlow<DiffLoadable<SizeReady>>,
    entityModelFlow: StateFlow<DiffLoadable<WasmEntityDiffResult>>,
    dispatchModelFlow: StateFlow<DiffLoadable<WasmDispatchSurfaceDiffResult>>,
    leftSymbolMap: WasmSymbolMap? = null,
    rightSymbolMap: WasmSymbolMap? = null,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedFunction by remember { mutableStateOf<FunctionSelection?>(null) }
    var topFraction by remember { mutableFloatStateOf(0.6f) }
    var minifiedNote by remember(leftBuffer, rightBuffer) { mutableStateOf<String?>(null) }
    val mapsLoaded = leftSymbolMap != null || rightSymbolMap != null

    // Both models are computed and held by the editor; collecting is cheap and survives
    // every recomposition / composition teardown for as long as the editor tab is open.
    val sizeModel by sizeModelFlow.collectAsState()
    val entityModel by entityModelFlow.collectAsState()
    val dispatchModel by dispatchModelFlow.collectAsState()

    // Detection is cheap (import/export sections only) but off the EDT for safety.
    LaunchedEffect(leftBuffer, rightBuffer) {
        minifiedNote = withContext(Dispatchers.IO) {
            val left = WasmBinaryProvider(leftBuffer.duplicate()).minification
            val right = WasmBinaryProvider(rightBuffer.duplicate()).minification
            val notes = buildList {
                if (left.minified) add("base: ${left.evidence}")
                if (right.minified) add("head: ${right.evidence}")
            }
            notes.takeIf { it.isNotEmpty() }?.joinToString("    ")
        }
    }

    val titles = listOf("Size Impact", "Entities", "Dispatch")
    val tabs = titles.mapIndexed { index, title ->
        TabData.Default(
            selected = index == selectedTab,
            content = { Text(title) },
            closable = false,
            onClick = { selectedTab = index },
        )
    }

    VerticalSplitPanel(
        topFraction = topFraction,
        onDragFraction = { delta -> topFraction = (topFraction + delta).coerceIn(0.2f, 0.9f) },
        northPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
                Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
                minifiedNote?.let { note ->
                    MinifiedBanner(note, mapsLoaded)
                    Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
                }
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> WasmSizeDiffPanel(sizeModel, leftName, rightName) { selectedFunction = it }
                        1 -> WasmEntityDiffPanel(entityModel) { selectedFunction = it }
                        else -> WasmDispatchDiffPanel(dispatchModel) { selectedFunction = it }
                    }
                }
            }
        },
        southPanel = {
            WasmFunctionWatDetailPanel(selectedFunction, leftBuffer, rightBuffer, leftSymbolMap, rightSymbolMap)
        },
    )
}

/**
 * Loading state for a diff model computed off the EDT and held by [WasmDiffEditorView]
 * across tab switches, so switching tabs never re-runs the parse+diff. Shared by the
 * size and entity panels (both in this package).
 */
internal sealed interface DiffLoadable<out T> {
    data object Loading : DiffLoadable<Nothing>
    data class Error(val message: String) : DiffLoadable<Nothing>
    data class Ready<T>(val value: T) : DiffLoadable<T>
}

/** Shown when either module's import/export names are minified. */
@Composable
private fun MinifiedBanner(note: String, mapsLoaded: Boolean) {
    val headline = if (mapsLoaded) {
        "Public symbols are minified — symbol map loaded; original names restored for matching."
    } else {
        "Public symbols are minified — function matches are structural; treat confidence accordingly."
    }
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(headline, style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
        Text(note, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}
