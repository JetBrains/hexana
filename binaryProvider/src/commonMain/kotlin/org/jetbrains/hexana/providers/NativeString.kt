package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer

data class BinaryStringRecord(
    val value: String,
    val offset: Int = -1,
    val size: Int = -1,
    val index: Int = -1,
    val tag: String? = null,
)

object NativeStringScanner {
    fun scanAscii(
        bytes: CommonByteBuffer,
        minLength: Int = 4,
        maxBytes: Int = 8 * 1024 * 1024,
        maxStrings: Int = 5_000,
    ): List<BinaryStringRecord> {
        require(minLength > 0) { "minLength must be positive" }
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(maxStrings >= 0) { "maxStrings must be non-negative" }

        val limit = minOf(bytes.size, maxBytes)
        val strings = mutableListOf<BinaryStringRecord>()
        var index = 0

        while (index < limit && strings.size < maxStrings) {
            val start = index
            val builder = StringBuilder()

            while (index < limit) {
                val value = bytes[index].toInt() and 0xFF
                if (!value.isPrintableAscii()) break
                builder.append(value.toChar())
                index++
            }

            if (builder.length >= minLength) {
                strings.add(BinaryStringRecord(value = builder.toString(), offset = start, size = builder.length))
            }

            index = if (index == start) index + 1 else index + 1
        }

        return strings
    }

    /**
     * Scans ASCII strings from a specific set of byte ranges within [bytes], preserving
     * file-relative offsets in the returned records. Use this instead of [scanAscii] when
     * the caller knows which sections contain string data (e.g. Mach-O), to avoid hitting
     * the [maxStrings] cap on a full-file scan.
     *
     * Each range is a (fileOffset, length) pair. Ranges that extend beyond [bytes] are
     * clamped silently. The returned list is ordered by file offset.
     */
    fun scanSections(
        bytes: CommonByteBuffer,
        sections: Iterable<Pair<Int, Int>>,
        minLength: Int = 4,
    ): List<BinaryStringRecord> {
        require(minLength > 0) { "minLength must be positive" }
        val result = mutableListOf<BinaryStringRecord>()
        for ((sectionOffset, sectionLength) in sections) {
            if (sectionOffset < 0 || sectionLength <= 0) continue
            val sectionEnd = minOf(sectionOffset + sectionLength, bytes.size)
            if (sectionOffset >= sectionEnd) continue
            var index = sectionOffset
            while (index < sectionEnd) {
                val start = index
                val builder = StringBuilder()
                while (index < sectionEnd) {
                    val b = bytes[index].toInt() and 0xFF
                    if (!b.isPrintableAscii()) break
                    builder.append(b.toChar())
                    index++
                }
                if (builder.length >= minLength) {
                    result.add(BinaryStringRecord(value = builder.toString(), offset = start, size = builder.length))
                }
                index = if (index == start) index + 1 else index + 1
            }
        }
        return result
    }
}

private fun Int.isPrintableAscii(): Boolean = this in 0x20..0x7E
