package org.jetbrains.hexana.tests

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParserTests
import kotlin.test.assertNotNull

actual suspend fun resourceAsByteBuffer(resourcePath: String): CommonByteBuffer {
    val stream = WasmParserTests::class.java.classLoader.getResourceAsStream(resourcePath)
    assertNotNull(stream, "Resource ${resourcePath} should be available on the classpath")
    val bytes = stream.use { it.readBytes() }
    return bytes.toCommonByteBuffer()
}