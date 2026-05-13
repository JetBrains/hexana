package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import org.jetbrains.hexana.HexViewState
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.analytics.HexanaAnalytics
import org.jetbrains.hexana.analytics.HexanaEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import org.jetbrains.hexana.document.DocumentListener
import org.jetbrains.hexana.document.EditableBinaryDocument
import org.jetbrains.hexana.document.editableBinaryDocumentOf
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryVirtualFileProvider
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.ui.panel.WasmPanelFunctions
import org.jetbrains.hexana.ui.panel.WasmPanelCustom
import org.jetbrains.hexana.ui.panel.PathsNavigationState
import org.jetbrains.hexana.ui.panel.WasmPanelDominators
import org.jetbrains.hexana.ui.panel.WasmPanelSize
import org.jetbrains.hexana.ui.panel.WasmPanelMonos
import org.jetbrains.hexana.ui.panel.WasmPanelGarbage
import org.jetbrains.hexana.ui.panel.WasmPanelData
import org.jetbrains.hexana.ui.panel.WasmPanelExports
import org.jetbrains.hexana.ui.panel.WasmPanelImports
import org.jetbrains.hexana.ui.panel.WasmPanelWat
import org.jetbrains.hexana.ui.panel.components.WasmComponentPanelExports
import org.jetbrains.hexana.ui.panel.components.WasmComponentPanelImports
import org.jetbrains.hexana.ui.panel.components.WasmComponentPanelModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.ui.panel.WasmPanelWatVirtualized
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

@Composable
internal fun BinaryEditorView(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
    fileName: String = "",
    parentFileName: String? = null,
    onNavigateToParent: (() -> Unit)? = null,
) {
    // Phase A.1: every opened file gets one editable BinaryDocument shared by
    // the editor's lifecycle. Edits land in this doc; on every commit we
    // rebuild a fresh BinaryProvider from the new bytes and swap liveProvider
    // so all panels recompose against the post-edit state.
    //
    // The original provider may be a file-aware WasmBinaryVirtualFileProvider
    // (which contributes an extra "Wat" text panel + DWARF source-location
    // capability). Rebuilds drop to plain WasmBinaryProvider — those file-
    // editor-bound panels can't be reconstructed without the IDE-side glue
    // that lives in HexanaFileEditor, so they don't survive an edit. The
    // panel list shrinks accordingly; the tab index is clamped so the user's
    // selection never points past the new list.
    val editableDoc: EditableBinaryDocument = remember(binaryProvider) {
        editableBinaryDocumentOf(binaryProvider.bytes.toByteArray())
    }
    var liveProvider by remember(binaryProvider) {
        mutableStateOf<BinaryProvider>(binaryProvider)
    }
    DisposableEffect(editableDoc, binaryProvider) {
        val listener = DocumentListener {
            val newBytes = editableDoc.read(0L, editableDoc.length.toInt())
            // The parser is allowed to reject malformed input by returning
            // WasmParseFailure, but it can also throw on edge cases like
            // negative array sizes when a section's length ULEB decodes to
            // an unsigned value too big for Int. Either way, we must not
            // take the panel down — keep showing the previous live provider
            // and let the user undo / fix the edit.
            val rebuilt = try {
                rebuildProviderFromBytes(binaryProvider, newBytes)
            } catch (t: Throwable) {
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "Parser rejected post-edit bytes; keeping previous live provider",
                    t,
                )
                null
            }
            if (rebuilt != null) liveProvider = rebuilt
        }
        editableDoc.addListener(listener)
        onDispose { editableDoc.removeListener(listener) }
    }

    // State hoisted ABOVE the panel-rebuild boundary so commits don't
    // reset the user's scroll position or fold pattern. The panels array
    // re-`remember`s on every liveProvider change (rebuilding lambdas);
    // anything held inside those lambdas via positional `remember` resets.
    // Lifting it here means each new lambda captures the SAME LazyListState
    // and folded-section MutableState references, preserving state.
    val watVirtualizedListState = rememberLazyListState()
    val watVirtualizedFolded = remember { mutableStateOf<Set<ChunkId>>(emptySet()) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        val pathsNav = remember { PathsNavigationState() }
        val panels = remember(liveProvider) {
            val p = liveProvider.resolvePanels(
                state = state,
                pathsNav = pathsNav,
                editableDoc = editableDoc,
                watVirtualizedListState = watVirtualizedListState,
                watVirtualizedFolded = watVirtualizedFolded,
            )
            mutableStateListOf(*p)
        }

        // If the panel list shrank because of a file-aware → plain provider
        // downgrade, clamp the tab index so we don't render past the end.
        LaunchedEffect(panels.size) {
            if (state.currentState.value.tabIndex >= panels.size && panels.isNotEmpty()) {
                state.setSelectedTab(panels.size - 1)
            }
        }

        val topTabIndex = remember(panels) { panels.indexOfFirst { it.description == "Top" }.takeIf { it >= 0 } }

        LaunchedEffect(panels) {
            state.pendingTabSelection.collect { desc ->
                val idx = panels.indexOfFirst { it.description == desc }
                if (idx >= 0) state.setSelectedTab(idx)
            }
        }

        VerticalSplitPanel(state, northPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                FileDescriptionBar(
                    fileName = fileName,
                    binaryProvider = liveProvider,
                    state = state,
                    onSizeClick = topTabIndex?.let { idx -> {
                        state.setSelectedTab(idx)
                        if (state.topFraction > 0.9f) {
                            state.setTopFraction(0.5f)
                        }
                    }},
                    parentFileName = parentFileName,
                    onNavigateToParent = onNavigateToParent,
                )
                EditingDemoBar(doc = editableDoc, stampKey = editableDoc.modificationStamp)
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HexViewer(
                        binaryProvider = liveProvider,
                        state = state,
                        focusRequester = hexFocusRequester,
                        editableDoc = editableDoc,
                    )
                }
                HexViewStatusBar(state = state, totalBytes = liveProvider.bytes.limit())
            }
        }, southPanel = {
            TabsPanel(panels, state.currentState.value,  onExpand = { expanded ->
                if (expanded) {
                    state.setTopFraction(0f)
                } else {
                    state.setTopFraction(0.5f)
                }
            }, onTabSelect = { index: Int ->
                state.setSelectedTab(index)
                panels.getOrNull(index)?.let { panel ->
                    HexanaAnalytics.client.capture(HexanaEvent.HexTabSelected(panel.description,
                        surface = HexanaEvent.Surface.IDE_PLUGIN))
                }
            })
        })
    }
}

/**
 * Demo strip: button that flips byte 0 to 0xFF, plus Undo / Redo, plus a
 * live readout of byte 0 and the modification stamp. Wired to the shared
 * [EditableBinaryDocument]; the [stampKey] parameter (the document's
 * `modificationStamp` at the time of recomposition) is the recomposition
 * trigger that keeps the readout fresh — recomposition is driven from the
 * parent, which reads the stamp after the live provider rebuild.
 *
 * This strip is a placeholder for the eventual hex-cell edit affordance in
 * [HexViewer]. Once that lands, the demo strip can be removed.
 */
@Composable
private fun EditingDemoBar(doc: EditableBinaryDocument, stampKey: Long) {
    @Suppress("UNUSED_PARAMETER") val unused = stampKey
    val byteZeroHex = if (doc.length > 0L) {
        ((doc.readByte(0L).toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    } else "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Editing demo (A.0):",
            style = TextStyle(fontSize = 11.sp, color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        DefaultButton(
            onClick = {
                if (doc.length > 0L) {
                    doc.edit().apply {
                        replace(0L, 1L, byteArrayOf(0xFF.toByte()))
                        commit()
                    }
                }
            },
        ) { Text("Flip byte 0 → 0xFF") }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = { if (doc.canUndo()) doc.undo() },
            enabled = doc.canUndo(),
        ) { Text("Undo") }
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(
            onClick = { if (doc.canRedo()) doc.redo() },
            enabled = doc.canRedo(),
        ) { Text("Redo") }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "byte0=0x$byteZeroHex  stamp=${doc.modificationStamp}  len=${doc.length}",
            style = TextStyle(fontSize = 10.sp, color = HexanaTheme.hexTextColor),
        )
    }
}

@Composable
private fun TabsPanel(
    panels: List<Panel>,
    state: HexViewState,
    onExpand: (Boolean) -> Unit,
    onTabSelect: (Int) -> Unit,
) {
    if (panels.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    val tabs by remember(state.tabIndex) {
        derivedStateOf {
            panels.mapIndexed { index, panel ->
                TabData.Default(
                    selected = index == state.tabIndex,
                    content = { Text(text = panel.description) },
                    closable = false,
                    onClick = {
                        onTabSelect(index)
                    }
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HexanaTheme.backgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Center the IconButton both vertically and horizontally within its own parent container
            Box(
                modifier = Modifier
                    .width(40.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    expanded = !expanded
                    onExpand(expanded)
                }) {
                    Icon(
                        if (expanded) AllIconsKeys.General.CollapseComponent else AllIconsKeys.General.ExpandComponent,
                        contentDescription = null,
                        iconClass = AllIcons::class.java
                    )
                }
            }
            TabStrip(
                tabs = tabs,
                style = JewelTheme.defaultTabStyle,
            )
        }

        Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal)

        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // The panel list shrinks when liveProvider rebuilds from
            // file-aware → plain (Phase A.1a). The LaunchedEffect clamp
            // runs asynchronously, so on the FIRST recomposition after
            // shrinkage state.tabIndex can still point past the new end.
            // Defensive coerce here so we never throw IndexOutOfBounds —
            // the LaunchedEffect catches up on the next composition.
            val safeIndex = state.tabIndex.coerceIn(0, panels.size - 1)
            panels.get(safeIndex).render()
        }
    }
}


/**
 * Builds a fresh [BinaryProvider] from [newBytes] when the editable document
 * commits a change. File-aware capabilities (the old WAT-text panel that
 * needs `createTextEditor`, DWARF source-location lookups) live on the
 * concrete [WasmBinaryVirtualFileProvider] subclass instantiated in
 * `HexanaFileEditor` and aren't reachable from a freshly-built provider —
 * panels that depend on them are dropped from the post-edit panel list.
 * Returns `null` if [original]'s type isn't supported for rebuild.
 */
private fun rebuildProviderFromBytes(original: BinaryProvider, newBytes: ByteArray): BinaryProvider? {
    val buf = newBytes.toCommonByteBuffer()
    return when (original) {
        is WasmBinaryVirtualFileProvider, is WasmBinaryProvider -> WasmBinaryProvider(buf)
        is WasmComponentBinaryVirtualFileProvider, is WasmComponentBinaryProvider -> WasmComponentBinaryProvider(buf)
        is GenericBinaryProvider -> GenericBinaryProvider(buf)
        else -> null
    }
}

private fun BinaryProvider.resolvePanels(
    state: HexViewStateHolder,
    pathsNav: PathsNavigationState,
    editableDoc: EditableBinaryDocument? = null,
    watVirtualizedListState: LazyListState? = null,
    watVirtualizedFolded: MutableState<Set<ChunkId>>? = null,
): Array<Panel> {
    val panels = when (this) {
        is GenericBinaryProvider -> emptyArray()
        is WasmBinaryVirtualFileProvider -> {
            if (canProvideWasmModule()) arrayOf(
                Panel("Data") { WasmPanelData(this, state) },
                Panel("Exports") { WasmPanelExports(this, state) },
                Panel("Imports") { WasmPanelImports(this, state) },
                Panel("Functions") { WasmPanelFunctions(this, state) },
                Panel("Custom") { WasmPanelCustom(this, state) },
                Panel("Top") { WasmPanelSize(this, state) },
                Panel("Monos") { WasmPanelMonos(this, state) },
                Panel("Garbage") { WasmPanelGarbage(this, state) },
                Panel("Dominators") { WasmPanelDominators(this, state, pathsNav) },
                Panel("Wat") { WasmPanelWat(this, state) },
                Panel("WAT (virtualized)") {
                    WasmPanelWatVirtualized(
                        provider = this,
                        state = state,
                        editableDoc = editableDoc,
                        listState = watVirtualizedListState,
                        foldedSectionsExternal = watVirtualizedFolded,
                    )
                },
                *dwarfPanels(this, state).toTypedArray()
            ) else {
                errorPanel(state)
            }
        }
        is WasmComponentBinaryVirtualFileProvider -> {
            if (canProvideWasmModule()) arrayOf(
                Panel("Exports") { WasmComponentPanelExports(this, state) },
                Panel("Imports") { WasmComponentPanelImports(this, state) },
                Panel("Modules") { WasmComponentPanelModules(this, state) },
                Panel("Top") { WasmPanelSize(this, state) },
                Panel("Wat") { WasmPanelWat(this, state) },
            ) else {
                errorPanel(state)
            }
        }
        is WasmBinaryProvider -> {
            if (canProvideWasmModule()) arrayOf(
                Panel("Data") { WasmPanelData(this, state) },
                Panel("Exports") { WasmPanelExports(this, state) },
                Panel("Imports") { WasmPanelImports(this, state) },
                Panel("Functions") { WasmPanelFunctions(this, state) },
                Panel("Custom") { WasmPanelCustom(this, state) },
                Panel("Top") { WasmPanelSize(this, state) },
                Panel("Monos") { WasmPanelMonos(this, state) },
                Panel("Garbage") { WasmPanelGarbage(this, state) },
                Panel("Dominators") { WasmPanelDominators(this, state, pathsNav) },
                Panel("WAT (virtualized)") {
                    WasmPanelWatVirtualized(
                        provider = this,
                        state = state,
                        editableDoc = editableDoc,
                        listState = watVirtualizedListState,
                        foldedSectionsExternal = watVirtualizedFolded,
                    )
                },
                *dwarfPanels(this, state).toTypedArray()
            ) else emptyArray()
        }
        is WasmComponentBinaryProvider -> {
            if (canProvideWasmModule()) arrayOf(
                Panel("Exports") { WasmComponentPanelExports(this, state) },
                Panel("Imports") { WasmComponentPanelImports(this, state) },
                Panel("Modules") { WasmComponentPanelModules(this, state) },
                Panel("Top") { WasmPanelSize(this, state) },
            ) else emptyArray()
        }

        else -> emptyArray()
    }
    // Wire Functions → Dominators cross-panel navigation + garbage badges
    val domIndex = panels.indexOfFirst { it.description == "Dominators" }
    val funcIndex = panels.indexOfFirst { it.description == "Functions" }
    if (funcIndex >= 0 && this is WasmBinaryProvider) {
        val provider = this
        panels[funcIndex] = Panel("Functions") {
            var unreachableIndices by remember { mutableStateOf(emptySet<Int>()) }
            LaunchedEffect(provider) {
                unreachableIndices = withContext(Dispatchers.IO) {
                    provider.garbageAnalysis?.entries
                        ?.map { it.funcIndex }?.toSet() ?: emptySet()
                }
            }
            WasmPanelFunctions(
                provider, state,
                onShowPaths = if (domIndex >= 0) { fIdx ->
                    pathsNav.targetFuncIndex = fIdx
                    state.setSelectedTab(domIndex)
                } else null,
                unreachableFuncIndices = unreachableIndices,
            )
        }
    }
    // Wire Garbage → Dominators cross-panel navigation
    val garbageIndex = panels.indexOfFirst { it.description == "Garbage" }
    if (garbageIndex >= 0 && domIndex >= 0 && this is WasmBinaryProvider) {
        val provider = this
        panels[garbageIndex] = Panel("Garbage") {
            WasmPanelGarbage(
                provider, state,
                onShowInDominators = { fIdx ->
                    pathsNav.targetFuncIndex = fIdx
                    state.setSelectedTab(domIndex)
                },
                onShowCallers = { fIdx ->
                    pathsNav.targetFuncIndex = fIdx
                    state.setSelectedTab(domIndex)
                },
            )
        }
    }
    return panels
}

private fun BinaryVirtualFileProvider.errorPanel(state: HexViewStateHolder): Array<Panel> = arrayOf(
    Panel("Something is wrong :(") {
        Box {
            Row {
                Column {
                    Text("Hexana does not know how to parse this file  ")
                }
                Column {
                    Link("report an issue", onClick = {
                        BrowserUtil.browse("https://github.com/JetBrains/hexana/issues")
                    })
                }
            }
        }
    },
    Panel("Wat") { WasmPanelWat(this, state) },
)