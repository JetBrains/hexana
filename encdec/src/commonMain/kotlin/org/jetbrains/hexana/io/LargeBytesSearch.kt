package org.jetbrains.hexana.io

/** Bytes read per step of a search; matches spanning two steps are still found. */
private const val SEARCH_CHUNK = 1 shl 20

/**
 * Offset of the first occurrence of [needle] starting at or after [from] (at or before [from]
 * when [reverse]), or `-1`. Reads in chunks, so it works on any size; [checkCanceled] runs
 * between chunks and may throw to stop a long search.
 */
fun LargeBytes.indexOf(
    needle: ByteArray,
    from: Long = 0,
    reverse: Boolean = false,
    checkCanceled: () -> Unit = {},
): Long {
    if (needle.isEmpty() || needle.size > size) return -1
    val lastStart = size - needle.size
    return if (reverse) {
        lastIndexOfBackward(needle, from.coerceAtMost(lastStart), checkCanceled)
    } else {
        indexOfForward(needle, from.coerceAtLeast(0), lastStart, checkCanceled)
    }
}

/**
 * Start offsets of the occurrences of [needle], in order, at most [limit] of them; overlapping
 * matches count separately ("aa" occurs twice in "aaa").
 */
fun LargeBytes.findAll(needle: ByteArray, limit: Int, checkCanceled: () -> Unit = {}): List<Long> {
    val found = ArrayList<Long>()
    if (needle.isEmpty() || needle.size > size || limit <= 0) return found
    val lastStart = size - needle.size
    // One pass over the bytes with one buffer: dense matches (zeros in zero padding) must not
    // re-read a chunk per match.
    val buffer = ByteArray(SEARCH_CHUNK + needle.size - 1)
    var chunkStart = 0L
    while (chunkStart <= lastStart) {
        checkCanceled()
        val starts = minOf(SEARCH_CHUNK.toLong(), lastStart - chunkStart + 1).toInt()
        read(chunkStart, buffer, 0, starts + needle.size - 1)
        var i = 0
        while (i < starts) {
            val hit = buffer.indexOf(needle, i, starts - i)
            if (hit < 0) break
            found += chunkStart + hit
            if (found.size >= limit) return found
            i = hit + 1
        }
        chunkStart += starts
    }
    return found
}

private fun LargeBytes.indexOfForward(needle: ByteArray, from: Long, lastStart: Long, checkCanceled: () -> Unit): Long {
    var chunkStart = from
    val buffer = ByteArray(SEARCH_CHUNK + needle.size - 1)
    while (chunkStart <= lastStart) {
        checkCanceled()
        // Starts examined this round, plus the tail a match beginning at the last of them needs.
        val starts = minOf(SEARCH_CHUNK.toLong(), lastStart - chunkStart + 1).toInt()
        val length = starts + needle.size - 1
        read(chunkStart, buffer, 0, length)
        val hit = buffer.indexOf(needle, 0, starts)
        if (hit >= 0) return chunkStart + hit
        chunkStart += starts
    }
    return -1
}

private fun LargeBytes.lastIndexOfBackward(needle: ByteArray, from: Long, checkCanceled: () -> Unit): Long {
    var chunkEnd = from // last start to examine, inclusive
    val buffer = ByteArray(SEARCH_CHUNK + needle.size - 1)
    while (chunkEnd >= 0) {
        checkCanceled()
        val starts = minOf(SEARCH_CHUNK.toLong(), chunkEnd + 1).toInt()
        val chunkStart = chunkEnd - starts + 1
        read(chunkStart, buffer, 0, starts + needle.size - 1)
        for (i in starts - 1 downTo 0) {
            if (buffer.matchesAt(i, needle)) return chunkStart + i
        }
        chunkEnd = chunkStart - 1
    }
    return -1
}

/** First `i` in `[from, from + starts)` where [needle] begins in this array, or `-1`. */
private fun ByteArray.indexOf(needle: ByteArray, from: Int, starts: Int): Int {
    val first = needle[0]
    for (i in from until from + starts) {
        if (this[i] == first && matchesAt(i, needle)) return i
    }
    return -1
}

private fun ByteArray.matchesAt(at: Int, needle: ByteArray): Boolean {
    for (j in needle.indices) if (this[at + j] != needle[j]) return false
    return true
}
