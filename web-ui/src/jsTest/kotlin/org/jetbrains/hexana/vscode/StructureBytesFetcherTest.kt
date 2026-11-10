package org.jetbrains.hexana.vscode

import kotlinx.browser.window
import org.jetbrains.hexana.io.SparseLargeBytes
import org.jetbrains.hexana.io.readBytes
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ParseCoverage
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rounds that let the 64-bit parsers read a file the webview holds only the head of: what
 * a read missed is fetched, and [StructureBytesFetcher.bytes] is replaced by bytes that hold it.
 */
class StructureBytesFetcherTest {
    private val page = SparseLargeBytes.PAGE_SIZE
    private val size = 3L * 1024 * 1024 * 1024

    private class FakeHost(private val fail: Boolean = false) {
        val requests = mutableListOf<Pair<Long, Int>>()
        fun fetch(offset: Long, length: Int): Promise<ByteArray> {
            requests += offset to length
            return if (fail) Promise.reject(RuntimeException("unreadable"))
            else Promise.resolve(ByteArray(length) { (offset + it).toByte() })
        }
    }

    /** Runs [check] once the fake host's replies have been handled. */
    private fun afterReplies(check: () -> Unit): Promise<Unit> =
        Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 10) }.then { check() }

    private fun fetcher(host: FakeHost) =
        StructureBytesFetcher(ByteArray(4096).toCommonByteBuffer(), size, host::fetch, schedule = { it() })

    @Test
    fun aMissIsFetchedWithTheTailAndReplacesTheBytes(): Promise<Unit> {
        val host = FakeHost()
        val fetcher = fetcher(host)
        val first = fetcher.bytes
        val far = 5L * 512 * 1024 * 1024 + 3
        assertEquals(0, first[far].toInt(), "not loaded: zero")
        assertTrue(fetcher.isFetching)
        assertEquals(ParseCoverage.Prefix(4096, "structures still being read"), fetcher.coverageOverride)
        val farPage = far / page * page
        assertEquals(listOf(farPage to page.toInt(), size - 1024 * 1024 to 1024 * 1024), host.requests)
        return afterReplies {
            val next = fetcher.bytes
            assertTrue(next !== first, "a new instance for the provider to be rebuilt over")
            assertEquals(far.toByte(), next[far])
            assertContentEquals(ByteArray(4) { (size - 4 + it).toByte() }, next.readBytes(size - 4, 4))
            assertFalse(fetcher.isFetching)
            assertNull(fetcher.coverageOverride)
        }
    }

    @Test
    fun aProviderThatReadsOnlyTheHeadFetchesNothing(): Promise<Unit> {
        val host = FakeHost()
        val fetcher = fetcher(host)
        val first = fetcher.bytes
        first.readBytes(0, 4096)
        return afterReplies {
            assertEquals(emptyList(), host.requests)
            assertSame(first, fetcher.bytes)
            assertNull(fetcher.coverageOverride)
        }
    }

    @Test
    fun aFailedReadMarksTheViewIncompleteAndIsNotRetried(): Promise<Unit> {
        val host = FakeHost(fail = true)
        val fetcher = fetcher(host)
        fetcher.bytes[page * 100]
        return afterReplies {
            assertTrue(fetcher.isIncomplete)
            assertEquals(ParseCoverage.Prefix(4096, "structures that could not be fetched"), fetcher.coverageOverride)
            fetcher.bytes[page * 100]
        }.then {
            afterReplies { assertEquals(2, host.requests.size, "the page and the tail, once") }
        }.then { }
    }

    @Test
    fun aMissInTheTailIsFetchedAndCountedOnce(): Promise<Unit> {
        val host = FakeHost()
        val fetcher = fetcher(host)
        // ELF keeps its section headers at the end: the first miss is already in the tail.
        fetcher.bytes[size - 100]
        val tail = 1024 * 1024
        assertEquals(listOf(size - tail to tail), host.requests)
        assertEquals(tail.toLong(), fetcher.fetchedBytes)
        return afterReplies { assertEquals((size - 100).toByte(), fetcher.bytes[size - 100]) }
    }

    @Test
    fun aFailedReadGivesItsBudgetBack(): Promise<Unit> {
        val host = FakeHost(fail = true)
        val fetcher = fetcher(host)
        fetcher.bytes[page * 100]
        assertTrue(fetcher.fetchedBytes > 0, "reserved while in flight")
        return afterReplies { assertEquals(0L, fetcher.fetchedBytes, "nothing was fetched") }
    }
}
