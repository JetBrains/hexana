package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.components.CorruptInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.UnknownInstanceCoreExpression
import org.jetbrains.hexana.wasm.sections.ParseProblemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [WasmComponentBinaryProvider.parseProblems]: a component whose
 * core-instance section contains an entry with an unknown tag byte must
 * degrade to an [UnknownInstanceCoreExpression] (consuming only the tag byte)
 * plus one [ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG] problem; known
 * encodings must report no problems.
 */
class WasmComponentParseProblemsTest {

    /**
     * ```
     * \0asm 0x0d 0x00 0x01 0x00     ; magic + component version/layer
     * 02 02 01 ff                   ; core-instance section, one entry, tag 0xFF
     * ```
     */
    private val componentWithUnknownCoreInstanceTag: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
        0x02, 0x02, 0x01, 0xFF.toByte(),
    )

    @Test
    fun unknown_core_instance_tag_degrades_to_unknown_expression() {
        val provider = WasmComponentBinaryProvider(componentWithUnknownCoreInstanceTag.toCommonByteBuffer())
        val instance = provider.coreInstances.single()
        assertIs<UnknownInstanceCoreExpression>(instance)
        assertEquals(0xFF.toByte(), instance.tag)
        assertEquals(11, instance.offset, "tag byte offset in the fixture")
    }

    @Test
    fun unknown_core_instance_tag_is_reported_as_parse_problem() {
        val provider = WasmComponentBinaryProvider(componentWithUnknownCoreInstanceTag.toCommonByteBuffer())
        val problem = provider.parseProblems.single()
        assertEquals(ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG, problem.kind)
        assertEquals(11, problem.byteOffset)
        assertEquals(1, problem.byteSize)
        assertEquals("unknown core-instance tag 0xff", problem.message)
    }

    @Test
    fun corrupt_entry_aborts_section_and_is_reported_as_parse_problem() {
        // 02 06 01 01 01 01 61 7f : one inline-exports entry whose export has
        // an invalid core sort byte (0x7F), failing mid-entry.
        val corrupt = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x02, 0x06, 0x01, 0x01, 0x01, 0x01, 0x61, 0x7F,
        )
        val provider = WasmComponentBinaryProvider(corrupt.toCommonByteBuffer())
        val instance = provider.coreInstances.single()
        assertIs<CorruptInstanceCoreExpression>(instance)
        assertEquals(11, instance.offset, "start of the failing entry")
        assertEquals(5, instance.size, "entry start to section end")

        val problem = provider.parseProblems.single()
        assertEquals(ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT, problem.kind)
        assertEquals(11, problem.byteOffset)
        assertEquals(5, problem.byteSize)
        assertEquals("Invalid component core sort: 7f", problem.message)
    }

    @Test
    fun known_core_instance_encodings_report_no_problems() {
        // 02 04 01 00 00 00 : one instantiate entry (module 0, no args)
        val clean = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x02, 0x04, 0x01, 0x00, 0x00, 0x00,
        )
        val provider = WasmComponentBinaryProvider(clean.toCommonByteBuffer())
        assertEquals(1, provider.coreInstances.size)
        assertTrue(provider.parseProblems.isEmpty(), "clean component must have no parse problems")
    }
}
