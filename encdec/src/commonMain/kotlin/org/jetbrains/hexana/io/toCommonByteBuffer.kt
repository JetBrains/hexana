package org.jetbrains.hexana.io

expect fun ByteArray.toCommonByteBuffer(): CommonByteBuffer

expect fun allocateCommonByteBuffer(size: Int): CommonByteBuffer