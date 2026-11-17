package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.components.CorruptInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.UnknownInstanceCoreExpression
import org.jetbrains.hexana.wasm.sections.ParseProblemKind
import org.jetbrains.hexana.wasm.sections.WasmParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    @Test
    fun truncated_component_section_header_is_reported_as_parse_problem() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x00,
        )
        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

        assertTrue(provider.canProvideWasmModule())
        val problem = provider.parseProblems.single()
        assertEquals(ParseProblemKind.SECTION_TRUNCATED, problem.kind)
        assertEquals(8, problem.byteOffset)
    }

    @Test
    fun malformed_complete_custom_section_name_is_a_resolver_failure() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x00, 0x05, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10,
        )

        val problem = WasmComponentBinaryProvider(bytes.toCommonByteBuffer()).parseProblems.single()

        assertEquals(ParseProblemKind.SECTION_RESOLVE_FAILURE, problem.kind)
        assertEquals(0, problem.sectionId)
        assertEquals("Custom section", problem.sectionName)
        assertEquals(8, problem.byteOffset)
        assertTrue(problem.message.isNotBlank())
    }

    @Test
    fun truncated_component_instance_entry_preserves_prefix_and_reports_problem() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x05, 0x04, 0x02,
            0x00, 0x00,
            0x00,
        )
        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

        val section = assertNotNull(provider.wasmModule).instanceSection.single()
        assertEquals(1, with(section) { provider.bytes.parseInstancesPartial().first.size })
        assertFailsWith<Exception> { provider.instances }
        val problem = provider.parseProblems.single()
        assertEquals(ParseProblemKind.SECTION_ENTRY_CORRUPT, problem.kind)
        assertEquals(5, problem.sectionId)
        assertEquals(14, problem.byteOffset)
    }

    @Test
    fun malformed_component_core_type_is_reported_at_entry_start() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x03, 0x02, 0x01, 0x00,
        )
        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

        val section = assertNotNull(provider.wasmModule).coreTypesSection.single()
        assertEquals(11, with(section) { provider.bytes.scanTypesPartial().second })
        assertFailsWith<WasmParseException> { with(section) { provider.bytes.scanTypes() } }
        val problem = provider.parseProblems.single()
        assertEquals(ParseProblemKind.SECTION_ENTRY_CORRUPT, problem.kind)
        assertEquals(3, problem.sectionId)
        assertEquals(11, problem.byteOffset)
    }

    @Test
    fun component_parse_problems_are_capped() {
        val unknownEntries = ByteArray(1001) { 0xFF.toByte() }
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x02, 0xEB.toByte(), 0x07,
            0xE9.toByte(), 0x07,
        ) + unknownEntries
        val problems = WasmComponentBinaryProvider(bytes.toCommonByteBuffer()).parseProblems

        assertEquals(1000, problems.size)
        assertTrue(problems.all { it.kind == ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG })
        assertEquals(13, problems.first().byteOffset)
        assertEquals(1012, problems.last().byteOffset)
    }

    @Test
    fun component_parse_problem_cap_preserves_earlier_section_diagnostics() {
        val unknownEntries = ByteArray(1000) { 0xFF.toByte() }
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x03, 0x02, 0x01, 0x00,
            0x02, 0xEA.toByte(), 0x07, 0xE8.toByte(), 0x07,
        ) + unknownEntries

        val problems = WasmComponentBinaryProvider(bytes.toCommonByteBuffer()).parseProblems

        assertEquals(1000, problems.size)
        assertEquals(ParseProblemKind.SECTION_ENTRY_CORRUPT, problems.first().kind)
        assertEquals(11, problems.first().byteOffset)
        assertEquals(999, problems.count { it.kind == ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG })
        assertEquals(problems.map { it.byteOffset }.sorted(), problems.map { it.byteOffset })
    }

    @Test
    fun section_scan_failure_does_not_move_a_preceding_entry_problem() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x02, 0x02, 0x01, 0x7F,
            0x00,
        )
        val problems = WasmComponentBinaryProvider(bytes.toCommonByteBuffer()).parseProblems

        assertEquals(
            listOf(ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG, ParseProblemKind.SECTION_TRUNCATED),
            problems.map { it.kind },
        )
        assertEquals(listOf(11, 12), problems.map { it.byteOffset })
    }

    @Test
    fun component_parse_problems_are_ordered_by_offset_across_entry_and_section_failures() {
        val overlongZero = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00,
        )
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00,
            0x03, 0x02, 0x01, 0x00,
            0x02, 0x09, 0x01, 0x00,
        ) + overlongZero + byteArrayOf(
            0x00,
            0x00,
        )
        val problems = WasmComponentBinaryProvider(bytes.toCommonByteBuffer()).parseProblems

        assertEquals(
            listOf(
                ParseProblemKind.SECTION_ENTRY_CORRUPT,
                ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT,
                ParseProblemKind.SECTION_TRUNCATED,
            ),
            problems.map { it.kind },
        )
        assertEquals(listOf(11, 15, 23), problems.map { it.byteOffset })
    }
}
