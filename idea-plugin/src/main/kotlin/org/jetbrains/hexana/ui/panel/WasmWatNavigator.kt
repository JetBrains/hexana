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
) {
    init {
        // One-shot: enumerate per-section chunk counts so we can verify the
        // FUNCTION section was actually split per-entry (and that the index
        // arithmetic in resolveFuncTarget has chunks to match against).
        var codeCount = 0
        var funcCount = 0
        var firstFuncIdx: Int? = null
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element) {
                when (c.kind) {
                    WasmSectionKinds.CODE -> codeCount++
                    WasmSectionKinds.FUNCTION -> {
                        if (firstFuncIdx == null) firstFuncIdx = c.indexInSection
                        funcCount++
                    }
                    else -> Unit
                }
            }
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] navigator init totalChunks=${chunkTree.totalChunks} " +
                "codeChunks=$codeCount funcChunks=$funcCount firstFuncIndexInSection=$firstFuncIdx " +
                "importFuncCount=${provider.importFuncCount}",
        )
    }
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
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] gotoChunk chunkId=$chunkId recordHistory=$recordHistory",
        )
        if (chunkTree.chunkForId(chunkId) == null) {
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] gotoChunk chunkId=$chunkId not in tree",
            )
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
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] gotoFunc funcIdx=$funcIdx section=${section.id} " +
                "importFuncCount=${provider.importFuncCount} recordHistory=$recordHistory resolved=$target",
        )
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
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] back popped=$prev backStackSize=${history.back.size} " +
                "fwdStackSize=${history.forward.size}",
        )
        if (prev == null) return false
        history.current?.let { history.forward += it }
        history.current = prev
        scope.launch { scrollAndFocus(prev) }
        return true
    }

    fun forward(): Boolean {
        val next = history.forward.removeLastOrNull()
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] forward popped=$next backStackSize=${history.back.size} " +
                "fwdStackSize=${history.forward.size}",
        )
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
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] resolveFuncTarget funcIdx=$funcIdx is imported — " +
                    "redirected to Import section, resolved=$target",
            )
            return target
        }
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == section && c.indexInSection == indexInSection) {
                return NavTarget(chunkId = c.id, rowIdx = null)
            }
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] resolveFuncTarget no chunk for funcIdx=$funcIdx " +
                "indexInSection=$indexInSection section=${section.id} totalChunks=${chunkTree.totalChunks}",
        )
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
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] resolveImportFuncTarget no mapping for funcIdx=$funcIdx " +
                    "(have ${importFuncRecordIndices.size} func imports)",
            )
            return null
        }
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.Element && c.kind == WasmSectionKinds.IMPORT && c.indexInSection == recordIdx) {
                return NavTarget(chunkId = c.id, rowIdx = null)
            }
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] resolveImportFuncTarget no IMPORT chunk for recordIdx=$recordIdx " +
                "(funcIdx=$funcIdx)",
        )
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
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] scrollAndFocus ENTER chunkId=${target.chunkId} rowIdx=${target.rowIdx}",
        )
        val unfoldedHeader = unfoldSectionContaining(target.chunkId)
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] scrollAndFocus unfold header=$unfoldedHeader " +
                "foldedAfter=${foldedSectionsState.value.size}",
        )

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
                        lazyCursor++
                    } else if (c.id == target.chunkId) {
                        org.jetbrains.hexana.HexanaLog.LOG.warn(
                            "[wat-nav-debug] scrollAndFocus target chunk is HIDDEN " +
                                "(its section is folded) chunkId=${target.chunkId} kind=${c.kind.id}",
                        )
                    }
                }
            }
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] scrollAndFocus chunkId=${target.chunkId} lazyIdx=$lazyIdx " +
                "sawTargetChunk=$sawTargetChunk sawKind=${sawTargetSectionWithKind?.id} " +
                "totalChunks=${chunkTree.totalChunks} foldedCount=${foldedSectionsState.value.size}",
        )
        if (lazyIdx < 0) {
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] scrollAndFocus BAILING lazyIdx<0 — no scroll dispatched",
            )
            return
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] scrollAndFocus scrollToItem START lazyIdx=$lazyIdx " +
                "firstVisible=${listState.firstVisibleItemIndex}",
        )
        // Instant jump rather than `animateScrollToItem`: when the target is
        // thousands of items away (typical for GotoSymbol on a 14k-function
        // module) the animation runs for noticeable seconds while the user
        // waits. IDE Go-to-Definition semantics expect an immediate land.
        listState.scrollToItem(lazyIdx)
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] scrollAndFocus scrollToItem DONE firstVisible=${listState.firstVisibleItemIndex}",
        )

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
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] scrollAndFocus skipping focus-poll for non-CODE target " +
                    "(kind=${(targetChunk as? BinaryChunk.Element)?.kind?.id})",
            )
            return
        }

        // Poll focusMap until the chunk's editable rows have registered.
        for (attempt in 0 until 5) {
            val rowKey = target.rowIdx ?: focusMap.keys
                .filter { it.first == target.chunkId }
                .minOfOrNull { it.second }
            val req = rowKey?.let { focusMap[target.chunkId to it] }
            org.jetbrains.hexana.HexanaLog.LOG.warn(
                "[wat-nav-debug] scrollAndFocus focus-poll attempt=$attempt rowKey=$rowKey " +
                    "reqAvailable=${req != null} focusMapSize=${focusMap.size}",
            )
            if (req != null) {
                runCatching { req.requestFocus() }
                return
            }
            delay(50)
        }
        org.jetbrains.hexana.HexanaLog.LOG.warn(
            "[wat-nav-debug] scrollAndFocus gave up focusing chunkId=${target.chunkId}",
        )
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
