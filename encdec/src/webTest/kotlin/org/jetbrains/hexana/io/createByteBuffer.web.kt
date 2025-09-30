package org.jetbrains.hexana.io

import org.khronos.webgl.toInt8Array

actual fun createByteBuffer(vararg bytes: Byte): CommonByteBuffer {
    return WebByteBuffer(bytes.toInt8Array().buffer)
}