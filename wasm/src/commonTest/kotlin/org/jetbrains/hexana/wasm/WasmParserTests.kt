package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.tests.IgnoreWebTarget
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.WasmCodeLocalRecord
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@IgnoreWebTarget
class WasmParserTests {

    @Test
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

    @Test
    fun `code leftshift`() {
        assertCodeRecords("snippets/leftshift.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.Local.Get(1),
                    OpCodes.I32.Shl,
                )
            )
        ))
    }

    @Test
    fun `code popcnt`() {
        assertCodeRecords("snippets/popcnt.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.I32.Popcnt,
                )
            )
        ))
    }

    @Test
    fun `code or`() {
        assertCodeRecords("snippets/or.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.Local.Get(1),
                    OpCodes.I32.Or,
                )
            )
        ))
    }

    @Test
    fun `code xor`() {
        assertCodeRecords("snippets/xor.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.Local.Get(1),
                    OpCodes.I32.Xor,
                )
            )
        ))
    }

    @Test
    fun `code and`() {
        assertCodeRecords("snippets/and.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.Local.Get(1),
                    OpCodes.I32.And,
                )
            )
        ))
    }

    @Test
    fun `code leftrotate`() {
        assertCodeRecords("snippets/leftrotate.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.Local.Get(1),
                    OpCodes.I32.Rotl,
                )
            )
        ))
    }

    @Test
    fun `code rightrotate`() {
        assertCodeRecords("snippets/rightrotate.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.Local.Get(1),
                    OpCodes.I32.Rotr,
                )
            )
        ))
    }

    @Test
    fun `code call`() {
        assertCodeRecords("snippets/call.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Flow.Call(0),
                )
            )
        ))
    }

    @Test
    fun `code unreachable`() {
        assertCodeRecords("snippets/unreachable.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Flow.Unreachable,
                )
            )
        ))
    }

    @Test
    fun `code nop`() {
        assertCodeRecords("snippets/nop.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Flow.Nop,
                )
            )
        ))
    }

    @Test
    fun `code clz`() {
        assertCodeRecords("snippets/clz.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.I32.Clz
                )
            )
        ))
    }

    @Test
    fun `code ctz`() {
        assertCodeRecords("snippets/ctz.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.Local.Get(0),
                    OpCodes.I32.Ctz
                )
            )
        ))
    }

    @Test
    fun `code const`() {
        assertCodeRecords("snippets/const.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code globalset`() {
        assertCodeRecords("snippets/globalset.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.Global.Set(0),
                    OpCodes.Global.Get(0),
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code equal`() {
        assertCodeRecords("snippets/equal.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(2),
                    OpCodes.I32.Eq,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code return`() {
        assertCodeRecords("snippets/return.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(90),
                    OpCodes.Flow.Return
                )
            )
        ))
    }

    @Test
    fun `code remainder`() {
        assertCodeRecords("snippets/remainder.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(3),
                    OpCodes.I32.RemU,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code addition`() {
        assertCodeRecords("snippets/addition.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(3),
                    OpCodes.I32.Add,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code multiplication`() {
        assertCodeRecords("snippets/multiplication.wasm", listOf(
            WasmCodeRecord(
                1,
                emptyList(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(3),
                    OpCodes.I32.Mul,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code local`() {
        assertCodeRecords("snippets/local.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(WasmCodeLocalRecord(1, 127)),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.Local.Set(0),
                    OpCodes.Local.Get(0),
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code ne`() {
        assertCodeRecords("snippets/ne.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(2),
                    OpCodes.I32.Ne,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code drop`() {
        assertCodeRecords("snippets/drop.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(20),
                    OpCodes.Flow.Drop,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code gt`() {
        assertCodeRecords("snippets/gt.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(2),
                    OpCodes.I32.GTU,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code nearest`() {
        assertCodeRecords("snippets/nearest.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.F32.Const(2.700000047683716f),
                    OpCodes.F32.Nearest,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code floor`() {
        assertCodeRecords("snippets/floor.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.F32.Const(-2.700000047683716f),
                    OpCodes.F32.Floor,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code ceil`() {
        assertCodeRecords("snippets/ceil.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.F32.Const(2.700000047683716f),
                    OpCodes.F32.Ceil,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code truncf2f`() {
        assertCodeRecords("snippets/truncf2f.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.F32.Const(-2.700000047683716f),
                    OpCodes.F32.Trunc,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

    @Test
    fun `code copy`() {
        assertCodeRecords("snippets/copy.wasm", listOf(
            WasmCodeRecord(
                1,
                listOf(),
                listOf(
                    OpCodes.F32.Const(10f),
                    OpCodes.F32.Const(-2f),
                    OpCodes.F32.CopySign,
                    OpCodes.Flow.Call(0)
                )
            )
        ))
    }

}


private fun assertCodeRecord(actual: WasmCodeRecord, expected: WasmCodeRecord) {
    assertContentEquals(expected = expected.locals, actual = actual.locals)
    assertContentEquals(expected = expected.opCodes, actual = actual.opCodes)
}

private fun assertCodeRecords(resourcePath: String, expected: List<WasmCodeRecord>) {
    val buffer = resourceAsByteBuffer(resourcePath)
    val wasmModule = WasmParser().parse(buffer)

    val codeSection = wasmModule?.codeSection!!
    with (codeSection) {
        val codeRecords = buffer.scan()
        codeRecords.zip(expected).forEach { (codeRecord, expectedCodeRecord) ->
            assertCodeRecord(codeRecord, expectedCodeRecord)
        }
    }
}