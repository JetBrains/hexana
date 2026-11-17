package org.jetbrains.hexana.vscode

import androidx.compose.runtime.snapshots.Snapshot
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hex view's byte source for a file the webview holds only the head of: the head is served
 * from memory, the rest fetched a page at a time.
 */
class HexBytesTest {
    private val page = PagedHexBytes.PAGE_SIZE.toLong()
    private val size = 3L * 1024 * 1024 * 1024 // past what a webview could hold

    /** A fake host whose bytes are the low byte of their offset, recording what was fetched. */
    private class FakeHost(private val fail: Boolean = false) {
        val requests = mutableListOf<Pair<Long, Int>>()
        fun fetch(offset: Long, length: Int): Promise<ByteArray> {
            requests += offset to length
            return if (fail) Promise.reject(RuntimeException("unreadable"))
            else Promise.resolve(ByteArray(length) { (offset + it).toByte() })
        }
    }

    @Test
    fun headIsServedWithoutFetching() {
        val host = FakeHost()
        val bytes = PagedHexBytes(ByteArray(16) { 7 }.toCommonByteBuffer(), size, host::fetch)
        assertEquals(7.toByte(), bytes.byteOrNull(15))
        assertEquals(emptyList(), host.requests)
        assertNull(bytes.byteOrNull(size))
        assertFalse(bytes.isFullyLoaded)
    }

    @Test
    fun bytesPastTheHeadArriveAPageAtATime(): Promise<Unit> {
        val host = FakeHost()
        val bytes = PagedHexBytes(ByteArray(16).toCommonByteBuffer(), size, host::fetch)
        val far = 5L * 512 * 1024 * 1024 + 3 // 2.5 GiB
        assertNull(bytes.byteOrNull(far), "not loaded yet")
        assertNull(bytes.byteOrNull(far + 1), "same page: no second request")
        assertEquals(listOf(far / page * page to PagedHexBytes.PAGE_SIZE), host.requests)
        val stamp = bytes.loadStamp
        return Promise.resolve(Unit).then {
            assertTrue(bytes.loadStamp > stamp, "arrival re-renders")
            assertEquals(far.toByte(), bytes.byteOrNull(far))
            assertEquals(1, host.requests.size)
        }
    }

    @Test
    fun theLastPageIsShort(): Promise<Unit> {
        val host = FakeHost()
        val bytes = PagedHexBytes(ByteArray(16).toCommonByteBuffer(), page * 10 + 5, host::fetch)
        bytes.byteOrNull(page * 10)
        assertEquals(listOf(page * 10 to 5), host.requests)
        return Promise.resolve(Unit).then { assertEquals((page * 10 + 4).toByte(), bytes.byteOrNull(page * 10 + 4)) }
    }

    @Test
    fun anUnreadablePageIsNotRequestedAgain(): Promise<Unit> {
        val host = FakeHost(fail = true)
        val bytes = PagedHexBytes(ByteArray(16).toCommonByteBuffer(), size, host::fetch)
        bytes.byteOrNull(page * 3)
        return Promise.resolve(Unit).then { }.then {
            assertNull(bytes.byteOrNull(page * 3))
            assertEquals(1, host.requests.size)
        }
    }

    /** A fake host that answers only when told to, so the fetch queue can be observed. */
    private class ManualHost {
        val requests = mutableListOf<Long>()
        private val replies = mutableMapOf<Long, () -> Unit>()
        fun fetch(offset: Long, length: Int): Promise<ByteArray> {
            requests += offset
            return Promise { resolve, _ -> replies[offset] = { resolve(ByteArray(length) { (offset + it).toByte() }) } }
        }
        fun answer(offset: Long) = replies.remove(offset)!!.invoke()
    }

    @Test
    fun aFastScrollFetchesThePageNowShownFirst(): Promise<Unit> {
        val host = ManualHost()
        val bytes = PagedHexBytes(ByteArray(16).toCommonByteBuffer(), size, host::fetch)
        // Scrolled past pages 10..19; only four are fetched at once.
        for (p in 10L..19L) bytes.byteOrNull(p * page)
        assertEquals(PagedHexBytes.MAX_IN_FLIGHT, host.requests.size)
        // The view settles on page 19: the queued pages it scrolled past are dropped.
        bytes.prefetch(19 * page, 19 * page + 512)
        host.answer(host.requests.first())
        return Promise.resolve(Unit).then {
            assertEquals(19 * page, host.requests[PagedHexBytes.MAX_IN_FLIGHT], "the shown page goes next")
            assertTrue(host.requests.none { it in (14 * page)..(17 * page) }, "pages scrolled past are not fetched")
        }
    }

    @Test
    fun prefetchFetchesTheNeighbouringPages() {
        val host = FakeHost()
        val bytes = PagedHexBytes(ByteArray(16).toCommonByteBuffer(), size, host::fetch)
        bytes.prefetch(5 * page + 100, 5 * page + 900)
        assertEquals(listOf(5 * page, 6 * page, 4 * page), host.requests.map { it.first })
    }

    @Test
    fun prefetchSkipsTheHeadAndTheEndOfTheFile() {
        val host = FakeHost()
        val bytes = PagedHexBytes(ByteArray(page.toInt() * 2).toCommonByteBuffer(), page * 3, host::fetch)
        bytes.prefetch(page + 10, page * 3)
        assertEquals(listOf(page * 2), host.requests.map { it.first })
    }

    /** How many snapshot state reads [block] makes, as a composition scope running it would record. */
    private fun stateReads(block: () -> Unit): Int {
        var reads = 0
        Snapshot.observe(readObserver = { reads++ }, block = block)
        return reads
    }

    @Test
    fun aMissSubscribesTheReaderToArrivals(): Promise<Unit> {
        val host = FakeHost()
        val bytes = PagedHexBytes(ByteArray(16).toCommonByteBuffer(), size, host::fetch)
        // The scope that drew a placeholder must be invalidated when the page lands, whichever
        // nested composable lambda it is; so the miss itself is a state read.
        assertTrue(stateReads { bytes.byteOrNull(9 * page) } > 0, "a miss reads loadStamp")
        return Promise.resolve(Unit).then {
            assertEquals(0, stateReads { bytes.byteOrNull(9 * page) }, "a hit does not")
            assertEquals(0, stateReads { bytes.byteOrNull(3) }, "nor does the head")
        }
    }

    @Test
    fun bufferBytesAreTheWholeFile() {
        val bytes = BufferHexBytes(byteArrayOf(1, 2, 3).toCommonByteBuffer())
        assertEquals(3L, bytes.size)
        assertEquals(3.toByte(), bytes.byteOrNull(2))
        assertNull(bytes.byteOrNull(3))
        assertTrue(bytes.isFullyLoaded)
    }

    @Test
    fun searchHighlightsUseTheSortedMatchStarts() {
        val state = HexSearchState()
        state.matchStarts = listOf(4L, 100L, 3L shl 30)
        state.matchLength = 3
        state.currentMatchIndex = 1
        assertTrue(state.isMatch(5))
        assertFalse(state.isMatch(7))
        assertTrue(state.isMatch((3L shl 30) + 2))
        assertTrue(state.isInCurrentMatch(102))
        assertFalse(state.isInCurrentMatch(4))
    }

    @Test
    fun aLateHostAnswerForAnOlderQueryIsDropped() {
        val state = HexSearchState()
        val first = state.beginPagedSearch() // "4F"
        val second = state.beginPagedSearch() // "4F 6B", typed before the first scan finished
        state.applyPagedMatch(second, 10L shl 30)
        state.applyPagedMatch(first, 5L)
        assertEquals(10L shl 30, state.currentMatchStart)
        assertFalse(state.isCurrentPagedSearch(first), "no wrap-around search for the old query either")
    }

    @Test
    fun anAnswerArrivingAfterTheQueryWasClearedIsDropped() {
        val state = HexSearchState()
        val generation = state.beginPagedSearch()
        state.clear()
        state.applyPagedMatch(generation, 42L)
        assertEquals(-1L, state.currentMatchStart)
        assertEquals(emptyList(), state.matchStarts)
    }

    @Test
    fun selectionIsSixtyFourBit() {
        val selection = HexSelectionState()
        selection.selectRange(5L shl 30, 16L)
        assertEquals(5L shl 30, selection.selectionStart)
        assertEquals((5L shl 30) + 15, selection.selectionEnd)
        assertTrue(selection.contains((5L shl 30) + 8))
    }
}
