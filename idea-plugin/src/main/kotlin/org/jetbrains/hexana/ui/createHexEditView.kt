package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewState
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

@Composable
internal fun BinaryEditorView(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        var bytesPerRow by remember { mutableStateOf(16) }

        VerticalSplitPanel(state, northPanel = {
            HexViewer(binaryProvider, state)
        }, southPanel = {
            val panels = remember { mutableStateListOf(*binaryProvider.resolvePanels(state)) }

            TabsPanel(panels, state.currentState.value,  onExpand = { expanded ->
                if (expanded) {
                    state.setTopFraction(0f)
                } else {
                    state.setTopFraction(0.5f)
                }
            }, onTabSelect = { index: Int ->
                state.setSelectedTab(index)
            })
        })

        if (state.showStructurePopup) {
            Dialog({
                state.setShowStructurePopup(false)
            }, DialogProperties()) {
                Box(modifier = Modifier.background(HexanaTheme.backgroundColor)) {
                    StructureView(binaryProvider = binaryProvider, state = state)
                }
            }
        }
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
            panels.get(state.tabIndex).onRender()
        }
    }
}


internal interface Panel {
    val description: String

    @Composable
    fun onRender()
}

private fun BinaryProvider.resolvePanels(state: HexViewStateHolder): Array<Panel> {
    return when (this) {
        is GenericBinaryProvider -> emptyArray()
        is WasmBinaryVirtualFileProvider -> {
            if (wasmModule == null) return emptyArray() else arrayOf(
                WasmPanel.Data(this, state),
                WasmPanel.Exports(this),
                WasmPanel.Imports(this),
                WasmPanel.Wat(this, state),
            )
        }

        else -> emptyArray()
    }
}