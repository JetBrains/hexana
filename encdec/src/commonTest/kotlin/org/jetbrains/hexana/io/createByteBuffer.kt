package org.jetbrains.hexana.io

expect fun createByteBuffer(vararg bytes: Byte = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) : CommonByteBuffer