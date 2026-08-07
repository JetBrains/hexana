package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.sections.ParseProblemKind
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [WasmBinaryProvider.parseProblems]: a module containing an
 * opcode the parser does not know (custom-descriptors `struct.new_desc`,
 * `0xFB 0x32`) must degrade to a partial opcode list plus one
 * [UnparsedOpCode] tail, surfaced as a [ParseProblemKind.UNKNOWN_OPCODE]
 * problem; a fully supported module must report no problems.
 */
class WasmParseProblemsTest {

    /**
     * ```
     * \0asm 0x01 0x00 0x00 0x00     ; magic + version 1
     * 01 04 01 60 00 00             ; type[0] = (func)
     * 03 02 01 00                   ; function[0] uses type 0
     * 0a 08 01 06                   ; code section, one entry, funSize=6
     *   00                          ; no locals
     *   41 00                       ; i32.const 0
     *   fb 32                       ; struct.new_desc (unknown to the parser)
     *   0b                          ; end
     * ```
     */
    private val wasmWithUnknownOpcode: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x08, 0x01, 0x06, 0x00, 0x41, 0x00, 0xFB.toByte(), 0x32, 0x0B,
    )

    private val minimalCleanWasm: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B,
    )

    @Test
    fun unknown_opcode_keeps_parsed_prefix_and_appends_unparsed_tail() {
        val provider = WasmBinaryProvider(wasmWithUnknownOpcode.toCommonByteBuffer())
        val record = provider.codeRecords.single()
        // i32.const 0 must survive; the unknown 0xFB 0x32 becomes the tail.
        assertTrue(record.opCodes.size >= 2, "expected parsed prefix + tail, got ${record.opCodes}")
        assertTrue(record.opCodes.last() is UnparsedOpCode, "last opcode should be the unparsed tail")
        assertTrue(record.opCodes.dropLast(1).none { it is UnparsedOpCode })
    }

    @Test
    fun unknown_opcode_is_reported_as_parse_problem() {
        val provider = WasmBinaryProvider(wasmWithUnknownOpcode.toCommonByteBuffer())
        val problem = provider.parseProblems.single()
        assertEquals(ParseProblemKind.UNKNOWN_OPCODE, problem.kind)
        assertEquals(0, problem.funcIdx, "module has no imports, so funcIdx 0")
        // The tail starts at the 0xFB byte (offset 25 in the fixture).
        assertEquals(25, problem.byteOffset)
        assertTrue(problem.byteSize > 0)
    }

    @Test
    fun clean_module_reports_no_problems() {
        val provider = WasmBinaryProvider(minimalCleanWasm.toCommonByteBuffer())
        assertTrue(provider.parseProblems.isEmpty(), "clean module must have no parse problems")
    }
}