package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
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
        VerticalSplitPanel(state, northPanel = {
            HexViewer(binaryProvider.bytes)
        }, southPanel = {
            val panels = remember { mutableStateListOf(*binaryProvider.resolvePanels(state)) }

            TabsPanel(panels) { expanded ->
                if (expanded) {
                    state.setTopFraction(0f)
                } else {
                    state.setTopFraction(0.5f)
                }
            }
        })

    }
}

@Composable
private fun TabsPanel(panels: List<Panel>, onExpand: (Boolean) -> Unit) {
    if (panels.isEmpty()) return

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    val tabs by remember {
        derivedStateOf {
            panels.mapIndexed { index, panel ->
                TabData.Default(
                    selected = index == selectedTabIndex,
                    content = { Text(text = panel.description) },
                    closable = false,
                    onClick = { selectedTabIndex = index }
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
            panels.get(selectedTabIndex).onRender()
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
                WasmPanel.Exports(this),
                WasmPanel.Imports(this),
                WasmPanel.Data(this),
                WasmPanel.Wat(this, state),
            )
        }

        else -> emptyArray()
    }
}