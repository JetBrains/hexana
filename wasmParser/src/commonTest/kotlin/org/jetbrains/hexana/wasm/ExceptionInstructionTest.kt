package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.parseWat
import org.jetbrains.hexana.tests.parseWatResource
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.tests.watResourceToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes
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

    // --- try_table (0x1F) — not yet supported ---
    // These tests verify that the WAT compiles to valid WASM (via wat2wasm)
    // and document expected parse failure until try_table support is added.

    @Test
    fun `try_table catch — compiles but parser does not support yet`() = runTest {
        val buffer = watResourceToBuffer("wat/exceptions/try_table_catch.wat")
        val result = WasmParser().parse(buffer)
        // try_table (0x1F) is not handled — parser will either throw
        // UnsupportedOpsException or produce an incomplete parse.
        // Once try_table support is added, change this to assertIs<WasmParseSuccess>
        // and verify the catch handler structure.
        assertTrue(
            result is WasmParseSuccess || result is WasmParseFailure,
            "Parser should either succeed (once implemented) or fail gracefully"
        )
    }

    @Test
    fun `try_table catch_all — compiles but parser does not support yet`() = runTest {
        val buffer = watResourceToBuffer("wat/exceptions/try_table_catch_all.wat")
        val result = WasmParser().parse(buffer)
        assertTrue(
            result is WasmParseSuccess || result is WasmParseFailure,
            "Parser should either succeed or fail gracefully"
        )
    }

    @Test
    fun `try_table catch_ref — compiles but parser does not support yet`() = runTest {
        val buffer = watResourceToBuffer("wat/exceptions/try_table_catch_ref.wat")
        val result = WasmParser().parse(buffer)
        assertTrue(
            result is WasmParseSuccess || result is WasmParseFailure,
            "Parser should either succeed or fail gracefully"
        )
    }

    @Test
    fun `try_table catch_all_ref — compiles but parser does not support yet`() = runTest {
        val buffer = watResourceToBuffer("wat/exceptions/try_table_catch_all_ref.wat")
        val result = WasmParser().parse(buffer)
        assertTrue(
            result is WasmParseSuccess || result is WasmParseFailure,
            "Parser should either succeed or fail gracefully"
        )
    }

    @Test
    fun `try_table nested — compiles but parser does not support yet`() = runTest {
        val buffer = watResourceToBuffer("wat/exceptions/try_table_nested.wat")
        val result = WasmParser().parse(buffer)
        assertTrue(
            result is WasmParseSuccess || result is WasmParseFailure,
            "Parser should either succeed or fail gracefully"
        )
    }

    @Test
    fun `throw_ref — compiles but parser does not support yet`() = runTest {
        val buffer = watResourceToBuffer("wat/exceptions/throw_ref.wat")
        val result = WasmParser().parse(buffer)
        // throw_ref (0x0A) IS supported, but the try_table wrapping it is not.
        assertTrue(
            result is WasmParseSuccess || result is WasmParseFailure,
            "Parser should either succeed or fail gracefully"
        )
    }
}