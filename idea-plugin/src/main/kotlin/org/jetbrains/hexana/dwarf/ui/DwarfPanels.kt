package org.jetbrains.hexana.dwarf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.dwarf.DwarfAbbrevEntry
import org.jetbrains.hexana.dwarf.DwarfAttribute
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.ui.panel.groupPanels
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.Tree
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
import org.jetbrains.hexana.dwarf.StringTableEntry
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.searchMatchStyle

/** Name of the parent tab the per-section DWARF tabs are folded into. */
const val DWARF_GROUP_TAB_NAME = "DWARF"

fun dwarfPanels(provider: BinaryProvider, state: HexViewStateHolder): List<Panel> =
    groupPanels(DWARF_GROUP_TAB_NAME, dwarfSectionPanels(provider, state))

private fun dwarfSectionPanels(provider: BinaryProvider, state: HexViewStateHolder):List<Panel> {
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

/**
 * Shared shell for the DWARF trees: a find-and-jump search bar over the whole tree plus a vertical
 * scrollbar. [label] is used both for the row content and as the search haystack, so what the user
 * sees is exactly what they can search for.
 */
@Composable
private fun DwarfTree(
    tree: Tree<Any>,
    treeState: TreeState,
    label: (Any) -> String,
    onElementClick: (Any) -> Unit = {},
) {
    val currentLabel by rememberUpdatedState(label)
    // Searching subnodes means labelling every row, including the ones inside collapsed subtrees,
    // which forces Jewel's lazy children generators for the whole tree. That is why the index is
    // built on first use rather than when the tab opens: a `.debug_line` program is only parsed if
    // someone actually searches it. The build runs on the UI thread on purpose — a background build
    // would race LazyTree over the same lazily-evaluated `children` fields.
    val searchIndex = remember(tree) { lazy { buildSearchIndex(tree) { currentLabel(it) } } }

    // Held as an explicit State: the derived match list below has to read it from inside its own
    // computation, otherwise it captures the first composition's value and never re-evaluates.
    val queryState = remember(tree) { mutableStateOf(TextFieldValue()) }
    val query = queryState.value.text
    var currentMatch by remember(tree) { mutableIntStateOf(0) }
    var pendingScrollTo by remember(tree) { mutableStateOf<Any?>(null) }

    val matches by remember(tree) {
        derivedStateOf {
            val needle = queryState.value.text.trim()
            if (needle.isEmpty()) emptyList() else searchIndex.value.idsMatching(needle)
        }
    }

    fun goToMatch(target: Int) {
        if (matches.isEmpty()) return
        val index = target.mod(matches.size)
        currentMatch = index
        val id = matches[index]
        // Reveal the match: a hit inside a collapsed subtree is useless until its ancestors open.
        treeState.openNodes = treeState.openNodes + searchIndex.value.ancestorsOf(id)
        treeState.selectedKeys = setOf(id)
        pendingScrollTo = id
    }

    // Jump to the first hit as the query is typed, the way Find does.
    LaunchedEffect(matches) {
        currentMatch = 0
        if (matches.isNotEmpty()) goToMatch(0)
    }

    // Deferred to an effect so the tree has recomposed with the freshly opened ancestors, and the
    // row we want to scroll to actually exists in the flattened list.
    LaunchedEffect(pendingScrollTo) {
        val id = pendingScrollTo ?: return@LaunchedEffect
        val index = visibleIndexOf(tree, treeState.openNodes, id)
        if (index >= 0) treeState.lazyListState.scrollToItem(index)
        pendingScrollTo = null
    }

    val matchStyle = JewelTheme.searchMatchStyle.colors
    val matchBackground = matchStyle.startBackground
    val matchForeground = matchStyle.foreground

    Column(modifier = Modifier.fillMaxSize()) {
        DwarfSearchBar(
            query = queryState.value,
            onQueryChange = { queryState.value = it },
            matchCount = matches.size,
            currentMatch = currentMatch,
            onNavigate = { delta -> goToMatch(currentMatch + delta) },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyTree(
                tree = tree,
                treeState = treeState,
                modifier = Modifier.fillMaxSize(),
                onElementClick = { onElementClick(it.data) },
            ) { element ->
                Text(
                    highlightMatches(currentLabel(element.data), query.trim(), matchBackground, matchForeground),
                    modifier = Modifier.padding(2.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                )
            }

            VerticalScrollbar(
                treeState.lazyListState.lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun DwarfSearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onNavigate: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Enter -> {
                        onNavigate(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    event.key == Key.Escape && query.text.isNotEmpty() -> {
                        onQueryChange(TextFieldValue())
                        true
                    }
                    else -> false
                }
            },
            placeholder = { Text("Search...") },
            leadingIcon = {
                Icon(
                    AllIconsKeys.Actions.Search,
                    contentDescription = null,
                    iconClass = AllIcons::class.java,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.text.isNotBlank()) {
                        Text(
                            if (matchCount == 0) "No matches" else "${currentMatch + 1} of $matchCount",
                            style = TextStyle(color = Color.Gray),
                        )
                    }
                    if (query.text.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange(TextFieldValue()) }) {
                            Icon(
                                AllIconsKeys.Actions.Close,
                                contentDescription = "Clear search",
                                iconClass = AllIcons::class.java,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            },
        )
        IconButton(onClick = { onNavigate(-1) }, enabled = matchCount > 0) {
            Icon(
                AllIconsKeys.Actions.PreviousOccurence,
                contentDescription = "Previous match",
                iconClass = AllIcons::class.java,
            )
        }
        IconButton(onClick = { onNavigate(1) }, enabled = matchCount > 0) {
            Icon(
                AllIconsKeys.Actions.NextOccurence,
                contentDescription = "Next match",
                iconClass = AllIcons::class.java,
            )
        }
    }
}

private fun highlightMatches(text: String, needle: String, background: Color, foreground: Color): AnnotatedString {
    if (needle.isEmpty()) return AnnotatedString(text)
    var from = text.indexOf(needle, ignoreCase = true)
    if (from < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (from >= 0) {
            append(text, cursor, from)
            withStyle(SpanStyle(color = foreground, background = background)) {
                append(text, from, from + needle.length)
            }
            cursor = from + needle.length
            from = text.indexOf(needle, cursor, ignoreCase = true)
        }
        append(text, cursor, text.length)
    }
}

/**
 * Every row of a tree, collapsed subtrees included, with the parent links needed to reveal a hit.
 * Ids are the ones handed to `addNode`/`addLeaf`, so they address rows in the live tree.
 */
private class DwarfSearchIndex(
    private val ids: List<Any>,
    private val labels: List<String>,
    private val parents: Map<Any, Any>,
) {
    /** Ids of the rows whose label contains [needle], in tree order. */
    fun idsMatching(needle: String): List<Any> = buildList {
        for (i in ids.indices) {
            if (labels[i].contains(needle, ignoreCase = true)) add(ids[i])
        }
    }

    fun ancestorsOf(id: Any): List<Any> = generateSequence(parents[id]) { parents[it] }.toList()
}

private fun buildSearchIndex(tree: Tree<Any>, label: (Any) -> String): DwarfSearchIndex {
    val ids = ArrayList<Any>()
    val labels = ArrayList<String>()
    val parents = HashMap<Any, Any>()

    fun visit(element: Tree.Element<Any>, parentId: Any?) {
        val id = element.id
        ids.add(id)
        labels.add(label(element.data))
        if (parentId != null) parents[id] = parentId
        if (element is Tree.Element.Node<Any>) {
            // `children` stays null until open() runs the generator - reading it is not enough.
            // Jewel's own walkDepthFirst() would do this, but it drives an insert-at-head queue
            // that degrades to O(n^2) on wide levels, and a line program is one very wide level.
            element.open()
            element.children.orEmpty().forEach { visit(it, id) }
        }
    }
    tree.roots.forEach { visit(it, null) }

    return DwarfSearchIndex(ids, labels, parents)
}

/**
 * Index of [targetId] in the row list LazyTree renders: depth-first, descending only into open
 * nodes. Mirrors Jewel's own flattening, which is private.
 */
private fun visibleIndexOf(tree: Tree<Any>, openNodes: Set<Any>, targetId: Any): Int {
    var index = 0
    fun visit(element: Tree.Element<Any>): Boolean {
        if (element.id == targetId) return true
        index++
        if (element is Tree.Element.Node<Any> && element.id in openNodes) {
            for (child in element.children.orEmpty()) {
                if (visit(child)) return true
            }
        }
        return false
    }
    for (root in tree.roots) {
        if (visit(root)) return index
    }
    return -1
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
                val tableLabel = "0x${tableOffset.toString(16)}"
                addNode(tableLabel, tableLabel) {
                    for (entry in entries) {
                        if (entry.attributes.isEmpty()) {
                            addLeaf(entry, entry)
                        } else {
                            addNode(entry, entry) {
                                for (attr in entry.attributes) {
                                    addLeaf(attr, attr)
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

    DwarfTree(
        tree = tree,
        treeState = treeState,
        label = { data ->
            when (data) {
                is String -> "Compilation Unit @ $data"
                is DwarfAbbrevEntry -> "[${data.number}] ${data.tag.name}  ${if (data.hasChildren) "DW_CHILDREN_yes" else "DW_CHILDREN_no"}"
                is DwarfAttribute -> "${data.name} ${data.form::class.simpleName}" + (data.value?.let { " = ${it.toHexString()}" } ?: "")
                else -> data.toString()
            }
        },
        onElementClick = { data ->
            when (data) {
                is DwarfAbbrevEntry -> state.scrollHexViewTo(data.offset, data.size)
                is DwarfAttribute -> state.scrollHexViewTo(data.offset, data.size)
            }
        },
    )
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

    // Indexed once: the label callback runs for every visible row and for every row in the
    // speed-search haystack, so a linear scan of the string table per row would be quadratic.
    val stringEntries: Map<UInt, StringTableEntry> = remember(provider) {
        if (WasmDwarfAccessor(module).debugStrSection() == null) {
            emptyMap()
        } else {
            with(infoSection) { wasmProvider.bytes.getStringTable() }.associateBy { it.originalOffset }
        }
    }

    fun TreeGeneratorScope<Any>.drawEntry(die: DebugInformationEntry) {
        addNode(die, die) {
            die.attributes.forEach {
                addLeaf(it, it)
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

    DwarfTree(
        tree = tree,
        treeState = treeState,
        label = { data ->
            when (data) {
                is DebugInformationEntry -> "${data.tag} @ 0x${data.offset.toString(16)}"
                is DieField -> {
                    when (data.attribute.form) {
                        Form.DW_FORM_strp -> {
                            val value = stringEntries[data.value as? UInt]
                                ?.run { wasmProvider.bytes.str() }
                                ?: "Unknown string"
                            "${data.attribute.name} ${data.attribute.form::class.simpleName} ${data.value}: '$value'"
                        }
                        else -> "${data.attribute.name} ${data.attribute.form::class.simpleName}"
                    }
                }
                else -> data.toString()
            }
        },
        onElementClick = { data ->
            when (data) {
                is DebugInformationEntry -> state.scrollHexViewTo(data.offset, data.size)
                is DieField -> state.scrollHexViewTo(data.attribute.offset, data.attribute.size)
            }
        },
    )
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

    // Jewel's default node ids are only unique among siblings, so a node collides with its own
    // first child, which breaks expansion and speed-search navigation on this tree of plain
    // strings. Give every row a stable, path-derived id instead.
    fun TreeGeneratorScope<Any>.drawHeadeEntry(unitHeader: DwarfLineUnitHeader<*>, unitId: String) {
        addNode("debug line [${(unitHeader.contentOffset - lineSection.contentOffset).toHexString()}]", unitId) {
            var fieldIndex = 0
            fun fieldId() = "$unitId/field/${fieldIndex++}"
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
            addLeaf("total_length: $unitSize", fieldId())
            addLeaf("format: ${if (addressSize == 8) "DWARF64" else "DWARF32"}", fieldId())
            addLeaf("version: $version", fieldId())
            addLeaf("address size: $addressSize", fieldId())

            val prologLength = when(val v = unitHeader.prologLength) {
                is UInt -> v.toHexString()
                is ULong -> v.toHexString()
                else -> throw IllegalArgumentException("Unsupported DWARF line: $v")
            }
            addLeaf("prolog length: $prologLength", fieldId())
            addLeaf("maximum_operations_per_instruction: ${unitHeader.maximumOperationsPerInstruction}", fieldId())
            addLeaf("default_is_stmt: ${unitHeader.defaultIsStatement}", fieldId())
            addLeaf("line_base: ${unitHeader.lineBase}", fieldId())
            addLeaf("line_range: ${unitHeader.lineRange}", fieldId())
            addLeaf("program_offset: ${unitHeader.programOffset}", fieldId())
            addNode("standard_opcode_lengths", "$unitId/opcode_lengths") {
                unitHeader.standardOpcodeLengths.forEachIndexed { index, value ->
                    val id = "$unitId/opcode_lengths/$index"
                    when (index) {
                        LnsOpcode.DW_LNS_copy.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_copy: $value", id)
                        LnsOpcode.DW_LNS_advance_pc.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_advance_pc: $value", id)
                        LnsOpcode.DW_LNS_advance_line.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_advance_line: $value", id)
                        LnsOpcode.DW_LNS_set_file.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_file: $value", id)
                        LnsOpcode.DW_LNS_set_column.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_column: $value", id)
                        LnsOpcode.DW_LNS_negate_stmt.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_negate_stmt: $value", id)
                        LnsOpcode.DW_LNS_set_basic_block.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_basic_block: $value", id)
                        LnsOpcode.DW_LNS_const_add_pc.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_const_add_pc: $value", id)
                        LnsOpcode.DW_LNS_fixed_advance_pc.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_fixed_advance_pc: $value", id)
                        LnsOpcode.DW_LNS_set_prologue_end.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_prologue_end: $value", id)
                        LnsOpcode.DW_LNS_set_epilogue_begin.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_epilogue_begin: $value", id)
                        LnsOpcode.DW_LNS_set_isa.standardOpcodeLengthsEncoding -> addLeaf("DW_LNS_set_isa: $value", id)
                        else -> addLeaf("opcode $index: $value", id)
                    }
                }
            }
            addNode("line_program", "$unitId/line_program") {
                with(unitHeader) {
                    val opcodes = wasmProvider.bytes.lineProgram()
                    opcodes.forEachIndexed { index, opcode ->
                        val id = "$unitId/line_program/$index"
                        val offset = (opcode.offset - lineSection.offset).toHexString()
                        when (opcode) {
                            is LnsSpecialOpcode -> addLeaf("$offset: ${opcode.value.toHexString()}", id)
                            is LnsOpcode -> {
                                addLeaf("$offset: ${opcode::class.simpleName}", id)
                            }
                            is LneOpcode -> addLeaf("$offset: ${opcode::class.simpleName}", id)
                        }
                    }
                }
            }
        }
    }

    val tree = remember(lineUnitHeaders) {
        buildTree {
            lineUnitHeaders.forEachIndexed { index, header ->
                drawHeadeEntry(header, "unit/$index")
            }
        }
    }


    val treeState = remember(tree) {
        TreeState(SelectableLazyListState(LazyListState()))
    }

    DwarfTree(
        tree = tree,
        treeState = treeState,
        label = { data -> data.toString() },
        onElementClick = { data ->
            when (data) {
                is DebugInformationEntry -> state.scrollHexViewTo(data.offset, data.size)
                is DieField -> state.scrollHexViewTo(data.attribute.offset, data.attribute.size)
            }
        },
    )
}

private fun calculateProvider(provider: BinaryProvider): WasmBinaryProvider? =
    provider as? WasmBinaryVirtualFileProvider ?: provider as? WasmBinaryProvider
