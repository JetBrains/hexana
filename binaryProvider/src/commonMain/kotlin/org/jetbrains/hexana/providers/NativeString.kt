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
}

private fun Int.isPrintableAscii(): Boolean = this in 0x20..0x7E
