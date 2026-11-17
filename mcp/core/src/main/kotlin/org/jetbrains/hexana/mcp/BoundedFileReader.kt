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

    val tooLargeAtLeast = maxBytes.toLong() + 1L
    var bytes = ByteArray(minOf(maxBytes, 8 * 1024))
    var offset = 0
    inputStreamOpener(path).use { input ->
        while (offset < maxBytes) {
            if (offset == bytes.size) {
                val doubledSize = bytes.size.toLong() * 2L
                bytes = bytes.copyOf(minOf(maxBytes.toLong(), doubledSize).toInt())
            }
            val count = input.read(bytes, offset, bytes.size - offset)
            when {
                count < 0 -> return BoundedFileReadResult.Bytes(bytes.trimToSize(offset))
                count > 0 -> offset += count
                else -> {
                    val singleByte = input.read()
                    if (singleByte < 0) return BoundedFileReadResult.Bytes(bytes.trimToSize(offset))
                    bytes[offset++] = singleByte.toByte()
                }
            }
        }
        return if (input.read() < 0) {
            BoundedFileReadResult.Bytes(bytes)
        } else {
            BoundedFileReadResult.TooLarge(tooLargeAtLeast)
        }
    }
}

private fun ByteArray.trimToSize(size: Int): ByteArray = if (size == this.size) this else copyOf(size)

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
