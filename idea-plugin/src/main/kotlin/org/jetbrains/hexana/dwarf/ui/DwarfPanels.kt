package org.jetbrains.hexana.dwarf.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.dwarf.DwarfAbbrevEntry
import org.jetbrains.hexana.dwarf.DwarfAttribute
import org.jetbrains.hexana.dwarf.debugAbbrevSection
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import androidx.compose.foundation.lazy.LazyListState
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

fun dwarfPanels(provider: BinaryProvider, state: HexViewStateHolder):List<Panel> {
    return when (provider) {
        is WasmBinaryVirtualFileProvider,
        is WasmBinaryProvider -> {
            provider.wasmModule?.let { module ->
                buildList {
                    (module as BaseModule).debugAbbrevSection()?.let {
                        return listOf(
                            Panel(".debug_abbrev") { DebugAbbrevPanel(provider, state) }
                        )
                    }
                }
            } ?: emptyList()
        }
        else -> emptyList()
    }
}

@Composable
fun DebugAbbrevPanel(provider: BinaryProvider, state: HexViewStateHolder) {
    val wasmProvider = provider as? WasmBinaryVirtualFileProvider ?: provider as? WasmBinaryProvider ?: return
    val module = wasmProvider.wasmModule as? BaseModule ?: return
    val abbrevSection = remember(provider) { module.debugAbbrevSection() } ?: return

    val abbrevTables = remember(provider) {
        with(abbrevSection) { wasmProvider.bytes.scanDwarfAbbrev() }
    }
    if (abbrevTables.isEmpty()) return

    val tree = remember(abbrevTables) {
        buildTree {
            for ((tableOffset, entries) in abbrevTables) {
                addNode("0x${tableOffset.toString(16)}") {
                    for (entry in entries) {
                        if (entry.attributes.isEmpty()) {
                            addLeaf(entry)
                        } else {
                            addNode(entry) {
                                for (attr in entry.attributes) {
                                    addLeaf(attr)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val treeState = remember(tree) {
        TreeState(SelectableLazyListState(LazyListState()))
    }

    LazyTree(
        tree = tree,
        treeState = treeState,
        modifier = Modifier.fillMaxSize(),
        onElementClick = { element ->
            when (val data = element.data) {
                is DwarfAbbrevEntry -> state.scrollHexViewTo(data.offset, data.size)
                is DwarfAttribute -> state.scrollHexViewTo(data.offset, data.size)
            }
        },
    ) { element ->
        val label = when (val data = element.data) {
            is String -> "Compilation Unit @ $data"
            is DwarfAbbrevEntry -> "[${data.number}] ${data.tag.name}  ${if (data.hasChildren) "DW_CHILDREN_yes" else "DW_CHILDREN_no"}"
            is DwarfAttribute -> "${data.name} ${data.form.name}" + (data.value?.let { " = ${it.toHexString()}" } ?: "")
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}