package org.jetbrains.hexana.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmSectionData
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.SelectionMode
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.search.SpeedSearchableLazyColumn
import org.jetbrains.jewel.ui.component.search.highlightSpeedSearchMatches
import org.jetbrains.jewel.ui.component.search.highlightTextSearch
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.treeStyle

@Composable
fun StructureViewList(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
) {
    val focusRequester = remember { FocusRequester() }

    var items by remember { mutableStateOf<List<HexanaNode>?>(null) }
    var itemToSelect by remember { mutableStateOf<HexanaNode?>(null) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            val selection = state.selection
            val byteOffset = if (selection.start.line >= 0 && selection.start.lineOffset >= 0) {
                selection.start.getByteOffset(state.bytesPerRow.value)
            } else {
                state.hoverPosition.getByteOffset(state.bytesPerRow.value)
            }
            var bestSelection: HexanaNode? = null

            val wasmModule = (binaryProvider as? WasmBinaryProvider)?.wasmModule
            val sections = wasmModule?.sections ?: emptyList()
            val res = mutableListOf<HexanaNode>()
            for (section in sections) {
                val name = section.getName()
                val childrenCount = when (section) {
                    is WasmSectionCode -> {
                        ((binaryProvider as? WasmBinaryProvider)?.codeRecords ?: emptyList()).size
                    }

                    is WasmSectionData -> {
                        ((binaryProvider as? WasmBinaryProvider)?.data ?: emptyList()).size
                    }

                    else -> {
                        0
                    }
                }
                val sectionNode = HexanaNode(name, section.sectionRange, isLeaf = false, childrenCount = childrenCount)
                res.add(sectionNode)
                if (sectionNode.range.contains(byteOffset) && (bestSelection == null || bestSelection.range.contains(
                        sectionNode.range
                    ))
                ) {
                    bestSelection = sectionNode
                }
                when (section) {
                    is WasmSectionCode -> {
                        val records = (binaryProvider as? WasmBinaryProvider)?.codeRecords ?: emptyList()
                        records.forEachIndexed { index, record ->
                            val node = HexanaNode(
                                "func $index",
                                record.offset.toInt() until record.offset.toInt() + record.size.toInt(),
                            )
                            res.add(node)
                            if (byteOffset in node.range && bestSelection?.range?.contains(node.range) ?: true) {
                                bestSelection = node
                            }
                        }
                    }

                    is WasmSectionData -> {
                        val records = (binaryProvider as? WasmBinaryProvider)?.data ?: emptyList()
                        records.forEachIndexed { index, record ->
                            val node = HexanaNode(
                                "data $index",
                                record.offset.toInt() until record.offset.toInt() + record.recordLength,
                            )
                            res.add(node)
                            if (byteOffset in node.range && bestSelection?.range?.contains(node.range) ?: true) {
                                bestSelection = node
                            }
                        }
                    }

                    else -> {

                    }
                }
            }
            itemToSelect = bestSelection
            res
        }
    }

    val onItemChosen = fun(item: HexanaNode?) {
        if (item != null) {
            state.setSelection(
                LongRange(
                    item.range.first.toLong(),
                    item.range.last.toLong()
                )
            )
            state.scrollHexViewTo(item.range.first.toLong())
        }
        state.setShowStructurePopup(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val borderColor = if (JewelTheme.isDark) {
            JewelTheme.colorPalette.grayOrNull(3) ?: Color(0xFF393B40)
        } else {
            JewelTheme.colorPalette.grayOrNull(12) ?: Color(0xFFEBECF0)
        }

        if (items == null) {
            Box(
                modifier = Modifier
                    .border(1.dp, borderColor, RoundedCornerShape(2.dp))
                    .size(200.dp, 200.dp)
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

        } else {
            SpeedSearchArea(
                modifier = Modifier
                    .border(1.dp, borderColor, RoundedCornerShape(2.dp))
                    .size(200.dp, 200.dp)
            ) {
                val filteredEntries by remember {
                    derivedStateOf {
                        val search = speedSearchState.searchText
                        val allItems = items ?: emptyList()
                        if (search.isEmpty()) {
                            allItems
                        } else {
                            val matchedItems = mutableListOf<HexanaNode>()
                            var i = 0
                            while (i < allItems.size) {
                                val item = allItems[i]
                                if (item.childrenCount == 0) {
                                    if (item.name.contains(search, true)) {
                                        matchedItems.add(item)
                                    }
                                    i++
                                } else {
                                    val matchedChildren = arrayListOf<HexanaNode>()
                                    for (child in allItems.subList(i + 1, i + item.childrenCount)) {
                                        if (child.name.contains(search, true)) {
                                            matchedChildren.add(child)
                                        }
                                    }
                                    if (item.name.contains(search, true) || !matchedChildren.isEmpty()) {
                                        matchedItems.add(item)
                                        matchedItems.addAll(matchedChildren)
                                    }
                                    i += item.childrenCount
                                }
                            }
                            matchedItems
                        }
                    }
                }

                val selectableListState = remember(filteredEntries) {
                    SelectableLazyListState(LazyListState(0, 0))
                }

                SpeedSearchableLazyColumn(
                    selectionMode = SelectionMode.Single,
                    state = selectableListState,
                    modifier = Modifier
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyUp && it.key == Key.Enter) {
                                val selected = selectableListState.selectedKeys.firstOrNull() as? HexanaNode
                                onItemChosen(selected)
                                true
                            } else {
                                false
                            }
                        }
                        .clickable(true) {
                            val selected = selectableListState.selectedKeys.firstOrNull() as? HexanaNode
                            onItemChosen(selected)
                        }
                        .focusRequester(focusRequester),
                ) {
                    items(filteredEntries, textContent = { it.name }) {
                        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                        SimpleListItem(
                            text = it.name.highlightTextSearch(),
                            selected = isSelected,
                            active = isActive,
                            onTextLayout = { textLayoutResult = it },
                            textModifier = Modifier.highlightSpeedSearchMatches(textLayoutResult),
                            icon = if (it.isLeaf) {
                                AllIconsKeys.Empty
                            } else {
                                JewelTheme.treeStyle.icons.chevron(true, isSelected)
                            },
                        )
                    }
                }

                VerticalScrollbar(selectableListState.lazyListState, modifier = Modifier.align(Alignment.CenterEnd))

                LaunchedEffect(Unit) {
                    itemToSelect?.let {
                        val index = filteredEntries.indexOf(it)
                        if (index >= 0) {
                            selectableListState.selectedKeys = setOf(it)
                            selectableListState.scrollToItem(index)
                        }
                    }

                    focusRequester.requestFocus()
                }
            }
        }
    }

}


data class HexanaNode(
    val name: String,
    val range: IntRange,
    val isLeaf: Boolean = true,
    val childrenCount: Int = 0,
)


fun WasmSection.getName(): String {
    val className = javaClass.simpleName
    return if (className.startsWith("WasmSection")) {
        className.removePrefix("WasmSection")
    } else {
        "Unknown"
    }
}


fun IntRange.contains(other: IntRange): Boolean {
    return other.first in this && other.last in this
}


@Composable
fun StructureViewTree(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
) {
    val focusRequester = remember { FocusRequester() }
    var tree by remember { mutableStateOf<Tree<HexanaNode>?>(null) }
    var itemToSelect by remember { mutableStateOf<Tree.Element<HexanaNode>?>(null) }

    LaunchedEffect(Unit) {
        val selection = state.selection
        val byteOffset = if (selection.start.line >= 0 && selection.start.lineOffset >= 0) {
            selection.start.getByteOffset(state.bytesPerRow.value)
        } else {
            state.hoverPosition.getByteOffset(state.bytesPerRow.value)
        }

        tree = withContext(Dispatchers.IO) {
            buildWasmStructureTree(binaryProvider).also {
                it.roots.forEach { root -> (root as? Tree.Element.Node<HexanaNode>)?.open()}
            }
        }
        var bestSelection: Tree.Element<HexanaNode>? = null
        val nodes = tree?.walkBreadthFirst() ?: emptySequence()
        for (node in nodes) {
            if (node.data.range.contains(byteOffset) && (bestSelection == null || bestSelection.data.range.contains(node.data.range))) {
                bestSelection = node
            }
        }
        itemToSelect = bestSelection
    }

    val onItemChosen = fun(item: HexanaNode?) {
        if (item != null) {
            state.setSelection(
                LongRange(
                    item.range.first.toLong(),
                    item.range.last.toLong()
                )
            )
            state.scrollHexViewTo(item.range.first.toLong())
        }
        state.setShowStructurePopup(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val borderColor = if (JewelTheme.isDark) {
            JewelTheme.colorPalette.grayOrNull(3) ?: Color(0xFF393B40)
        } else {
            JewelTheme.colorPalette.grayOrNull(12) ?: Color(0xFFEBECF0)
        }

        if (tree == null) {
            Box(
                modifier = Modifier
                    .border(1.dp, borderColor, RoundedCornerShape(2.dp))
                    .size(300.dp, 300.dp)
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

        } else {
            SpeedSearchArea(
                modifier = Modifier
                    .border(1.dp, borderColor, RoundedCornerShape(2.dp))
                    .size(300.dp, 300.dp),
            ) {

                val filteredTree by remember(speedSearchState.searchText) {
                    derivedStateOf {
                        val text = speedSearchState.searchText
                        val t = filterTree(tree, text)
                        t
                    }
                }

                val treeState = remember(filteredTree, speedSearchState.searchText) {
                    val state = TreeState(SelectableLazyListState(LazyListState(0, 0)))
                    filteredTree.roots.forEach {
                        (it as? Tree.Element.Node<HexanaNode>)?.let { node ->
                            state.expandNode(node)
                        }
                    }
                    state
                }

                SpeedSearchableTree(
                    modifier = Modifier
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyUp && it.key == Key.Enter) {
                                // TreeState.selectedKeys contains ids instead of nodes like it is done
                                // in the selectable list.
                                // We need to look up the node by the selected id.
                                val selected = treeState.selectedKeys.firstOrNull()?.let { selectedId ->
                                    filteredTree.walkBreadthFirst().find { node ->
                                        node.id == selectedId
                                    }?.data
                                }
                                onItemChosen(selected)
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(focusRequester),
                    tree = filteredTree,
                    treeState = treeState,
                    onElementClick = {
                        onItemChosen(it.data)
                    },
                    nodeText = { it.data.name },
                ) { element ->

                    Box {
                        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                        Text(
                            element.data.name.highlightTextSearch(),
                            Modifier.padding(2.dp).highlightSpeedSearchMatches(textLayoutResult),
                            onTextLayout = { textLayoutResult = it },
                        )
                    }
                }


                VerticalScrollbar(treeState.lazyListState.lazyListState, modifier = Modifier.align(Alignment.CenterEnd))

                LaunchedEffect(Unit) {
                    itemToSelect?.let {
                        val index = tree?.walkDepthFirst()?.indexOf(it) ?: -1
                        if (index >= 0) {
                            treeState.selectedKeys = setOf(it.id)
                            treeState.lazyListState.scrollToItem(index)
                        }
                    }

                    focusRequester.requestFocus()
                }

            }
        }
    }
}


private fun buildWasmStructureTree(binaryProvider: BinaryProvider) = buildTree {
    val wasmModule = (binaryProvider as? WasmBinaryProvider)?.wasmModule
    if (wasmModule != null) {
        for (section in wasmModule.sections) {
            val name = section.getName()
            when (section) {
                is WasmSectionCode -> {
                    addNode(HexanaNode(name, section.sectionRange, isLeaf = false)) {
                        val records = binaryProvider.codeRecords
                        records.mapIndexed { index, record ->
                            val recordName = "func $index"
                            addLeaf(
                                HexanaNode(
                                    recordName,
                                    record.offset.toInt() until record.offset.toInt() + record.size.toInt()
                                )
                            )
                        }
                    }
                }

                is WasmSectionData -> {
                    addNode(HexanaNode(name, section.sectionRange, isLeaf = false)) {
                        val records = binaryProvider.data
                        records.mapIndexed { index, record ->
                            val recordName = "data $index"
                            addLeaf(
                                HexanaNode(
                                    recordName,
                                    record.offset.toInt() until record.offset.toInt() + record.recordLength
                                )
                            )
                        }
                    }
                }

                else -> {
                    addNode(HexanaNode(name, section.sectionRange, isLeaf = false))
                }
            }
        }
    }
}


private fun filterTree(
    tree: Tree<HexanaNode>?,
    searchText: String,
) = buildTree {
    val sections = tree?.roots ?: emptyList()
    for (section in sections) {
        when (section) {
            is Tree.Element.Node<HexanaNode> -> {
                val matchedChildren = section.children?.filter {
                    it.data.name.contains(searchText, true)
                } ?: emptyList()
                if (section.data.name.contains(searchText, true) || matchedChildren.isNotEmpty()) {
                    addNode(section.data) {
                        matchedChildren.forEach {
                            addLeaf(it.data)
                        }
                    }
                }
            }

            else -> {
                if (section.data.name.contains(searchText, true)) {
                    addLeaf(section.data)
                }
            }
        }
    }
}


fun <T> TreeState.expandNode(node: Tree.Element.Node<T>) {
    val nodeId = node.id
    if (!openNodes.contains(nodeId)) {
        toggleNode(nodeId)
    }
}
