package org.jetbrains.hexana.vscode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.hexana.io.CommonByteBuffer
import kotlin.js.Promise

/**
 * The bytes the hex view, the data inspector and the status bar show, addressed with [Long]
 * offsets: the whole file, which can be far larger than the buffer the tabs were parsed from.
 *
 * Reads are synchronous and may miss: [byteOrNull] answers `null` for a byte still being fetched,
 * and [loadStamp] changes when fetched bytes arrive, so a composable that reads it re-renders.
 */
interface HexBytes {
    val size: Long

    /** The byte at [offset], or `null` while it is not loaded yet. Requests it when missing. */
    fun byteOrNull(offset: Long): Byte?

    /** Changes whenever more bytes become available; read it in composition to follow loading. */
    val loadStamp: Int

    /**
     * Whether every byte is in memory. When `false`, whole-file operations (search) run on the
     * host instead of scanning [byteOrNull].
     */
    val isFullyLoaded: Boolean

    /**
     * The view now shows bytes [from] until [toExclusive]: fetch them and what surrounds them
     * ahead of time. A no-op for bytes that are all in memory.
     */
    fun prefetch(from: Long, toExclusive: Long) {}
}

/** [HexBytes] over a buffer that holds the whole file. */
class BufferHexBytes(private val buffer: CommonByteBuffer) : HexBytes {
    override val size: Long get() = buffer.size.toLong()

    override fun byteOrNull(offset: Long): Byte? =
        if (offset in 0 until size) buffer[offset.toInt()] else null

    override val loadStamp: Int get() = 0

    override val isFullyLoaded: Boolean get() = true

    /** The buffer itself, for code that works on a whole in-memory file. */
    val bytes: CommonByteBuffer get() = buffer
}

/**
 * [HexBytes] for a file larger than the webview holds: [head] is served from memory, the rest is
 * fetched with [fetch] in [PAGE_SIZE] pages as rows ask for it and kept in a bounded cache
 * ([MAX_CACHED_PAGES], least recently used dropped first).
 *
 * At most [MAX_IN_FLIGHT] pages are fetched at once and the most recently asked-for page goes
 * first, so a fast scroll does not leave the page now on screen waiting behind the ones scrolled
 * past. [prefetch] names the window the view shows: its pages and their neighbours are fetched
 * ahead, and queued pages outside it are dropped.
 */
class PagedHexBytes(
    private val head: CommonByteBuffer,
    override val size: Long,
    private val fetch: (offset: Long, length: Int) -> Promise<ByteArray>,
) : HexBytes {
    private val pages = LinkedHashMap<Long, ByteArray>()
    private val inFlight = mutableSetOf<Long>()
    /** Pages waiting for a fetch slot, the next one to fetch first. */
    private val queue = ArrayDeque<Long>()
    private val failed = mutableSetOf<Long>()

    override var loadStamp by mutableStateOf(0)
        private set

    override val isFullyLoaded: Boolean get() = false

    override fun byteOrNull(offset: Long): Byte? {
        if (offset < 0 || offset >= size) return null
        if (offset < head.size) return head[offset.toInt()]
        val page = offset / PAGE_SIZE
        val bytes = pages[page]
        if (bytes == null) {
            enqueue(page, first = true)
            pump()
            return null
        }
        // Touch for LRU order.
        pages.remove(page)
        pages[page] = bytes
        val inPage = (offset - page * PAGE_SIZE).toInt()
        return if (inPage < bytes.size) bytes[inPage] else null
    }

    override fun prefetch(from: Long, toExclusive: Long) {
        val start = maxOf(from, head.size.toLong())
        val end = minOf(toExclusive, size)
        if (start >= end) {
            queue.clear()
            return
        }
        val firstPage = start / PAGE_SIZE
        val lastPage = (end - 1) / PAGE_SIZE
        val lastPageInFile = (size - 1) / PAGE_SIZE
        val headPages = head.size.toLong() / PAGE_SIZE
        val wanted = (firstPage - PREFETCH_PAGES).coerceAtLeast(headPages)..(lastPage + PREFETCH_PAGES).coerceAtMost(lastPageInFile)
        // Pages asked for at a position already scrolled past are not worth a fetch slot.
        queue.removeAll { it !in wanted }
        // Shown pages first, then the neighbours, nearest first.
        for (page in firstPage..lastPage) enqueue(page, first = false)
        for (distance in 1..PREFETCH_PAGES) {
            (lastPage + distance).takeIf { it in wanted }?.let { enqueue(it, first = false) }
            (firstPage - distance).takeIf { it in wanted }?.let { enqueue(it, first = false) }
        }
        pump()
    }

    private fun enqueue(page: Long, first: Boolean) {
        if (page in pages || page in inFlight || page in failed) return
        if (first) {
            queue.remove(page)
            queue.addFirst(page)
        } else if (page !in queue) {
            queue.addLast(page)
        }
    }

    private fun pump() {
        while (inFlight.size < MAX_IN_FLIGHT) {
            val page = queue.removeFirstOrNull() ?: return
            start(page)
        }
    }

    private fun start(page: Long) {
        inFlight += page
        val start = page * PAGE_SIZE
        val length = minOf(PAGE_SIZE.toLong(), size - start).toInt()
        fetch(start, length).then(
            { bytes ->
                inFlight -= page
                pages[page] = bytes
                while (pages.size > MAX_CACHED_PAGES) pages.remove(pages.keys.first())
                loadStamp++
                pump()
            },
            {
                inFlight -= page
                // Not retried automatically: a page the host cannot read stays blank rather than
                // being requested on every frame.
                failed += page
                loadStamp++
                pump()
            },
        )
    }

    companion object {
        const val PAGE_SIZE: Int = 64 * 1024
        const val MAX_CACHED_PAGES: Int = 512
        const val MAX_IN_FLIGHT: Int = 4
        /** Pages fetched ahead on each side of the shown window. */
        const val PREFETCH_PAGES: Int = 1
    }
}
