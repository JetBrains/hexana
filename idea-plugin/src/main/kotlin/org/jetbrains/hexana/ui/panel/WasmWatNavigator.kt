package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.WasmChunkTree
import org.jetbrains.hexana.providers.format.WasmSectionKinds
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

/**
 * Destination of a navigation step. Currently always a code-section element;
 * `rowIdx == null` means "focus the first editable row in the chunk".
 */
internal data class NavTarget(val chunkId: ChunkId, val rowIdx: Int? = null)

/**
 * Browser-style back / forward stacks plus the current position. Held in a
 * panel slot that survives `provider` rebuilds so navigation history is not
 * erased by edit commits.
 */
internal class NavHistoryState {
    val back: SnapshotStateList<NavTarget> = mutableStateListOf()
    val forward: SnapshotStateList<NavTarget> = mutableStateListOf()
    var current: NavTarget? by mutableStateOf(null)
}

/**
 * Drives "jump to function" in [WasmPanelWatVirtualized]. Bundles the
 * collaborators needed to:
 *
 *  1. Resolve a global funcidx to the chunk that holds its body.
 *  2. Unfold the Code section if the user collapsed it.
 *  3. Compute the [LazyListState] item index for that chunk in the current
 *     fold state, and animate-scroll there.
 *  4. Focus the chunk's first editable row once it composes.
 *
 * Imported funcs have no body — calls to them resolve to a no-op (the
 * navigator returns `false`). Same for out-of-range funcidx.
 *
 * Mirrors the inline append+redirect scroll logic that previously lived
 * in `LaunchedEffect(totalCodeChunks)` so both code paths converge on one
 * scroll-and-focus implementation.
 */
internal class WasmWatNavigator(
    private val chunkTree: WasmChunkTree,
    private val provider: WasmBinaryProvider,
    private val listState: LazyListState,
    private val foldedSectionsState: MutableState<Set<ChunkId>>,
    private val focusMap: SnapshotStateMap<Pair<ChunkId, Int>, FocusRequester>,
    private val scope: CoroutineScope,
    private val history: NavHistoryState,
    /**
     * Number of LazyColumn items a chunk currently occupies: 1 for normal
     * chunks, but the decoded row count for a big CODE function rendered as
     * per-row items (see BIG_FUNC_THRESHOLD). Keeps the item-index walk below
     * correct past row-virtualized functions.
     */
    private val itemCountForChunk: (ChunkId) -> Int = { 1 },
    /**
     * Decodes + caches a big function's rows before we scroll to it, so the
     * target lands on real per-row items (not the placeholder) and the focus
     * poll can find the row. No-op for non-big / already-decoded chunks.
     */
    private val ensureDecoded: suspend (ChunkId) -> Unit = {},
) {
    /**
     * Scroll to and focus the body of function [funcIdx] (global function
     * index, including imports). Returns `true` if a target chunk was found;
     * `false` if the index is out of range or refers to an imported function.
     *
     * When [recordHistory] is true (the default), the current position is
     * pushed onto the back stack and the forward stack is cleared — standard
     * browser semantics. [back] and [forward] pass `false` to avoid
     * re-recording.
     */
    /**
     * Convenience for "go to the function body" — equivalent to
     * `gotoFunc(funcIdx, WasmSectionKinds.CODE)`. Kept for the
     * append+redirect path which always lands in the Code section.
     */
    fun gotoFunc(funcIdx: Int, recordHistory: Boolean = true): Boolean =
        gotoFunc(funcIdx, WasmSectionKinds.CODE, recordHistory)

    /**
     * Jump directly to [chunkId] (any chunk: section header, code entry,
     * function entry, ...). Used by the section-shortcut bar and other
     * navigation surfaces that operate on chunk ids rather than funcidx.
     */
    fun gotoChunk(chunkId: ChunkId, recordHistory: Boolean = true): Boolean {
        val target = NavTarget(chunkId = chunkId, rowIdx = null)
        if (chunkTree.chunkForId(chunkId) == null) {
            return false
        }
        if (recordHistory) {
            history.current?.let { history.back += it }
            history.forward.clear()
        }
        history.current = target
        scope.launch { scrollAndFocus(target) }
        return true
    }

    /**
     * Scroll-and-focus the entry for [funcIdx] in [section]. `section` is
     * either [WasmSectionKinds.CODE] (the function body, the
     * "implementation") or [WasmSectionKinds.FUNCTION] (the declaration
     * row that maps funcidx → typeidx). Returns `false` for imported
     * funcs, out-of-range indices, or sections that don't carry per-func
     * chunks.
     */
    fun gotoFunc(funcIdx: Int, section: SectionKind, recordHistory: Boolean = true): Boolean {
        val target = resolveFuncTarget(funcIdx, section)
        if (target == null) return false
        if (recordHistory) {
            history.current?.let { history.back += it }
            history.forward.clear()
        }
        history.current = target
        scope.launch { scrollAndFocus(target) }
        return true
    }

    fun back(): Boolean {
        val prev = history.back.removeLastOrNull()
        if (prev == null) return false
        history.current?.let { history.forward += it }
        history.current = prev
        scope.launch { scrollAndFocus(prev) }
        return true
    }

    fun forward(): Boolean {
        val next = history.forward.removeLastOrNull()
        if (next == null) return false
        history.current?.let { history.back += it }
        history.current = next
        scope.launch { scrollAndFocus(next) }
        return true
    }

    val canBack: Boolean get() = history.back.isNotEmpty()
    val canForward: Boolean get() = history.forward.isNotEmpty()

    /**
     * Resolve a global funcidx to a [NavTarget] pointing at the code-section
     * chunk that owns the function's body. Imported funcs and out-of-range
     * indices return `null`.
     */
    private fun resolveFuncTarget(funcIdx: Int, section: SectionKind): NavTarget? {
        val indexInSection = funcIdx - provider.importFuncCount
        if (indexInSection < 0) {
            // Imported function — neither the Function nor the Code section
            // declares it. The Import section is the only declaration site,
            // so redirect there regardless of the requested `section`.
            val target = resolveImportFuncTarget(funcIdx)
            return target
        }
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == section && c.indexInSection == indexInSection) {
                return NavTarget(chunkId = c.id, rowIdx = null)
            }
        }
        return null
    }

    /**
     * Map an imported global funcidx to the chunk for its row in the Import
     * section. Import-section records include functions, tables, memories,
     * and globals; function imports are a subset. The Nth function import
     * (0-based by funcidx) lives at the Nth IMPORT chunk whose record type
     * is [WasmTypeIndexExternalType].
     */
    private fun resolveImportFuncTarget(funcIdx: Int): NavTarget? {
        val recordIdx = importFuncRecordIndices.getOrNull(funcIdx)
        if (recordIdx == null) {
            return null
        }
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == WasmSectionKinds.IMPORT && c.indexInSection == recordIdx) {
                return NavTarget(chunkId = c.id, rowIdx = null)
            }
        }
        return null
    }

    /**
     * Indices into the Import-section record list that correspond to
     * function imports — `importFuncRecordIndices[k]` is the record index
     * of the k-th function import (i.e. the chunk's `indexInSection`).
     * Computed once per navigator.
     */
    private val importFuncRecordIndices: List<Int> by lazy {
        val section = provider.wasmModule?.importSection ?: return@lazy emptyList()
        val records = with(section) { provider.bytes.getImportRecords() }
        records.withIndex()
            .filter { (_, r) -> r.type is WasmTypeIndexExternalType }
            .map { it.index }
    }

    /**
     * Unfold the Code section if needed, walk the chunk tree to compute the
     * [LazyListState] item index of [target]'s chunk in the current fold
     * state, animate-scroll there, then poll the [focusMap] briefly for the
     * row to focus.
     *
     * Focus polling: composition can lag the scroll by a frame or two, so we
     * retry up to ~250 ms before giving up. Silent give-up is fine — the
     * scroll alone delivers most of the value.
     */
    private suspend fun scrollAndFocus(target: NavTarget) {
        unfoldSectionContaining(target.chunkId)

        // If the target is a big (row-virtualized) function, decode it now so
        // it scrolls to its real per-row items rather than the placeholder, and
        // so the item-index walk below counts its rows correctly.
        ensureDecoded(target.chunkId)

        // Walk in current fold state to find the LazyColumn item index.
        var lazyCursor = 0
        var hidden = false
        var lazyIdx = -1
        var sawTargetSectionWithKind: org.jetbrains.hexana.document.format.SectionKind? = null
        var sawTargetChunk = false
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            when (c) {
                is BinaryChunk.SectionHeader -> {
                    hidden = c.id in foldedSectionsState.value
                    if (c.id == target.chunkId) {
                        lazyIdx = lazyCursor
                        sawTargetChunk = true
                        break
                    }
                    lazyCursor++
                }
                is BinaryChunk.Element -> {
                    if (c.id == target.chunkId) sawTargetSectionWithKind = c.kind
                    if (!hidden) {
                        if (c.id == target.chunkId) {
                            lazyIdx = lazyCursor
                            sawTargetChunk = true
                            break
                        }
                        lazyCursor += itemCountForChunk(c.id)
                    }
                }
            }
        }
        if (lazyIdx < 0) {
            return
        }
        // Instant jump rather than `animateScrollToItem`: when the target is
        // thousands of items away (typical for GotoSymbol on a 14k-function
        // module) the animation runs for noticeable seconds while the user
        // waits. IDE Go-to-Definition semantics expect an immediate land.
        // Scroll one item earlier so the target isn't hidden behind the
        // sticky section header that Compose pins at the top of the viewport.
        listState.scrollToItem(maxOf(0, lazyIdx - 1))

        // Focus polling only makes sense for code chunks (where the
        // editable opcode rows register requesters in `focusMap`). For
        // Function / Import / Export / Data / Memory / Table / Global
        // chunks the rows are non-editable, so polling wastes ~250ms
        // doing nothing and makes the click-to-jump feel laggy. Bail
        // out cleanly for non-CODE targets.
        val chunkIdx = chunkTree.chunkForId(target.chunkId)
        val targetChunk = chunkIdx?.let { chunkTree.chunkAt(it) }
        val isCodeTarget = targetChunk is BinaryChunk.Element && targetChunk.kind == WasmSectionKinds.CODE
        if (!isCodeTarget && target.rowIdx == null) {
            return
        }

        // Poll focusMap until the chunk's editable rows have registered.
        for (attempt in 0 until 5) {
            val rowKey = target.rowIdx ?: focusMap.keys
                .filter { it.first == target.chunkId }
                .minOfOrNull { it.second }
            val req = rowKey?.let { focusMap[target.chunkId to it] }
            if (req != null) {
                runCatching { req.requestFocus() }
                return
            }
            delay(50)
        }
    }

    /**
     * If [chunkId] belongs to a folded section, remove that section's id
     * from the folded set so the target chunk participates in the
     * [LazyColumn]'s linear walk. Returns the header [ChunkId] that was
     * walked through (regardless of whether it was actually folded) so the
     * caller can log the unfold attempt. Returns `null` if the chunk wasn't
     * found in the tree at all.
     */
    private fun unfoldSectionContaining(chunkId: ChunkId): ChunkId? {
        var currentHeader: ChunkId? = null
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            when (c) {
                is BinaryChunk.SectionHeader -> currentHeader = c.id
                is BinaryChunk.Element -> if (c.id == chunkId) {
                    val header = currentHeader ?: return null
                    if (header in foldedSectionsState.value) {
                        foldedSectionsState.value = foldedSectionsState.value - header
                    }
                    return header
                }
            }
        }
        return null
    }
}
