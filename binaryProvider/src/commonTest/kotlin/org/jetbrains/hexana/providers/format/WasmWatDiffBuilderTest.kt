package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasmWatDiffBuilderTest {

    /** One function, type ()->i32, body `i32.const 5`. */
    private val oneFunc = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    /** Same, body `i32.const 6`. */
    private val oneFuncChangedBody = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x06, 0x0B,
    )

    /** Exports the single function as `main` (so the resolver names func 0). */
    private val withExportName = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x07, 0x0E, 0x02,
        0x04, 0x6D, 0x61, 0x69, 0x6E, 0x00, 0x00,
        0x03, 0x6D, 0x65, 0x6D, 0x02, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    private fun source(bytes: ByteArray) = WasmFunctionWatSource(WasmBinaryProvider(bytes.toCommonByteBuffer()))

    @Test
    fun `changed body marks the changed line`() {
        val diff = WasmWatDiffBuilder.functionDiff(source(oneFunc), source(oneFuncChangedBody), 0, 0, "f")
        assertTrue(diff.lineOps.any { it.kind == LineOpKind.REMOVED && it.text.contains("i32.const 5") })
        assertTrue(diff.lineOps.any { it.kind == LineOpKind.ADDED && it.text.contains("i32.const 6") })
    }

    @Test
    fun `identical function has no add or remove`() {
        val diff = WasmWatDiffBuilder.functionDiff(source(oneFunc), source(oneFunc), 0, 0, "f")
        assertTrue(diff.leftLines.isNotEmpty())
        assertTrue(diff.lineOps.none { it.kind == LineOpKind.ADDED || it.kind == LineOpKind.REMOVED })
    }

    @Test
    fun `absent left side renders all added`() {
        val diff = WasmWatDiffBuilder.functionDiff(source(oneFunc), source(oneFunc), null, 0, "f")
        assertTrue(diff.leftLines.isEmpty())
        assertTrue(diff.rightLines.isNotEmpty())
        assertTrue(diff.lineOps.all { it.kind == LineOpKind.ADDED })
    }

    @Test
    fun `codeIdxForName resolves an exported function`() {
        val src = source(withExportName)
        assertEquals(0, src.codeIdxForName("main"))
        assertEquals(null, src.codeIdxForName("nope"))
    }
}
