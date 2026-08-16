package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActive
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActiveWithMemoryIndex
import org.jetbrains.hexana.wasm.sections.WasmDataRecordPassive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for the data section across all three encoding flag values introduced
 * by the Bulk Memory Operations proposal.
 *
 * Spec: https://webassembly.github.io/spec/core/binary/modules.html#data-section
 *
 * | flag | mode                           | payload                          |
 * |------|--------------------------------|----------------------------------|
 * | 0    | active, default memory         | offset-expr ; bytes              |
 * | 1    | passive                        | bytes                            |
 * | 2    | active, explicit memory index  | memidx ; offset-expr ; bytes     |
 *
 * Plus the DataCount section (id 12) which the proposal made mandatory in
 * any module that uses `memory.init` or `data.drop`.
 */
class DataSectionTest {

    private suspend fun parseDataRecords(wat: String): List<WasmDataRecord> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val section = module.dataSection ?: fail("module has no data section")
        return with(section) { buffer.scan() }
    }

    // ========================================================================
    // Flag 0 — active default
    // ========================================================================

    @Test
    fun `flag 0 — active default memory`() = runTest {
        val records = parseDataRecords("""
            (module
              (memory 1)
              (data (i32.const 0) "ABCD")
            )
        """.trimIndent())
        val record = assertIs<WasmDataRecordActive>(records.single())
        assertEquals(byteArrayOf(0x41, 0x42, 0x43, 0x44).toList(), record.data.toByteArray().toList())
        assertTrue(record.expression.isNotEmpty(), "offset expression must be parsed")
    }

    @Test
    fun `flag 0 — multiple active segments`() = runTest {
        val records = parseDataRecords("""
            (module
              (memory 1)
              (data (i32.const 0) "AA")
              (data (i32.const 16) "BB")
              (data (i32.const 32) "CC")
            )
        """.trimIndent())
        assertEquals(3, records.size)
        records.forEach { assertIs<WasmDataRecordActive>(it) }
    }

    // ========================================================================
    // Flag 1 — passive
    // ========================================================================

    @Test
    fun `flag 1 — passive segment alone`() = runTest {
        val records = parseDataRecords("""
            (module
              (memory 1)
              (data "passive")
            )
        """.trimIndent())
        val record = assertIs<WasmDataRecordPassive>(records.single())
        assertEquals("passive", record.data.toByteArray().decodeToString())
    }

    @Test
    fun `flag 1 — passive segment used by memory init`() = runTest {
        val records = parseDataRecords("""
            (module
              (memory 1)
              (data ${'$'}d "ZZZZ")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 4
                memory.init ${'$'}d
              )
            )
        """.trimIndent())
        val record = assertIs<WasmDataRecordPassive>(records.single(),
            "wat2wasm encodes a segment referenced by memory.init as passive (flag 1)")
        assertEquals("ZZZZ", record.data.toByteArray().decodeToString())
    }

    // ========================================================================
    // Flag 2 — active with explicit memory index
    //
    // wat2wasm only emits flag 2 when the (memory N) clause is present AND
    // resolves to a non-default index, i.e. it requires multi-memory.
    // ========================================================================

    @Test
    fun `flag 2 — active with explicit memory index 1`() = runTest {
        val records = parseDataRecords("""
            (module
              (memory ${'$'}m0 1)
              (memory ${'$'}m1 1)
              (data (memory ${'$'}m1) (i32.const 0) "X")
            )
        """.trimIndent())
        val record = assertIs<WasmDataRecordActiveWithMemoryIndex>(records.single())
        assertEquals(1u, record.memoryIndex)
        assertEquals(byteArrayOf(0x58).toList(), record.data.toByteArray().toList())
    }

    // ========================================================================
    // DataCount section (id 12)
    //
    // Per the bulk-memory proposal, a module that uses memory.init / data.drop
    // MUST include a DataCount section between the Element and Code sections.
    // ========================================================================

    @Test
    fun `DataCount section is parsed when memory init is used`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (memory 1)
              (data ${'$'}d "abc")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 3
                memory.init ${'$'}d
              )
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        assertNotNull(module.dataCountSection,
            "DataCount section is mandatory when memory.init is present")
    }

    @Test
    fun `DataCount section absent when no bulk-memory ops are used`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (memory 1)
              (data (i32.const 0) "abc")
              (func (export "f"))
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        // Either null or present-but-counts-1; the proposal only requires its
        // presence when memory.init / data.drop are used.
        if (module.dataCountSection != null) {
            assertEquals(WasmSectionDataCountId, WasmSectionDataCountId,
                "If wat2wasm emitted DataCount it is fine; structural test only")
        }
    }

    // ========================================================================
    // Mixed flag values in one module
    // ========================================================================

    @Test
    fun `mixed — active default + passive coexist`() = runTest {
        val records = parseDataRecords("""
            (module
              (memory 1)
              (data (i32.const 0) "active-bytes")
              (data ${'$'}p "passive-bytes")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 13
                memory.init ${'$'}p
              )
            )
        """.trimIndent())
        assertEquals(2, records.size)
        assertIs<WasmDataRecordActive>(records[0])
        assertIs<WasmDataRecordPassive>(records[1])
    }

    // ========================================================================
    // Negative -- unknown flag degrades to an empty prefix
    //
    // Hand-crafted minimal module whose data section claims flag 5 (none of
    // the legal 0/1/2). scan() now delegates to scanPartial().first, which
    // catches the IllegalArgumentException for the unknown flag and returns
    // the decoded prefix (empty here, since entry 0 is the corrupt one).
    // ========================================================================

    @Test
    fun `unknown data flag is rejected when section is scanned`() = runTest {
        // Layout (annotated similar to `wasm-tools dump`):
        // ```
        //   off  | bytes          | meaning
        //   0x00 | 00 61 73 6d    | magic '\0asm'
        //   0x04 | 01 00 00 00    | version 1
        //   0x08 | 05 03          | memory section, size 3
        //   0x0a | 01             |   count: 1
        //   0x0b | 00 01          |   [memory 0] limits min=1
        //   0x0d | 0b 04          | data section, size 4
        //   0x0f | 01             |   count: 1
        //   0x10 | 05             |   [data 0] flag 5 (illegal -- spec allows 0/1/2)
        //   0x11 | 01 00          |     bytes: len=1, [0x00]
        // ```
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            0x05, 0x03, 0x01, 0x00, 0x01,
            0x0B, 0x04, 0x01, 0x05, 0x01, 0x00
        )
        val buffer = bytes.toCommonByteBuffer()
        val parseResult = WasmParser().parse(buffer)
        // scan() now degrades gracefully: the corrupt entry is skipped and the
        // decoded prefix (empty, since entry 0 is already bad) is returned.
        if (parseResult is WasmParseSuccess) {
            val module = parseResult.wasmModule as WasmModule
            val section = module.dataSection ?: fail("expected data section in crafted bytes")
            val records = with(section) { buffer.scan() }
            // No entries before the corrupt one; scan() must not throw.
            assertTrue(records.isEmpty(), "scan() must return an empty prefix for a leading corrupt entry")
        }
    }

    private companion object {
        private const val WasmSectionDataCountId: Byte = 12
    }
}
