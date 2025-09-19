package org.jetbrains.hexana.tests

import org.jetbrains.hexana.io.CommonByteBuffer

expect suspend fun resourceAsByteBuffer(resourcePath: String): CommonByteBuffer