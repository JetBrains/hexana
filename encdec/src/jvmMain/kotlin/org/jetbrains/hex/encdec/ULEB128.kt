package org.jetbrains.hex.encdec

import java.nio.ByteBuffer

fun ByteBuffer.getUInt8(): UInt = get().toUInt()

fun ByteBuffer.getULEB128(): Long {
    var result = 0L
    var shift = 0

    while (true) {
        val byte = getUInt8()
        result = result or ((byte and 0x7f.toUInt()) shl shift).toLong()
        if (byte and 0x80.toUInt() == 0.toUInt()) {
            break
        }
        shift +=7
    }

    return result
}