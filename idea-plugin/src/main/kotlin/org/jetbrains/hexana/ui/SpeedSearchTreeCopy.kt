package org.jetbrains.hexana.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.*
import org.jetbrains.jewel.foundation.lazy.DefaultMacOsSelectableColumnKeybindings.Companion.isSelectAll
import org.jetbrains.jewel.foundation.lazy.DefaultMacOsSelectableColumnKeybindings.Companion.isSelectNextItem
import org.jetbrains.jewel.foundation.lazy.DefaultMacOsSelectableColumnKeybindings.Companion.isSelectPreviousItem
import org.jetbrains.jewel.foundation.lazy.DefaultSelectableOnKeyEvent.Companion.onSelectAll
import org.jetbrains.jewel.foundation.lazy.DefaultSelectableOnKeyEvent.Companion.onSelectNextItem
import org.jetbrains.jewel.foundation.lazy.DefaultSelectableOnKeyEvent.Companion.onSelectPreviousItem
import org.jetbrains.jewel.foundation.lazy.tree.*
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.ProvideSearchMatchState
import org.jetbrains.jewel.ui.component.SpeedSearchScope
import org.jetbrains.jewel.ui.component.SpeedSearchState
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.theme.treeStyle
import kotlin.math.absoluteValue


@Composable
@ExperimentalJewelApi
@ApiStatus.Experimental
public fun <T> SpeedSearchScope.SpeedSearchableTree(
    tree: Tree<T>,
    nodeText: (Tree.Element<T>) -> String?,
    modifier: Modifier = Modifier,
    treeState: TreeState = rememberTreeState(),
    onElementClick: (Tree.Element<T>) -> Unit = {},
    onElementDoubleClick: (Tree.Element<T>) -> Unit = {},
    onSelectionChange: (List<Tree.Element<T>>) -> Unit = {},
    keyActions: KeyActions = DefaultTreeViewKeyActions(treeState),
    style: LazyTreeStyle = JewelTheme.treeStyle,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    nodeContent: @Composable (SelectableLazyItemScope.(Tree.Element<T>) -> Unit),
) {
    val currentTree = rememberUpdatedState(tree)
    val currentNodeText = rememberUpdatedState(nodeText)

    val currentTreeToList = remember {
        derivedStateOf { currentTree.value.toList(currentNodeText.value, treeState.openNodes) }
    }

    val speedSearchKeyActions =
        remember(keyActions) { SpeedSearchableLazyColumnKeyActions(keyActions, speedSearchState) }

    LazyTree(
        tree = tree,
        modifier = modifier.onPreviewKeyEvent(::processKeyEvent),
        onElementClick = onElementClick,
        treeState = treeState,
        onElementDoubleClick = onElementDoubleClick,
        onSelectionChange = onSelectionChange,
        keyActions = speedSearchKeyActions,
        style = style,
        interactionSource = interactionSource,
    ) {
        ProvideSearchMatchState(speedSearchState, nodeText(it), searchMatchStyle) { nodeContent(it) }
    }

    SpeedSearchableLazyColumnScrollEffect(
        treeState.lazyListState,
        speedSearchState,
        currentTreeToList.value.second,
        dispatcher,
    )

    LaunchedEffect(dispatcher) {
        val entriesState = MutableStateFlow(emptyList<String?>())

        val entriesFlow = snapshotFlow { currentTreeToList.value.first }
        async(dispatcher) { entriesFlow.collect(entriesState::emit) }

        speedSearchState.attach(entriesState, dispatcher)
    }
}

private fun <T> Tree<T>.toList(
    nodeText: (Tree.Element<T>) -> String?,
    openNodes: Set<Any>,
): Pair<List<String?>, List<Any?>> {
    val texts = mutableListOf<String?>()
    val keys = mutableListOf<Any?>()

    val nodes = walkDepthFirst().filter { it.parent == null || it.parent?.id in openNodes }
    for (node in nodes) {
        texts.add(nodeText(node))
        keys.add(node.id)
    }

    return (texts to keys)
}

internal class SpeedSearchableLazyColumnKeyActions(
    private val delegate: KeyActions,
    private val speedSearchState: SpeedSearchState,
) : KeyActions by delegate {
    override fun handleOnKeyEvent(
        event: KeyEvent,
        keys: List<SelectableLazyListKey>,
        state: SelectableLazyListState,
        selectionMode: SelectionMode,
    ): KeyEvent.() -> Boolean = lambda@{
        val initialIndex = state.lastActiveItemIndex
        if (type != KeyEventType.KeyUp && initialIndex != null) {
            when {
                isSelectAll && selectionMode == SelectionMode.Multiple -> {
                    if (!speedSearchState.isVisible) {
                        onSelectAll(keys, state)
                    }
                    return@lambda true
                }
                isSelectNextItem && speedSearchState.isVisible -> {
                    onSelectNextItem(keys, state, speedSearchState.matchingIndexes.filter { it > initialIndex })
                    return@lambda true
                }
                isSelectPreviousItem && speedSearchState.isVisible -> {
                    onSelectPreviousItem(
                        keys,
                        state,
                        speedSearchState.matchingIndexes.filter { it < initialIndex }.asReversed(),
                    )
                    return@lambda true
                }
                else -> Unit
            }
        }

        delegate.handleOnKeyEvent(event, keys, state, selectionMode).invoke(this)
    }
}


@Composable
internal fun SpeedSearchableLazyColumnScrollEffect(
    selectableLazyListState: SelectableLazyListState,
    speedSearchState: SpeedSearchState,
    keys: List<Any?>,
    dispatcher: CoroutineDispatcher,
) {
    val currentKeys = rememberUpdatedState(keys)
    LaunchedEffect(selectableLazyListState, speedSearchState, dispatcher) {
        snapshotFlow { speedSearchState.matchingIndexes }
            .distinctUntilChanged()
            .filter { it.isNotEmpty() }
            .flowOn(dispatcher)
            .onEach { indexesMatchingSearchText ->
                val keyValues = currentKeys.value
                val visibleItemIndexes = selectableLazyListState.visibleItemsRange
                val indexesForSelectedKeys = keyValues.indicesForKeys(selectableLazyListState.selectedKeys)

                val matchingSelectionIndex =
                    indexesForSelectedKeys.firstOrNull { indexesMatchingSearchText.binarySearch(it) >= 0 }

                // If any of the selected items match the filter, and it is visible, just skip. But if it's not visible,
                // scroll to it.
                if (matchingSelectionIndex != null) {
                    if (matchingSelectionIndex !in visibleItemIndexes) {
                        selectableLazyListState.scrollToItem(matchingSelectionIndex)
                    }

                    return@onEach
                }

                // If any of the visible items match the filter, just select the one closest to any of the selected
                // items
                val indexOfVisibleMatches =
                    visibleItemIndexes.filter { indexesMatchingSearchText.binarySearch(it) >= 0 }

                val bestVisibleMatch =
                    indexOfVisibleMatches
                        .mapNotNull { visibleMatchIndex ->
                            indexesForSelectedKeys
                                .minByOrNull { (visibleMatchIndex - it).absoluteValue }
                                ?.let { visibleMatchIndex to it }
                        }
                        .minByOrNull { (it.first - it.second).absoluteValue }
                        ?.first

                if (bestVisibleMatch != null) {
                    selectableLazyListState.selectedKeys = setOfNotNull(keyValues.getOrNull(bestVisibleMatch))
                    selectableLazyListState.lastActiveItemIndex = bestVisibleMatch
                    return@onEach
                }

                // If no items are visible or selected, scroll to the best match after the last visible item
                val indexBeforeVisibleItems = (0 until visibleItemIndexes.first)
                val indexesAfterVisibleItems =
                    (visibleItemIndexes.last until selectableLazyListState.lazyListState.layoutInfo.totalItemsCount)

                // First item after the visible items, or before it (ordered in order)
                val bestMatch =
                    indexesMatchingSearchText.firstOrNull { it in indexesAfterVisibleItems }
                        ?: indexesMatchingSearchText.firstOrNull { it in indexBeforeVisibleItems }

                if (bestMatch != null) {
                    selectableLazyListState.selectedKeys = setOfNotNull(keyValues.getOrNull(bestMatch))
                    selectableLazyListState.scrollToItem(bestMatch)
                }
            }
            .collect()
    }
}

private fun List<Any?>.indicesForKeys(keysToSearch: Collection<Any?>): Set<Int> {
    if (keysToSearch.isEmpty()) return emptySet()

    return buildSet {
        for (index in this@indicesForKeys.indices) {
            val keyForIndex = this@indicesForKeys[index]
            if (keysToSearch.contains(keyForIndex)) {
                add(index)

                // After finding all keys, can stop search
                if (keysToSearch.size == size) break
            }
        }
    }
}
