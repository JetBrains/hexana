package org.jetbrains.hexana.mcp

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal fun readFileBytesBounded(
    path: Path,
    maxBytes: Int,
    inputStreamOpener: (Path) -> InputStream = Files::newInputStream,
): BoundedFileReadResult {
    require(maxBytes >= 0) { "maxBytes must not be negative" }

    val sentinelSize = maxBytes.toLong() + 1L
    var bytes = ByteArray(minOf(sentinelSize, 8L * 1024L).toInt())
    var offset = 0
    inputStreamOpener(path).use { input ->
        while (offset.toLong() < sentinelSize) {
            if (offset == bytes.size) {
                if (bytes.size == Int.MAX_VALUE) {
                    return if (input.read() < 0) {
                        BoundedFileReadResult.Bytes(bytes)
                    } else {
                        BoundedFileReadResult.TooLarge(sentinelSize)
                    }
                }
                val doubledSize = bytes.size.toLong() * 2L
                bytes = bytes.copyOf(minOf(sentinelSize, doubledSize, Int.MAX_VALUE.toLong()).toInt())
            }
            val count = input.read(bytes, offset, bytes.size - offset)
            when {
                count < 0 -> break
                count > 0 -> offset += count
                else -> {
                    val singleByte = input.read()
                    if (singleByte < 0) break
                    bytes[offset++] = singleByte.toByte()
                }
            }
        }
    }
    return if (offset.toLong() == sentinelSize) {
        BoundedFileReadResult.TooLarge(sentinelSize)
    } else {
        BoundedFileReadResult.Bytes(if (offset == bytes.size) bytes else bytes.copyOf(offset))
    }
}

internal sealed interface BoundedFileReadResult {
    data class Bytes(val bytes: ByteArray) : BoundedFileReadResult
    data class TooLarge(val atLeastBytes: Long) : BoundedFileReadResult
}

internal fun ByteArray.toBoundedFileReadResult(maxBytes: Int): BoundedFileReadResult =
    if (size.toLong() > maxBytes.toLong()) {
        BoundedFileReadResult.TooLarge(maxBytes.toLong() + 1L)
    } else {
        BoundedFileReadResult.Bytes(this)
    }
