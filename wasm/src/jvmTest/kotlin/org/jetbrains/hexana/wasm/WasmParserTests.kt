package org.jetbrains.hexana.wasm

import kotlin.test.Test
import kotlin.test.assertNotNull
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmParserTests {

    private fun getAsByteBuffer(resourceName: String): ByteBuffer {
        val stream = WasmParserTests::class.java.classLoader.getResourceAsStream(resourceName)
        assertNotNull(stream, "Resource skiko.wasm should be available on the classpath")
        val bytes = stream.use { it.readBytes() }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun parseSkikoSections() {
        val wasmModule = WasmParser().parse(getAsByteBuffer("skiko.wasm"))

        assertNotNull(wasmModule)

        assertNull(wasmModule.customSection)
        assertEquals(2796, wasmModule.typesSection?.size)
        assertEquals(4265, wasmModule.importSection?.size)
        assertEquals(14363, wasmModule.functionSection?.size)
        assertEquals(7, wasmModule.tableSection?.size)
        assertEquals(7, wasmModule.memorySection?.size)
        assertEquals(14, wasmModule.globalSection?.size)
        assertEquals(49677, wasmModule.exportSection?.size)
        assertNull(wasmModule.startSection?.size)
        assertEquals(19761, wasmModule.elementSection?.size)
        assertEquals(6380629, wasmModule.codeSection?.size)
        assertEquals(1833784, wasmModule.dataSection?.size)
        assertNull( wasmModule.dataCountSection)
        assertEquals(49677,  wasmModule.exportSection?.size)
    }
}
