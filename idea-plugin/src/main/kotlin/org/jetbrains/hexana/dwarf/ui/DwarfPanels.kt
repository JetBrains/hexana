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
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import androidx.compose.foundation.lazy.LazyListState
import org.jetbrains.hexana.dwarf.DebugInformationEntry
import org.jetbrains.hexana.dwarf.DieField
import org.jetbrains.hexana.dwarf.DwarfLineUnitHeader
import org.jetbrains.hexana.dwarf.Form
import org.jetbrains.hexana.dwarf.LneOpcode
import org.jetbrains.hexana.dwarf.LnsOpcode
import org.jetbrains.hexana.dwarf.LnsSpecialOpcode
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

fun dwarfPanels(provider: BinaryProvider, state: HexViewStateHolder):List<Panel> {
    return when (provider) {
        is WasmBinaryVirtualFileProvider,
        is WasmBinaryProvider -> {
            provider.wasmModule?.let { module ->
                buildList {
                    WasmDwarfAccessor(module as BaseModule).debugAbbrevSection()?.let {
                            add(Panel(".debug_abbrev") { DebugAbbrevPanel(provider, state) })
                    }
                    WasmDwarfAccessor(module as BaseModule).debugInfoSection()?.let {
                        add(Panel(".debug_info") { DebugInfoPanel(provider, state) })
                    }
                    WasmDwarfAccessor(module as BaseModule).debugLineSection()?.let {
                        add(Panel(".debug_line") { DebugLinePanel(provider, state) })
                    }
                }
            } ?: emptyList()
        }
        else -> emptyList()
    }
}

@Composable
fun DebugAbbrevPanel(provider: BinaryProvider, state: HexViewStateHolder) {
    val wasmProvider = calculateProvider(provider) ?: return
    val module = wasmProvider.wasmModule as? BaseModule ?: return
    val abbrevSection = remember(provider) { WasmDwarfAccessor(module).debugAbbrevSection() } ?: return

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
            is DwarfAttribute -> "${data.name} ${data.form::class.simpleName}" + (data.value?.let { " = ${it.toHexString()}" } ?: "")
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}


@Composable
fun DebugInfoPanel(provider: BinaryProvider, state: HexViewStateHolder) {
    val wasmProvider = calculateProvider(provider) ?: return
    val module = wasmProvider.wasmModule as? BaseModule ?: return
    val infoSection = remember(provider) { WasmDwarfAccessor(module).debugInfoSection() } ?: return

    val debugInfoHeaders = remember(provider) {
        with(infoSection) {
            wasmProvider.bytes.scanDwarfInfo()
        }
    }
    if (debugInfoHeaders.isEmpty()) return

    fun TreeGeneratorScope<Any>.drawEntry(die: DebugInformationEntry) {
        addNode(die) {
            die.attributes.forEach {
                addLeaf(it)
            }
            die.children.forEach { child ->
                drawEntry(child)
            }
        }
    }

    val tree = remember(debugInfoHeaders) {
        buildTree {
            debugInfoHeaders.forEach { header ->
                drawEntry(header.children[0])
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
                is DebugInformationEntry -> state.scrollHexViewTo(data.offset, data.size)
                is DieField -> state.scrollHexViewTo(data.attribute.offset, data.attribute.size)
            }
        },
    ) { element ->
        val label = when (val data = element.data) {
            is DebugInformationEntry -> "${data.tag} @ 0x${data.offset.toString(16)}"
            is DieField -> {
                when (data.attribute.form) {
                    Form.DW_FORM_strp -> {
                        val stringTable = with(infoSection) {
                            wasmProvider.bytes.getStringTable()
                        }
                        val value = stringTable.find { it.originalOffset == data.value as UInt }?.run {
                            wasmProvider.bytes.str()
                        } ?: "Unknown string"
                        "${data.attribute.name} ${data.attribute.form::class.simpleName} ${data.value}: '$value'"
                    }
                    else -> "${data.attribute.name} ${data.attribute.form::class.simpleName}"
                }
            }
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
fun DebugLinePanel(provider: BinaryProvider, state: HexViewStateHolder) {
    val wasmProvider = calculateProvider(provider) ?: return
    val module = wasmProvider.wasmModule as? BaseModule ?: return
    val lineSection = remember(provider) { WasmDwarfAccessor(module).debugLineSection() } ?: return

    val lineUnitHeaders = remember(provider) {
        with(lineSection) {
            wasmProvider.bytes.scanLineTable()
        }
    }
    if (lineUnitHeaders.isEmpty()) return

    fun TreeGeneratorScope<Any>.drawHeadeEntry(unitHeader: DwarfLineUnitHeader<*>) {
        addNode("debug line [${(unitHeader.contentOffset - lineSection.contentOffset).toHexString()}]") {
            val (version, addressSize) = when (unitHeader){
                is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4 -> 4 to 4
                is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4  -> 4 to 8
                is DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5 -> 5 to 4
                is DwarfLineUnitHeader.Dwarf64LineUnitHeader.V5 -> 5 to 8
            }
            val unitSize = when(val v = unitHeader.unitSize) {
                is UInt -> v.toHexString()
                is ULong -> v.toHexString()
                else -> throw IllegalArgumentException("Unsupported DWARF line: $v")
            }
            addLeaf("total_length: $unitSize")
            addLeaf("format: ${if (addressSize == 8) "DWARF64" else "DWARF32"}")
            addLeaf("version: $version")
            addLeaf("address size: $addressSize")

            val prologLength = when(val v = unitHeader.prologLength) {
                is UInt -> v.toHexString()
                is ULong -> v.toHexString()
                else -> throw IllegalArgumentException("Unsupported DWARF line: $v")
            }
            addLeaf("prolog length: $prologLength")
            addLeaf("maximum_operations_per_instruction: ${unitHeader.maximumOperationsPerInstruction}")
            addLeaf("default_is_stmt: ${unitHeader.defaultIsStatement}")
            addLeaf("line_base: ${unitHeader.lineBase}")
            addLeaf("line_range: ${unitHeader.lineRange}")
            addLeaf("program_offset: ${unitHeader.programOffset}")
            addNode("standard_opcode_lengths") {
                unitHeader.standardOpcodeLengths.forEachIndexed { index, value ->
                    when (index) {
                        LnsOpcode.DW_LNS_copy.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_copy: $value")
                        LnsOpcode.DW_LNS_advance_pc.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_advance_pc: $value")
                        LnsOpcode.DW_LNS_advance_line.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_advance_line: $value")
                        LnsOpcode.DW_LNS_set_file.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_file: $value")
                        LnsOpcode.DW_LNS_set_column.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_column: $value")
                        LnsOpcode.DW_LNS_negate_stmt.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_negate_stmt: $value")
                        LnsOpcode.DW_LNS_set_basic_block.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_basic_block: $value")
                        LnsOpcode.DW_LNS_const_add_pc.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_const_add_pc: $value")
                        LnsOpcode.DW_LNS_fixed_advance_pc.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_fixed_advance_pc: $value")
                        LnsOpcode.DW_LNS_set_prologue_end.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_prologue_end: $value")
                        LnsOpcode.DW_LNS_set_epilogue_begin.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_epilogue_begin: $value")
                        LnsOpcode.DW_LNS_set_isa.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_isa: $value")
                        else -> addLeaf("opcode $index: $value")
                    }
                }
            }
            addNode("line_program") {
                with(unitHeader) {
                    val opcodes = wasmProvider.bytes.lineProgram()
                    opcodes.forEach { opcode ->
                        val offset = (opcode.offset - lineSection.offset).toHexString()
                        when (opcode) {
                            is LnsSpecialOpcode -> addLeaf("$offset: ${opcode.value.toHexString()}")
                            is LnsOpcode -> {
                                addLeaf("$offset: ${opcode::class.simpleName}")
                            }
                            is LneOpcode -> addLeaf("$offset: ${opcode::class.simpleName}")
                        }
                    }
                }
            }
        }
    }

    val tree = remember(lineUnitHeaders) {
        buildTree {
            lineUnitHeaders.forEach { header ->
                drawHeadeEntry(header)
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
                is DebugInformationEntry -> state.scrollHexViewTo(data.offset, data.size)
                is DieField -> state.scrollHexViewTo(data.attribute.offset, data.attribute.size)
            }
        },
    ) { element ->
        val label = when (val data = element.data) {
            else -> data.toString()
        }
        Text(
            label,
            modifier = Modifier.padding(2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

private fun calculateProvider(provider: BinaryProvider): WasmBinaryProvider? =
    provider as? WasmBinaryVirtualFileProvider ?: provider as? WasmBinaryProvider