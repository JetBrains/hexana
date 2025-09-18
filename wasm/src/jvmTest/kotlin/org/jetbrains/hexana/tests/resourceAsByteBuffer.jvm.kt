package org.jetbrains.hexana.tests

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParserTests
import java.nio.ByteBuffer
import kotlin.test.assertNotNull

actual fun resourceAsByteBuffer(resourcePath: String): CommonByteBuffer {
    val stream = WasmParserTests::class.java.classLoader.getResourceAsStream(resourcePath)
    assertNotNull(stream, "Resource skiko.wasm should be available on the classpath")
    val bytes = stream.use { it.readBytes() }
    return CommonByteBuffer(ByteBuffer.wrap(bytes))
}