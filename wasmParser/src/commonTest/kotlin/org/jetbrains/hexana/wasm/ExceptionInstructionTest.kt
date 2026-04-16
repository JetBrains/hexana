package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.parseWatResource
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.tests.watResourceToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for WASM 3.0 exception handling instructions.
 *
 * Covers: throw, throw_ref, try_table with catch/catch_all/catch_ref/catch_all_ref,
 * tag declarations, tag imports, tag exports.
 *
 * try_table (opcode 0x1F) is not yet supported by the parser — tests that require
 * it are marked to expect parse failure until support is added.
 */
class ExceptionInstructionTest {

    // --- throw (0x08) ---

    @Test
    fun `throw simple — tag with i32 param`() = runTest {
        val module = parseWatResource("wat/exceptions/throw_simple.wat")
        val codeSection = module.codeSection!!
        val buffer = watResourceToBuffer("wat/exceptions/throw_simple.wat")
        val records = with(codeSection) { buffer.scan() }

        assertEquals(1, records.size)
        val opcodes = records[0].opCodes
        val throwOp = opcodes.filterIsInstance<OpCodes.Flow.Trow>().firstOrNull()
        assertNotNull(throwOp, "Should find a throw instruction")
        assertEquals(0u, throwOp.value, "Tag index should be 0")
    }

    @Test
    fun `throw multivalue — tag with i32 and i64 params`() = runTest {
        val module = parseWatResource("wat/exceptions/throw_multivalue.wat")
        val codeSection = module.codeSection!!
        val buffer = watResourceToBuffer("wat/exceptions/throw_multivalue.wat")
        val records = with(codeSection) { buffer.scan() }

        val throwOp = records[0].opCodes.filterIsInstance<OpCodes.Flow.Trow>().firstOrNull()
        assertNotNull(throwOp)
        assertEquals(0u, throwOp.value)
    }

    @Test
    fun `throw no params — empty tag`() = runTest {
        val module = parseWatResource("wat/exceptions/throw_no_params.wat")
        val codeSection = module.codeSection!!
        val buffer = watResourceToBuffer("wat/exceptions/throw_no_params.wat")
        val records = with(codeSection) { buffer.scan() }

        val throwOp = records[0].opCodes.filterIsInstance<OpCodes.Flow.Trow>().firstOrNull()
        assertNotNull(throwOp)
        assertEquals(0u, throwOp.value)
    }

    // --- throw inline WAT ---

    @Test
    fun `throw inline — multiple tags`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (tag ${'$'}e1 (param i32))
              (tag ${'$'}e2 (param i64))
              (func (export "f")
                (throw ${'$'}e2 (i64.const 99))
              )
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }

        val throwOp = records[0].opCodes.filterIsInstance<OpCodes.Flow.Trow>().firstOrNull()
        assertNotNull(throwOp)
        assertEquals(1u, throwOp.value, "Should reference tag index 1 (${'$'}e2)")
    }

    // --- tag import ---

    @Test
    fun `tag import — parsed as WasmTagType`() = runTest {
        val module = parseWatResource("wat/exceptions/tag_import.wat")
        val importSection = module.importSection
        assertNotNull(importSection)
        val buffer = watResourceToBuffer("wat/exceptions/tag_import.wat")
        val imports = with(importSection) { buffer.getImportRecords() }

        assertEquals(1, imports.size)
        assertEquals("env", imports[0].prefix)
        assertEquals("exn", imports[0].name)
        assertIs<WasmTagType>(imports[0].type)
    }

    // --- tag export ---

    @Test
    fun `tag export — parsed as WasmTagExportRecordEntry`() = runTest {
        val module = parseWatResource("wat/exceptions/tag_export.wat")
        val exportSection = module.exportSection
        assertNotNull(exportSection)
        val buffer = watResourceToBuffer("wat/exceptions/tag_export.wat")
        val exports = with(exportSection) { buffer.scan() }

        val tagExport = exports.filterIsInstance<WasmTagExportRecordEntry>().firstOrNull()
        assertNotNull(tagExport, "Should have a tag export")
        assertEquals("myexn", tagExport.name)
    }

    @Test
    fun `try_table catch`() = runTest {
        val module = parseWatResource("wat/exceptions/try_table_catch.wat")
        val buffer = watResourceToBuffer("wat/exceptions/try_table_catch.wat")
        val result = WasmParser().parse(buffer)
        val records = with(module.codeSection!!) { buffer.scan() }
        val op = records.single().opCodes.single()
        assertIs<OpCodes.Flow.TryTable>(op)
        val catch = op.catches.single()
        assertIs<OpCodes.Flow.CatchType.Catch>( catch)
        assertIs<I32.Const>(op.code[0])
        assertIs<OpCodes.Flow.Trow>(op.code[1])
        assertIs<I32.Const>(op.code[2])
        assertTrue( result is WasmParseSuccess )
    }

    @Test
    fun `try_table catch_all`() = runTest {
        val wat = "wat/exceptions/try_table_catch_all.wat"
        val module = parseWatResource(wat)
        val buffer = watResourceToBuffer(wat)
        val result = WasmParser().parse(buffer)
        assertTrue( result is WasmParseSuccess )
        val records = with(module.codeSection!!) { buffer.scan() }
        val op = records.single().opCodes.first()
        assertIs<OpCodes.Flow.TryTable>(op)
        val catch = op.catches.single()
        assertIs<OpCodes.Flow.CatchType.CatchAll>( catch)
        assertIs<I32.Const>(op.code[0])
        assertIs<OpCodes.Flow.Trow>(op.code[1])
        assertIs<I32.Const>(op.code[2])
    }

    @Test
    fun `try_table catch_ref`() = runTest {
        val wat = "wat/exceptions/try_table_catch_ref.wat"
        val module = parseWatResource(wat)
        val buffer = watResourceToBuffer(wat)
        val result = WasmParser().parse(buffer)
        assertTrue( result is WasmParseSuccess )
        val records = with(module.codeSection!!) { buffer.scan() }
        val op = records.single().opCodes.first()
        assertIs<OpCodes.Flow.TryTable>(op)
        val catch = op.catches.single()
        assertIs<OpCodes.Flow.CatchType.CatchRef>( catch)
        assertIs<I32.Const>(op.code[0])
        assertIs<OpCodes.Flow.Trow>(op.code[1])
        assertIs<I32.Const>(op.code[2])
    }

    @Test
    fun `try_table catch_all_ref`() = runTest {
        val wat = "wat/exceptions/try_table_catch_all_ref.wat"
        val module = parseWatResource(wat)
        val buffer = watResourceToBuffer(wat)
        val result = WasmParser().parse(buffer)
        assertTrue( result is WasmParseSuccess )
        val records = with(module.codeSection!!) { buffer.scan() }
        val op = records.single().opCodes.first()
        assertIs<OpCodes.Flow.TryTable>(op)
        val catch = op.catches.single()
        assertIs<OpCodes.Flow.CatchType.CatchAllRef>( catch)
        assertIs<I32.Const>(op.code[0])
        assertIs<OpCodes.Flow.Trow>(op.code[1])
        assertIs<I32.Const>(op.code[2])
    }

    @Test
    fun `try_table nested`() = runTest {
        val wat = "wat/exceptions/try_table_nested.wat"
        val module = parseWatResource(wat)
        val buffer = watResourceToBuffer(wat)
        val result = WasmParser().parse(buffer)
        assertTrue( result is WasmParseSuccess )
        val records = with(module.codeSection!!) { buffer.scan() }
        val op = records.single().opCodes.first()
        assertIs<OpCodes.Flow.TryTable>(op)
        val catch = op.catches.single()
        assertIs<OpCodes.Flow.CatchType.Catch>( catch)
        val tryTableOpNested = op.code[0]
        assertIs<OpCodes.Flow.TryTable>(tryTableOpNested)
        val nestedCatch = tryTableOpNested.catches.single()
        assertIs<OpCodes.Flow.CatchType.Catch>( nestedCatch)
        assertIs<I32.Const>(op.code[1])
    }

    @Test
    fun `throw_ref`() = runTest {
        val wat = "wat/exceptions/throw_ref.wat"
        val module = parseWatResource(wat)
        val buffer = watResourceToBuffer(wat)
        val result = WasmParser().parse(buffer)
        assertTrue( result is WasmParseSuccess )
        val records = with(module.codeSection!!) { buffer.scan() }
        val op = records.single().opCodes.first()
        assertIs<OpCodes.Flow.TryTable>(op)
        val catch = op.catches.single()
        assertIs<OpCodes.Flow.CatchType.CatchRef>( catch)
        assertIs<I32.Const>(op.code[0])
        assertIs<OpCodes.Flow.Trow>(op.code[1])
        assertIs<I32.Const>(op.code[2])
    }
}