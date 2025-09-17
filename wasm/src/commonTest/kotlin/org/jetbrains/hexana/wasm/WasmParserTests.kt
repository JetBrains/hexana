package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.tests.IgnoreWebTarget
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WasmParserTests {

    @Test
    @IgnoreWebTarget
    fun parseSkikoSections() {
        val buffer = resourceAsByteBuffer("skiko.wasm")
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
    @IgnoreWebTarget
    fun parseLongImports() {
        val buffer = resourceAsByteBuffer("string.wasm")
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