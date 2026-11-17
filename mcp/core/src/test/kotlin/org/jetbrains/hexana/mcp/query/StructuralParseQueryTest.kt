package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.structural.IdentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.StructuralKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseFailureKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseStatus
import org.jetbrains.hexana.mcp.model.structural.StructuralSummaryStage
import org.jetbrains.hexana.mcp.model.structural.UnidentifiedStructuralParseResult
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class StructuralParseQueryTest {
    @Test
    fun `core module has completed core summary and stable serialization`() {
        val result = assertIs<IdentifiedStructuralParseResult>(CORE_WITH_FUNCTION.toCommonByteBuffer().readStructuralParse())

        assertEquals(StructuralKind.CORE_MODULE, result.summary.kind)
        assertEquals(StructuralParseStatus.CLEAN, result.status)
        assertEquals(1, result.summary.coreModule.functions)
        assertEquals(1, result.summary.coreModule.types)
        assertEquals(StructuralSummaryStage.COMPLETED, result.summary.coreModule.stage)
        assertEquals(StructuralSummaryStage.NOT_APPLICABLE, result.summary.component.stage)
        assertNull(result.summary.component.imports)
        val serialized = Json.encodeToString(result)
        assertTrue(serialized.contains("\"section_count\":2"))
        assertTrue(serialized.contains("\"core_module\""))
        assertTrue(serialized.contains("\"nested_components\":null"))
    }

    @Test
    fun `component has completed component summary and inapplicable core counts`() {
        val result = assertIs<IdentifiedStructuralParseResult>(COMPONENT_WITH_TYPE.toCommonByteBuffer().readStructuralParse())

        assertEquals(StructuralKind.COMPONENT, result.summary.kind)
        assertEquals(StructuralParseStatus.CLEAN, result.status)
        assertEquals(1, result.summary.component.types)
        assertEquals(StructuralSummaryStage.COMPLETED, result.summary.component.stage)
        assertEquals(StructuralSummaryStage.NOT_APPLICABLE, result.summary.coreModule.stage)
        assertNull(result.summary.coreModule.functions)
    }

    @Test
    fun `component summary exposes unsigned high bit layer metadata`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            COMPONENT_HIGH_BIT_LAYER.toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(0x0081, result.summary.layer)
    }

    @Test
    fun `malformed truncated and unsupported headers remain distinct failures`() {
        assertEquals(
            StructuralParseFailureKind.MALFORMED_MAGIC,
            assertIs<UnidentifiedStructuralParseResult>(byteArrayOf(1, 2, 3, 4).toCommonByteBuffer().readStructuralParse()).failure.kind,
        )
        assertEquals(
            StructuralParseFailureKind.TRUNCATED_HEADER,
            assertIs<UnidentifiedStructuralParseResult>(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0).toCommonByteBuffer().readStructuralParse()).failure.kind,
        )
        val unsupported = assertIs<UnidentifiedStructuralParseResult>(
            byteArrayOf(0, 0x61, 0x73, 0x6d, 2, 0, 0, 0).toCommonByteBuffer().readStructuralParse()
        ).failure
        assertEquals(StructuralParseFailureKind.UNSUPPORTED_VERSION_LAYER, unsupported.kind)
        assertEquals(2, unsupported.version)
        assertEquals(0, unsupported.layer)
        listOf(
            CORE_HEADER.copyOf().also { it[6] = 1 },
            COMPONENT_HEADER.copyOf().also { it[6] = 0 },
        ).forEach { bytes ->
            assertEquals(
                StructuralParseFailureKind.UNSUPPORTED_VERSION_LAYER,
                assertIs<UnidentifiedStructuralParseResult>(bytes.toCommonByteBuffer().readStructuralParse()).failure.kind,
            )
        }
    }

    @Test
    fun `header only core module is clean`() {
        val result = assertIs<IdentifiedStructuralParseResult>(CORE_HEADER.toCommonByteBuffer().readStructuralParse())

        assertEquals(StructuralParseStatus.CLEAN, result.status)
        assertEquals(0, result.summary.sectionCount)
    }

    @Test
    fun `recognized partial module retains scan failure unresolved sections and parse problems`() {
        val scanFailure = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x00)).toCommonByteBuffer().readStructuralParse()
        )
        assertEquals(0, scanFailure.summary.sectionCount)
        assertEquals(8, scanFailure.sectionScanFailures.single().byteOffset)
        assertEquals(8, scanFailure.parseProblems.single().byteOffset)

        val unresolved = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x40, 0x00)).toCommonByteBuffer().readStructuralParse()
        ).unresolvedSections.single()
        assertEquals(0x40, unresolved.sectionId)
        assertEquals(8, unresolved.byteOffset)
        assertEquals(2, unresolved.byteSize)

        val malformedTypes = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x01, 0x05, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f))
                .toCommonByteBuffer().readStructuralParse()
        )
        assertEquals(StructuralKind.CORE_MODULE, malformedTypes.summary.kind)
        assertEquals(StructuralParseStatus.PARTIAL, malformedTypes.status)
        assertEquals(StructuralSummaryStage.COMPLETED, malformedTypes.summary.coreModule.stage)
        assertEquals(10, malformedTypes.parseProblems.single().byteOffset)
    }

    @Test
    fun `component scan and provider diagnostics remain observable`() {
        val scanFailure = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x00)).toCommonByteBuffer().readStructuralParse()
        )
        assertEquals(StructuralKind.COMPONENT, scanFailure.summary.kind)
        assertEquals(8, scanFailure.sectionScanFailures.single().byteOffset)

        val problem = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x02, 0x02, 0x01, 0xff.toByte())).toCommonByteBuffer().readStructuralParse()
        ).parseProblems.single()
        assertEquals("UNKNOWN_CORE_INSTANCE_TAG", problem.kind)
        assertEquals(2, problem.sectionId)
        assertEquals(11, problem.byteOffset)
    }

    @Test
    fun `component malformed custom section name is a resolver failure`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(
                0x00, 0x05, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10,
            )).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals("SECTION_RESOLVE_FAILURE", result.parseProblems.single().kind)
    }

    @Test
    fun `malformed applicable core memory retains zero count and partial evidence`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x05, 0x00)).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(0, result.summary.coreModule.memories)
        assertEquals(5, result.parseProblems.single().sectionId)
        assertEquals(10, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `core structural function count does not require type resolution`() {
        val bytes = CORE_HEADER + byteArrayOf(0x03, 0x02, 0x01, 0x00)
        val result = assertIs<IdentifiedStructuralParseResult>(
            bytes.toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(1, result.summary.coreModule.functions)
    }

    @Test
    fun `core structural type count includes every recursive group entry`() {
        val bytes = CORE_HEADER + byteArrayOf(
            0x01, 0x09,
            0x01,
            0x4e, 0x02,
            0x60, 0x00, 0x00,
            0x60, 0x00, 0x00,
        )
        val result = assertIs<IdentifiedStructuralParseResult>(
            bytes.toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.CLEAN, result.status)
        assertEquals(2, result.summary.coreModule.types)
    }

    @Test
    fun `core structural globals exclude imported globals`() {
        val bytes = CORE_HEADER + byteArrayOf(0x02, 0x06, 0x01, 0x00, 0x00, 0x03, 0x7f, 0x00)
        val result = assertIs<IdentifiedStructuralParseResult>(
            bytes.toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(0, result.summary.coreModule.globals)
    }

    @Test
    fun `core structural vector count retains a valid prefix`() {
        val bytes = CORE_HEADER + byteArrayOf(0x03, 0x03, 0x02, 0x00, 0x80.toByte())
        val result = assertIs<IdentifiedStructuralParseResult>(
            bytes.toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(1, result.summary.coreModule.functions)
        assertEquals(StructuralParseStatus.PARTIAL, result.status)
    }

    @Test
    fun `truncated type payload after a complete vector remains partial`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x01, 0x07, 0x01, 0x60, 0x00, 0x00))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(1, result.summary.coreModule.types)
        assertEquals(8, result.sectionScanFailures.single().byteOffset)
        assertEquals("SECTION_TRUNCATED", result.parseProblems.single().kind)
    }

    @Test
    fun `malformed applicable component import retains zero count and partial evidence`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x0a, 0x05, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(0, result.summary.component.imports)
        assertEquals(10, result.parseProblems.single().sectionId)
        assertEquals(10, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `component core instance with an unreadable vector remains partial`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x02, 0x00)).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(2, result.parseProblems.single().sectionId)
        assertEquals(10, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `component instance with an unreadable entry remains partial`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x05, 0x04, 0x02, 0x00, 0x00, 0x00))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(5, result.parseProblems.single().sectionId)
        assertEquals(14, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `component core type with an unreadable entry remains partial`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x03, 0x02, 0x01, 0x00))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(3, result.parseProblems.single().sectionId)
        assertEquals(11, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `truncated component submodule retains its section and remains partial`() {
        listOf(0x01.toByte(), 0x04.toByte()).forEach { sectionId ->
            val result = assertIs<IdentifiedStructuralParseResult>(
                (COMPONENT_HEADER + byteArrayOf(sectionId, 0x40, 0x00, 0x61, 0x73, 0x6D))
                    .toCommonByteBuffer().readStructuralParse()
            )

            assertEquals(StructuralParseStatus.PARTIAL, result.status)
            assertEquals(1, result.summary.sectionCount)
            assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
            assertEquals(listOf(8), result.parseProblems.map { it.byteOffset })
            assertEquals(0, result.summary.component.nestedComponents)
        }
    }

    @Test
    fun `component structural diagnostics are capped before reporting`() {
        val unknownEntries = ByteArray(1001) { 0xFF.toByte() }
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(
                0x02, 0xEB.toByte(), 0x07,
                0xE9.toByte(), 0x07,
            ) + unknownEntries).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(1000, result.parseProblems.size)
        assertEquals(13, result.parseProblems.first().byteOffset)
        assertEquals(1012, result.parseProblems.last().byteOffset)
    }

    @Test
    fun `component type recovery retains a prefix from the same section`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x07, 0x02, 0x02, 0x7f))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(1, result.summary.component.types)
        assertEquals(7, result.parseProblems.single().sectionId)
        assertEquals(12, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `oversized component variant label remains partial`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x07, 0x08, 0x01, 0x71, 0x01, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(0, result.summary.component.types)
        assertEquals(7, result.parseProblems.single().sectionId)
        assertEquals(11, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `unsupported component type discriminator is partial rather than an error`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x07, 0x02, 0x01, 0x00)).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(0, result.summary.component.types)
        assertEquals(7, result.parseProblems.single().sectionId)
    }

    @Test
    fun `unsupported nested component type discriminator is partial rather than an error`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x07, 0x05, 0x01, 0x41, 0x01, 0x03, 0x02))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(0, result.summary.component.types)
        assertEquals(7, result.parseProblems.single().sectionId)
    }

    @Test
    fun `legacy component queries do not silently treat a malformed import as empty`() {
        val provider = WasmComponentBinaryProvider((COMPONENT_HEADER + byteArrayOf(0x0a, 0x00)).toCommonByteBuffer())

        assertFailsWith<Exception> { provider.readComponentSurface() }
        assertFailsWith<Exception> {
            provider.checkComponentCompatibility(null, emptyList(), null, null, null)
        }
        val structural = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x0a, 0x00)).toCommonByteBuffer().readStructuralParse()
        )
        assertEquals(StructuralParseStatus.PARTIAL, structural.status)
        assertEquals(0, structural.summary.component.imports)
        assertEquals(10, structural.parseProblems.single().byteOffset)
    }

    @Test
    fun `malformed nested component retains partial evidence when no exception is thrown`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + byteArrayOf(0x04, 0x04, 1, 2, 3, 4)).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(0, result.summary.component.nestedComponents)
        assertEquals(4, result.parseProblems.single().sectionId)
        assertEquals(10, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `declared custom payload past the buffer remains identified and partial`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x00, 0x7f, 0x00)).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        assertEquals(0, result.summary.sectionCount)
    }

    @Test
    fun `malformed section size encodings remain identified and partial`() {
        listOf(
            byteArrayOf(0x08, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10),
            byteArrayOf(0x08, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00),
        ).forEach { section ->
            val result = assertIs<IdentifiedStructuralParseResult>(
                (CORE_HEADER + section).toCommonByteBuffer().readStructuralParse()
            )

            assertEquals(StructuralParseStatus.PARTIAL, result.status)
            assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        }
    }

    @Test
    fun `custom section name cannot consume the following section`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(0x00, 0x01, 0x03, 0x01, 0x01, 0x00))
                .toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(listOf(8), result.sectionScanFailures.map { it.byteOffset })
        assertEquals(0, result.summary.customSectionCount)
        assertEquals(2, result.summary.sectionCount)
    }

    @Test
    fun `core import name cannot consume a following section`() {
        val result = assertIs<IdentifiedStructuralParseResult>(
            (CORE_HEADER + byteArrayOf(
                0x02, 0x06, 0x02, 0x00, 0x00, 0x00, 0x00, 0x01,
                0x03, 0x04, 0x03, 0x01, 0x01, 0x01,
                0x00, 0x01, 0x00,
            )).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, result.status)
        assertEquals(3, result.summary.coreModule.functions)
        assertEquals(2, result.parseProblems.single().sectionId)
        assertEquals(15, result.parseProblems.single().byteOffset)
    }

    @Test
    fun `component summary counts every valid nested component section`() {
        val nested = componentSection(COMPONENT_HEADER)
        val result = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + nested + nested).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.CLEAN, result.status)
        assertEquals(2, result.summary.component.nestedComponents)
    }

    @Test
    fun `component summary retains valid nested component count around malformed sections`() {
        val valid = componentSection(COMPONENT_HEADER)
        val malformed = componentSection(byteArrayOf(1, 2, 3, 4))

        val malformedFirst = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + malformed + valid).toCommonByteBuffer().readStructuralParse()
        )
        val malformedSecond = assertIs<IdentifiedStructuralParseResult>(
            (COMPONENT_HEADER + valid + malformed).toCommonByteBuffer().readStructuralParse()
        )

        assertEquals(StructuralParseStatus.PARTIAL, malformedFirst.status)
        assertEquals(1, malformedFirst.summary.component.nestedComponents)
        assertEquals(listOf(10), malformedFirst.parseProblems.map { it.byteOffset })
        assertEquals(StructuralParseStatus.PARTIAL, malformedSecond.status)
        assertEquals(1, malformedSecond.summary.component.nestedComponents)
        assertEquals(listOf(20), malformedSecond.parseProblems.map { it.byteOffset })
    }

    @Test
    fun `provider diagnostics retain fields and are ordered by byte offset`() {
        val result = assertIs<IdentifiedStructuralParseResult>(TYPE_FAILURE_THEN_UNKNOWN_OPCODE.toCommonByteBuffer().readStructuralParse())

        assertEquals(listOf(10, 17), result.parseProblems.map { it.byteOffset })
        assertEquals("UNKNOWN_TYPE_BYTE", result.parseProblems[0].kind)
        assertEquals(1, result.parseProblems[0].sectionId)
        assertEquals("Types", result.parseProblems[0].sectionName)
        assertEquals("UNKNOWN_OPCODE", result.parseProblems[1].kind)
        assertEquals(10, result.parseProblems[1].sectionId)
        assertEquals(0, result.parseProblems[1].functionIndex)
        assertNull(result.parseProblems[1].typeIndex)
        assertTrue(result.parseProblems.all { it.byteSize > 0 && it.message.isNotBlank() })
    }

    private companion object {
        val CORE_HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)

        val CORE_WITH_FUNCTION = CORE_HEADER + byteArrayOf(
            0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
            0x03, 0x02, 0x01, 0x00,
        )

        val COMPONENT_WITH_TYPE = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00,
            0x07, 0x02, 0x01, 0x7f,
        )

        val COMPONENT_HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00)

        val COMPONENT_HIGH_BIT_LAYER = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x81.toByte(), 0x00,
        )

        val TYPE_FAILURE_THEN_UNKNOWN_OPCODE = CORE_HEADER + byteArrayOf(
            0x01, 0x00,
            0x0a, 0x08, 0x01, 0x06, 0x00, 0x41, 0x00, 0xfb.toByte(), 0x32, 0x0b,
        )

        fun componentSection(payload: ByteArray): ByteArray =
            byteArrayOf(0x04, payload.size.toByte()) + payload
    }
}
