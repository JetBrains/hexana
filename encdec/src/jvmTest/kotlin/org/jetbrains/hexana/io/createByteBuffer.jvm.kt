package org.jetbrains.hexana.io

import java.nio.ByteBuffer


actual fun createByteBuffer(vararg bytes: Byte): CommonByteBuffer {
    return JvmByteBuffer(ByteBuffer.wrap(bytes))
}