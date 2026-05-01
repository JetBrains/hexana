package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import org.jetbrains.hexana.HexViewState
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.analytics.HexanaAnalytics
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
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
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        VerticalSplitPanel(state, northPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                FileDescriptionBar(fileName = fileName, binaryProvider = binaryProvider, state = state)
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HexViewer(binaryProvider, state, focusRequester = hexFocusRequester)
                }
                HexViewStatusBar(state = state, totalBytes = binaryProvider.bytes.limit())
            }
        }, southPanel = {
            val pathsNav = remember { PathsNavigationState() }
            val panels = remember {
                val p = binaryProvider.resolvePanels(state, pathsNav)
                mutableStateListOf(*p)
            }

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
            panels.get(state.tabIndex).render()
        }
    }
}


private fun BinaryProvider.resolvePanels(state: HexViewStateHolder, pathsNav: PathsNavigationState): Array<Panel> {
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