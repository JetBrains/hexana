package org.jetbrains.hexana.io

import java.nio.ByteBuffer

actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer = JvmByteBuffer(ByteBuffer.wrap(this))