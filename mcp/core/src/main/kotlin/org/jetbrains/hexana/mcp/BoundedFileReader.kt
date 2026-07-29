package org.jetbrains.hexana.mcp

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal fun readFileBytesBounded(
    path: Path,
    maxBytes: Int,
    inputStreamOpener: (Path) -> InputStream = Files::newInputStream,
): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    require(maxBytes < Int.MAX_VALUE) { "maxBytes must leave room for an oversize sentinel byte" }

    val sentinelSize = maxBytes + 1
    var bytes = ByteArray(minOf(sentinelSize, 8 * 1024))
    var offset = 0
    inputStreamOpener(path).use { input ->
        while (offset < sentinelSize) {
            if (offset == bytes.size) {
                val doubledSize = bytes.size.toLong() * 2L
                bytes = bytes.copyOf(minOf(sentinelSize.toLong(), doubledSize).toInt())
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
    return if (offset == bytes.size) bytes else bytes.copyOf(offset)
}
