package org.jetbrains.hexana.io

import org.khronos.webgl.toInt8Array

actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer = WebByteBuffer(toInt8Array().buffer)