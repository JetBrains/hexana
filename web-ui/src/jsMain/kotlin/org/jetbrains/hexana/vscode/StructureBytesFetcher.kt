package org.jetbrains.hexana.vscode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.SparseLargeBytes
import org.jetbrains.hexana.providers.ParseCoverage
import kotlin.js.Promise

/**
 * Lets the 64-bit parsers read a file the webview holds only the head of. [bytes] is the whole
 * file as a [SparseLargeBytes]: what the parsers read past the head and is not there yet reads
 * as zeros and is recorded; this fetches those ranges from the extension with [fetch] and
 * replaces [bytes] with an instance that holds them. A provider keyed on [bytes] is rebuilt and
 * reads further — the section header table, then the string table it names, … — until a round
 * misses nothing. The first round also fetches the tail of the file, where ZIP, ELF and Parquet
 * keep their tables. A provider that reads only the head misses nothing and is never rebuilt.
 *
 * Bounded: at most [MAX_FETCHED_BYTES] in all, over at most [MAX_ROUNDS] rounds. What is left
 * out then (debug info of hundreds of megabytes, say) marks the view [isIncomplete].
 */
class StructureBytesFetcher(
    head: CommonByteBuffer,
    private val fileSize: Long,
    private val fetch: (offset: Long, length: Int) -> Promise<ByteArray>,
    /** Runs a round after the reads that missed have returned; a test runs it at once. */
    private val schedule: (() -> Unit) -> Unit = { block -> window.setTimeout({ block() }, 0) },
) {
    private val headSize = head.size.toLong()

    /** The file as far as it is fetched; replaced (state) when a round's bytes arrive. */
    var bytes: SparseLargeBytes by mutableStateOf(SparseLargeBytes.of(head, fileSize).also(::watch))
        private set

    /** A round's bytes are on their way. */
    var isFetching: Boolean by mutableStateOf(false)
        private set

    /** Some bytes the parsers read were not fetched: over the budget, or the host failed to read them. */
    var isIncomplete: Boolean by mutableStateOf(false)
        private set

    /** Bytes fetched beyond the head so far. */
    var fetchedBytes: Long = 0
        private set

    private var rounds = 0
    private var roundScheduled = false
    private var inFlight = false
    private var tailRequested = false
    private val declined = mutableListOf<LongRange>()

    /**
     * What the tabs cover while this is not done, else `null` for the provider's own answer: a
     * prefix while bytes are still arriving or some were never fetched.
     */
    val coverageOverride: ParseCoverage?
        get() = when {
            isIncomplete -> ParseCoverage.Prefix(headSize, "structures that could not be fetched")
            isFetching -> ParseCoverage.Prefix(headSize, "structures still being read")
            else -> null
        }

    private fun watch(sparse: SparseLargeBytes) {
        sparse.onMiss = ::requestRound
    }

    private fun requestRound() {
        if (roundScheduled || inFlight) return
        roundScheduled = true
        schedule {
            roundScheduled = false
            runRound()
        }
    }

    private fun runRound() {
        val current = bytes
        val wanted = current.misses
            .filter { miss -> declined.none { it.first <= miss.first && miss.last <= it.last } }
            .toMutableList()
        // Nothing read past the head: a provider limited to it, which must not be rebuilt for nothing.
        if (wanted.isEmpty()) return
        if (!tailRequested) {
            tailRequested = true
            val tailStart = maxOf(headSize, fileSize - TAIL_BYTES)
            if (tailStart < fileSize && !current.isLoaded(tailStart, fileSize)) wanted += tailStart until fileSize
        }
        if (rounds >= MAX_ROUNDS) {
            decline(wanted)
            return
        }
        val accepted = mutableListOf<LongRange>()
        for (range in wanted) {
            val length = range.last - range.first + 1
            if (fetchedBytes + length <= MAX_FETCHED_BYTES) {
                accepted += range
                fetchedBytes += length
            } else {
                decline(listOf(range))
            }
        }
        if (accepted.isEmpty()) return

        inFlight = true
        isFetching = true
        val requests = accepted.flatMap { range ->
            generateSequence(range.first) { it + MAX_REQUEST_BYTES }
                .takeWhile { it <= range.last }
                .map { start ->
                    val length = minOf(MAX_REQUEST_BYTES.toLong(), range.last + 1 - start).toInt()
                    fetch(start, length).then { data -> SparseLargeBytes.Chunk(start, data) }
                }
                .toList()
        }
        Promise.all(requests.toTypedArray()).then(
            { chunks ->
                rounds++
                inFlight = false
                isFetching = false
                val next = current.withChunks(chunks.toList())
                watch(next)
                bytes = next
            },
            {
                decline(accepted)
                inFlight = false
                isFetching = false
            },
        )
    }

    private fun decline(ranges: List<LongRange>) {
        declined += ranges
        isIncomplete = true
    }

    companion object {
        /** The most bytes fetched past the head for one file. */
        const val MAX_FETCHED_BYTES: Long = 256L * 1024 * 1024

        /** The most rounds of parse-then-fetch for one file. */
        const val MAX_ROUNDS: Int = 64

        /** The most one request asks for: the extension's `MAX_RANGE_BYTES`. */
        const val MAX_REQUEST_BYTES: Int = 16 * 1024 * 1024

        /** How much of the end of the file the first round fetches. */
        const val TAIL_BYTES: Long = 1024L * 1024
    }
}
