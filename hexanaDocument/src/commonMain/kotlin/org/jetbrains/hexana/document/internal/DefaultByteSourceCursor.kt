package org.jetbrains.hexana.document.internal

import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.ByteSourceCursor

internal class DefaultByteSourceCursor(
    override val source: ByteSource,
    initialPosition: Long,
) : ByteSourceCursor {
    override var position: Long = initialPosition

    override fun readByte(): Byte {
        val b = source.get(position)
        position++
        return b
    }

    override fun readBytes(length: Int): ByteArray {
        val out = source.read(position, length)
        position += length.toLong()
        return out
    }
}
