package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.CommonByteBuffer
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import kotlin.test.Test
import kotlin.test.assertNotNull
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
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
        val buffer = CommonByteBuffer(getAsByteBuffer("skiko.wasm"))
        val wasmModule = WasmParser().parse(buffer)

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
        assertEquals(3,  wasmModule.exceptionSection?.size)

    }

    @Test
    fun parseLongImports() {
        val buffer = CommonByteBuffer(getAsByteBuffer("string.wasm"))
        val wasmModule = WasmParser().parse(buffer)
        assertNotNull(wasmModule)

        with(wasmModule.importSection!!) {
            val importRecords = buffer.getImportRecords()

            assertEquals(4, importRecords.size)

            assertEquals(
                WasmImportRecord(
                    "abcdefghijklmnopqrstuvwxyz0123456789_0_abcdefghijklmnopqrstuvwxyz0123456789_1_abcdefghijklmnopqrstuvwxyz0123456789_2_abcdefghijklmnopqrstuvwxyz0123456789_2_abcdefghijklmnopqrstuvwxyz0123456789_3_abcdefghijklmnopqrstuvwxyz0123456789_4_abcdefghijklmnopqrstuvwxyz0123456789_5_abcdefghijklmnopqrstuvwxyz0123456789_6_abcdefghijklmnopqrstuvwxyz0123456789_7_abcdefghijklmnopqrstuvwxyz0123456789_8_abcdefghijklmnopqrstuvwxyz0123456789_9_abcdefghijklmnopqrstuvwxyz0123456789_10_abcdefghijklmnopqrstuvwxyz0123456789_11_",
                    "x",
                    0
                ), importRecords[0]
            )

            assertEquals(
                WasmImportRecord(
                    "string",
                    "a",
                    0
                ), importRecords[1]
            )

            assertEquals(
                WasmImportRecord(
                    "a",
                    "b",
                    0
                ), importRecords[2]
            )

            assertEquals(
                WasmImportRecord(
                    "env",
                    "abort",
                    0
                ), importRecords[3]
            )
        }
    }
}
