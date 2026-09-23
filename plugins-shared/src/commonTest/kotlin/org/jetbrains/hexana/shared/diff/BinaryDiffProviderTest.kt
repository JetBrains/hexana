package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.WasmBinaryWriter
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOp
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryDiffProviderTest {

    /** A one-function module exporting `hello`; [body] is the function's instructions before `end`. */
    private fun wasmModule(vararg body: Byte): ByteArray {
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
        val types = WasmBinaryWriter()
        types.writeULEB128(1)
        types.writeByte(0x60)
        types.writeULEB128(0)
        types.writeULEB128(0)
        w.writeSection(1, types.toByteArray())
        val functions = WasmBinaryWriter()
        functions.writeULEB128(1)
        functions.writeULEB128(0)
        w.writeSection(3, functions.toByteArray())
        val exports = WasmBinaryWriter()
        exports.writeULEB128(1)
        exports.writeName("hello")
        exports.writeByte(0x00)
        exports.writeULEB128(0)
        w.writeSection(7, exports.toByteArray())
        val code = WasmBinaryWriter()
        code.writeULEB128(1)
        val fn = WasmBinaryWriter()
        fn.writeULEB128(0)
        fn.writeBytes(body)
        fn.writeByte(0x0B)
        code.writeULEB128(fn.size())
        code.writeBytes(fn.toByteArray())
        w.writeSection(10, code.toByteArray())
        return w.toByteArray()
    }

    private val componentPreamble = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)

    private fun input(left: ByteArray, right: ByteArray) =
        BinaryDiffInput(left.toCommonByteBuffer(), right.toCommonByteBuffer(), "base.bin", "head.bin")

    // i32.const 1; drop
    private val base = wasmModule()
    private val head = wasmModule(0x41, 0x01, 0x1A)

    @Test
    fun registryRoutesByFormat() {
        assertIs<WasmModuleDiffProvider>(BinaryDiffProviderRegistry.forInput(input(base, head)))
        assertIs<WasmComponentDiffProvider>(BinaryDiffProviderRegistry.forInput(input(componentPreamble, componentPreamble)))
        assertIs<NativeDiffProvider>(BinaryDiffProviderRegistry.forInput(input(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))))
    }

    @Test
    fun mismatchedKindsAreNotComparable() {
        assertNull(BinaryDiffProviderRegistry.forInput(input(base, componentPreamble)))
        assertNull(BinaryDiffProviderRegistry.forInput(input(base, byteArrayOf(1, 2, 3))))
    }

    @Test
    fun wasmSessionDeclaresTabsAndDetail() {
        val session = WasmModuleDiffProvider().session(input(base, head))
        assertEquals(listOf("Size Impact", "Entities", "Dispatch"), session.tabs.map { it.name })
        assertNotNull(session.detail)
        assertEquals(emptyList(), session.banner())
    }

    @Test
    fun wasmSizeTabListsModifiedFunctionAndSelectsIt() = runTest {
        var selected: DiffItemRef? = null
        val tab = WasmModuleDiffProvider().session(input(base, head)).tabs.first()
        val content = tab.compute(DiffTabContext(select = { selected = it }))
        assertEquals("base.bin  →  head.bin", content.header.first().text)
        val table = assertNotNull(content.table)
        val names = (0 until table.rowCount).map { table.columns[1].cellProvider(it).representation }
        val fn = names.indexOfFirst { "hello" in it }
        assertTrue(fn >= 0, "expected the modified function among $names")
        assertEquals(RowTint.Modified, table.rowTintProvider?.invoke(fn))
        table.rowClickAction!!.invoke(fn)
        assertEquals(0, selected?.leftIndex)
        assertEquals(0, selected?.rightIndex)
    }

    @Test
    fun wasmDetailRendersSideBySideWat() = runTest {
        val detail = assertNotNull(WasmModuleDiffProvider().session(input(base, head)).detail)
        val rendered = detail.render(DiffItemRef("hello", 0, 0))
        assertNull(rendered.emptyMessage)
        assertTrue(rendered.rows.any { it.rightAdded }, "head adds instructions")
    }

    @Test
    fun identicalModulesHaveNoSizeTable() = runTest {
        val content = WasmModuleDiffProvider().session(input(base, base)).tabs.first().compute(DiffTabContext())
        assertNull(content.table)
        assertEquals("Modules are identical — no size changes.", content.emptyMessage)
    }

    @Test
    fun nativeWithoutSizeAnalysisReportsIt() = runTest {
        val session = NativeDiffProvider().session(input(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))
        assertNull(session.detail)
        val content = session.tabs.single().compute(DiffTabContext())
        assertEquals("Size analysis is not available for one or both binaries.", content.emptyMessage)
    }

    @Test
    fun sizeRowKeysMapToRefs() {
        assertEquals(DiffItemRef("f", 1, 2), sizeRowRef(NodeKind.FUNCTION, "fn:pair:1:2", "f"))
        assertEquals(DiffItemRef("f", 3, null), sizeRowRef(NodeKind.FUNCTION, "fn:rmL:3", "f"))
        assertEquals(DiffItemRef("f", null, 4), sizeRowRef(NodeKind.FUNCTION, "fn:addR:4", "f"))
        assertEquals(DiffItemRef("f", 5, 5), sizeRowRef(NodeKind.FUNCTION, "fn:idx:5", "f"))
        assertEquals(DiffItemRef("f", null, null), sizeRowRef(NodeKind.FUNCTION, "fn:name:f", "f"))
        assertNull(sizeRowRef(NodeKind.DATA_SEGMENT, "fn:pair:1:2", "f"))
    }

    @Test
    fun sideBySideZipsChangedHunks() {
        val rows = sideBySideRows(
            listOf(
                LineOp(LineOpKind.SAME, "a", 0, 0),
                LineOp(LineOpKind.REMOVED, "b", 1, null),
                LineOp(LineOpKind.ADDED, "B", null, 1),
                LineOp(LineOpKind.ADDED, "C", null, 2),
            ),
        )
        assertEquals(
            listOf(
                SideBySideRow("a", false, "a", false),
                SideBySideRow("b", true, "B", true),
                SideBySideRow(null, false, "C", true),
            ),
            rows,
        )
    }
}
