package org.jetbrains.hexana.io

/**
 * A file of [size] bytes of which only some are in memory: the head (bytes `0 until head.size`)
 * and the chunks fetched so far. For a host that cannot read the file synchronously — the VS
 * Code webview asks the extension for bytes with a round trip — but still wants the 64-bit
 * parsers to read structures anywhere in it.
 *
 * A read of bytes not in memory gets zeros and records them in [misses]. Zeros rather than an
 * exception, because the parsers' lazily computed tables do not expect a read inside the file to
 * fail, and zero counts and sizes make them come out empty instead of crashing the UI. The host
 * runs the parsers in rounds: parse, fetch what was missed, build a new instance [withChunks],
 * parse again, until nothing is missed. Every round only adds bytes, so the rounds converge.
 * Only a [window] too large to fill with zeros ([MAX_ZERO_FILLED_WINDOW]) throws
 * [BytesNotLoadedException].
 *
 * Immutable apart from the miss record (and [onMiss]), so one instance backs one parse.
 */
class SparseLargeBytes private constructor(
    private val head: CommonByteBuffer,
    override val size: Long,
    /** Sorted by start, non-overlapping and not adjacent (adjacent chunks are merged). */
    private val chunks: List<Chunk>,
) : LargeBytes {

    /** Bytes [bytes] at [start] of the file. */
    class Chunk(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    private val headSize: Long = head.size.toLong()

    private val missed = mutableListOf<LongRange>()

    /** Called after a read records a miss; the host schedules a fetch from it. */
    var onMiss: (() -> Unit)? = null

    /** Bytes in memory: the head and every chunk. */
    val loadedBytes: Long get() = headSize + chunks.sumOf { it.bytes.size.toLong() }

    /** Ranges reads asked for that are not in memory, page-aligned and merged; `[first, last]`. */
    val misses: List<LongRange> get() = mergeRanges(missed)

    override fun get(offset: Long): Byte {
        if (offset < 0 || offset >= size) throw IndexOutOfBoundsException("offset $offset outside 0 until $size")
        if (offset < headSize) return head[offset.toInt()]
        val chunk = chunkAt(offset)
        if (chunk == null) {
            recordMiss(offset, offset + 1)
            onMiss?.invoke()
            return 0
        }
        return chunk.bytes[(offset - chunk.start).toInt()]
    }

    override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        checkRead(offset, length, size, dst, dstOffset)
        val end = offset + length
        var missedAny = false
        var pos = offset
        var out = dstOffset
        while (pos < end) {
            if (pos < headSize) {
                val n = (minOf(end, headSize) - pos).toInt()
                head.duplicate().position(pos.toInt()).get(dst, out, n)
                pos += n; out += n
                continue
            }
            val chunk = chunkAt(pos)
            if (chunk != null) {
                val n = (minOf(end, chunk.end) - pos).toInt()
                val from = (pos - chunk.start).toInt()
                chunk.bytes.copyInto(dst, out, from, from + n)
                pos += n; out += n
            } else {
                val gapEnd = (chunks.firstOrNull { it.start > pos }?.start ?: end).coerceAtMost(end)
                val n = (gapEnd - pos).toInt()
                dst.fill(0, out, out + n)
                recordMiss(pos, gapEnd)
                missedAny = true
                pos += n; out += n
            }
        }
        if (missedAny) onMiss?.invoke()
    }

    override fun window(offset: Long, length: Int): CommonByteBuffer {
        checkWindow(offset, length, size)
        if (offset + length <= headSize) return head.slice(offset.toInt(), length)
        val chunk = chunkAt(offset)
        if (chunk != null && offset + length <= chunk.end) {
            return chunk.bytes.toCommonByteBuffer().slice((offset - chunk.start).toInt(), length)
        }
        if (length > MAX_ZERO_FILLED_WINDOW && !isLoaded(offset, offset + length)) {
            recordMiss(maxOf(offset, headSize), offset + length)
            onMiss?.invoke()
            throw BytesNotLoadedException(offset, length.toLong())
        }
        // Spans the head and chunks, or reaches bytes not loaded yet: one copy of just the window.
        return ByteArray(length).also { read(offset, it, 0, length) }.toCommonByteBuffer()
    }

    override fun asCommonByteBufferOrNull(): CommonByteBuffer? = null

    /** A new instance that also holds [fetched]; its miss record starts empty. */
    fun withChunks(fetched: List<Chunk>): SparseLargeBytes = SparseLargeBytes(head, size, merge(chunks + fetched))

    /** Whether all of `[start, end)` is in memory. */
    fun isLoaded(start: Long, end: Long): Boolean {
        var pos = maxOf(start, headSize)
        while (pos < end) {
            pos = chunkAt(pos)?.end ?: return false
        }
        return true
    }

    private fun chunkAt(offset: Long): Chunk? {
        var lo = 0
        var hi = chunks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val chunk = chunks[mid]
            when {
                offset < chunk.start -> hi = mid - 1
                offset >= chunk.end -> lo = mid + 1
                else -> return chunk
            }
        }
        return null
    }

    /**
     * Records `[start, end)` as missed, widened to whole [PAGE_SIZE] pages. A miss right after
     * loaded bytes is usually a parser reading on sequentially (a string table byte by byte), so
     * it is widened by the size of the run it continues, up to [MAX_SEQUENTIAL_GROWTH]: the
     * chunk doubles instead of costing a round per page.
     */
    private fun recordMiss(start: Long, end: Long) {
        var from = start / PAGE_SIZE * PAGE_SIZE
        var to = (end + PAGE_SIZE - 1) / PAGE_SIZE * PAGE_SIZE
        // The loaded run the miss follows: the last chunk before it, else the head.
        val before = chunks.lastOrNull { it.end <= start }
        val runEnd = before?.end ?: headSize
        val runSize = before?.bytes?.size?.toLong() ?: headSize
        if (start - runEnd < PAGE_SIZE) {
            from = maxOf(from, runEnd)
            to = maxOf(to, from + minOf(runSize, MAX_SEQUENTIAL_GROWTH))
        }
        from = maxOf(from, headSize)
        to = minOf(to, size)
        if (from >= to) return
        // A byte-by-byte walk over a gap misses once per byte; keep one record of it.
        val last = missed.lastOrNull()
        if (last != null && from >= last.first && to - 1 <= last.last) return
        missed += from until to
    }

    companion object {
        /** Granularity of what a miss asks for. */
        const val PAGE_SIZE: Long = 64 * 1024

        /** Largest step a sequential read grows a chunk by. */
        const val MAX_SEQUENTIAL_GROWTH: Long = 4L * 1024 * 1024

        /** Largest [window] over bytes not loaded yet that is zero-filled rather than thrown for. */
        const val MAX_ZERO_FILLED_WINDOW: Int = 64 * 1024 * 1024

        /** [head] of a [size]-byte file with nothing else loaded yet. */
        fun of(head: CommonByteBuffer, size: Long): SparseLargeBytes {
            require(size >= head.size) { "a $size-byte file cannot have a ${head.size}-byte head" }
            return SparseLargeBytes(head.duplicate().position(0), size, emptyList())
        }

        private fun merge(chunks: List<Chunk>): List<Chunk> {
            val sorted = chunks.filter { it.bytes.isNotEmpty() }.sortedBy { it.start }
            val out = mutableListOf<Chunk>()
            for (chunk in sorted) {
                val last = out.lastOrNull()
                if (last == null || chunk.start > last.end) {
                    out += chunk
                } else if (chunk.end > last.end) {
                    // Overlapping or adjacent: append the part past the last chunk.
                    val tail = chunk.bytes.copyOfRange((last.end - chunk.start).toInt(), chunk.bytes.size)
                    out[out.size - 1] = Chunk(last.start, last.bytes + tail)
                }
            }
            return out
        }

        /** [ranges] sorted, with overlapping and adjacent ones joined. */
        fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
            val sorted = ranges.sortedBy { it.first }
            val out = mutableListOf<LongRange>()
            for (range in sorted) {
                val last = out.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    out[out.size - 1] = last.first..maxOf(last.last, range.last)
                } else {
                    out += range
                }
            }
            return out
        }
    }
}

/**
 * Whether `[start, end)` reads as the file's real bytes: always, except for a [SparseLargeBytes]
 * that has not fetched them yet (and would read zeros). Check it before handing bytes to the user
 * — opening an archive member, say — rather than to a parser that will run again.
 */
fun LargeBytes.isFullyLoaded(start: Long, end: Long): Boolean = (this as? SparseLargeBytes)?.isLoaded(start, end) ?: true

/** A [SparseLargeBytes.window] too large to zero-fill over bytes not loaded yet; see there. */
class BytesNotLoadedException(val offset: Long, val length: Long) :
    RuntimeException("bytes $offset+$length are not loaded yet")
