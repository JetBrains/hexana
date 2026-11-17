package org.jetbrains.hexana.dwarf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.shared.DWARF_GROUP_TAB_NAME
import org.jetbrains.hexana.shared.DwarfOutlineNode
import org.jetbrains.hexana.shared.DwarfOutlineSection
import org.jetbrains.hexana.shared.DwarfSearchIndex
import org.jetbrains.hexana.shared.dwarfOutlineSections
import org.jetbrains.hexana.shared.dwarfSearchIndex
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.ui.panel.groupPanels
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.searchMatchStyle

/**
 * The DWARF section tabs, folded behind one parent tab.
 *
 * Row text, node ids and byte ranges all come from `plugins-shared`'s `DwarfOutline.kt`, which the
 * VS Code webview renders from too. This file only draws it.
 */
fun dwarfPanels(provider: BinaryProvider, state: HexViewStateHolder): List<Panel> =
    groupPanels(DWARF_GROUP_TAB_NAME, dwarfOutlineSectionsOf(provider).map { section ->
        Panel(section.name) { DwarfOutlinePanel(section, state) }
    })

/**
 * The DWARF sections this provider has an outline for. Cheap - it probes for section presence and
 * never reads section content, which matters because tab-name discovery calls it too.
 */
internal fun dwarfOutlineSectionsOf(provider: BinaryProvider): List<DwarfOutlineSection> {
    val wasmProvider = calculateProvider(provider) ?: return emptyList()
    val module = wasmProvider.wasmModule as? BaseModule ?: return emptyList()
    return dwarfOutlineSections(WasmDwarfAccessor(module), wasmProvider.bytes)
}

@Composable
private fun DwarfOutlinePanel(section: DwarfOutlineSection, state: HexViewStateHolder) {
    val roots = remember(section) { section.roots() }
    if (roots.isEmpty()) {
        Text("No entries", modifier = Modifier.padding(2.dp))
        return
    }

    val tree = remember(roots) { buildTree { roots.forEach { addDwarfNode(it) } } }
    val treeState = remember(tree) { TreeState(SelectableLazyListState(LazyListState())) }

    DwarfTree(roots = roots, tree = tree, treeState = treeState) { node ->
        node.offset?.let { state.scrollHexViewTo(it, node.size) }
    }
}

private fun TreeGeneratorScope<DwarfOutlineNode>.addDwarfNode(node: DwarfOutlineNode) {
    // The id is passed explicitly: Jewel defaults it to the child index, which is unique only among
    // siblings, so a node would collide with its own first child. Shared ancestor lookup and
    // visibleIndexOf both key on these ids.
    if (!node.hasChildren) {
        addLeaf(node, node.id)
    } else {
        addNode(node, node.id) { node.children().forEach { addDwarfNode(it) } }
    }
}

/**
 * Shared shell for the DWARF trees: a find-and-jump search bar over the whole tree plus a vertical
 * scrollbar.
 */
@Composable
private fun DwarfTree(
    roots: List<DwarfOutlineNode>,
    tree: Tree<DwarfOutlineNode>,
    treeState: TreeState,
    onElementClick: (DwarfOutlineNode) -> Unit = {},
) {
    // Searching subnodes means labelling every row, including the ones inside collapsed subtrees,
    // which forces the model's lazy children for the whole tree - on a debug-heavy binary that is
    // far too much to do on the EDT. It is built once, off-thread, and only when the user actually
    // searches, so merely opening `.debug_line` never decodes a line program.
    //
    // Safe off-thread because the index walks the shared outline model, not the Jewel tree: the
    // model's children providers only read, and each duplicates the byte buffer for its own cursor.
    var searchIndex by remember(roots) { mutableStateOf<DwarfSearchIndex?>(null) }

    // Held as an explicit State: the derived match list below has to read it from inside its own
    // computation, otherwise it captures the first composition's value and never re-evaluates.
    val queryState = remember(tree) { mutableStateOf(TextFieldValue()) }
    val query = queryState.value.text
    var currentMatch by remember(tree) { mutableIntStateOf(0) }
    var pendingScrollTo by remember(tree) { mutableStateOf<String?>(null) }

    val wanted = query.trim().isNotEmpty()
    LaunchedEffect(roots, wanted) {
        if (wanted && searchIndex == null) {
            searchIndex = withContext(Dispatchers.Default) { dwarfSearchIndex(roots) }
        }
    }
    val indexing = wanted && searchIndex == null

    val matches by remember(tree) {
        derivedStateOf {
            val needle = queryState.value.text.trim()
            val index = searchIndex
            if (needle.isEmpty() || index == null) emptyList() else index.idsMatching(needle)
        }
    }

    fun goToMatch(target: Int) {
        val index = searchIndex ?: return
        if (matches.isEmpty()) return
        val position = target.mod(matches.size)
        currentMatch = position
        val id = matches[position]
        // Reveal the match: a hit inside a collapsed subtree is useless until its ancestors open.
        treeState.openNodes = treeState.openNodes + index.ancestorsOf(id)
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
            indexing = indexing,
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
                    highlightMatches(element.data.label, query.trim(), matchBackground, matchForeground),
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
    indexing: Boolean,
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
                            when {
                                indexing -> "Indexing..."
                                matchCount == 0 -> "No matches"
                                else -> "${currentMatch + 1} of $matchCount"
                            },
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
 * Index of [targetId] in the row list LazyTree renders: depth-first, descending only into open
 * nodes. Mirrors Jewel's own flattening, which is private.
 */
private fun visibleIndexOf(
    tree: Tree<DwarfOutlineNode>,
    openNodes: Set<Any>,
    targetId: String,
): Int {
    var index = 0
    fun visit(element: Tree.Element<DwarfOutlineNode>): Boolean {
        if (element.id == targetId) return true
        index++
        if (element is Tree.Element.Node<DwarfOutlineNode> && element.id in openNodes) {
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

private fun calculateProvider(provider: BinaryProvider): WasmBinaryProvider? =
    provider as? WasmBinaryVirtualFileProvider ?: provider as? WasmBinaryProvider
