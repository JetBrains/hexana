package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer

data class NativeStringRecord(
    val offset: Int,
    val size: Int,
    val value: String,
)

object NativeStringScanner {
    fun scanAscii(
        bytes: CommonByteBuffer,
        minLength: Int = 4,
        maxBytes: Int = 8 * 1024 * 1024,
        maxStrings: Int = 5_000,
    ): List<NativeStringRecord> {
        require(minLength > 0) { "minLength must be positive" }
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(maxStrings >= 0) { "maxStrings must be non-negative" }

        val limit = minOf(bytes.size, maxBytes)
        val strings = mutableListOf<NativeStringRecord>()
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
                strings.add(NativeStringRecord(start, builder.length, builder.toString()))
            }

            index = if (index == start) index + 1 else index + 1
        }

        return strings
    }
}

private fun Int.isPrintableAscii(): Boolean = this in 0x20..0x7E
