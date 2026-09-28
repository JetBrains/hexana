package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.structural.IdentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.StructuralKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseStatus
import org.jetbrains.hexana.mcp.model.structural.UnidentifiedStructuralParseResult
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidateAndExplainQueryTest {
    @Test
    fun `core evidence includes features proposals and explicit runtime order`() {
        val evidence = MEMORY64_CORE.readValidateAndExplainEvidence(listOf("Wasmtime", "graalwasm"))
        val structural = assertIs<IdentifiedStructuralParseResult>(evidence.structural)
        val core = assertNotNull(evidence.core)

        assertEquals(StructuralKind.CORE_MODULE, structural.summary.kind)
        assertEquals(StructuralParseStatus.CLEAN, structural.status)
        assertEquals(1, structural.summary.coreModule.memories)
        assertTrue(core.enabledFeatures.features.memory64)
        assertEquals(listOf("memory64"), core.requiredProposals.proposals.map { it.proposal })
        assertEquals(listOf("wasmtime", "graalwasm"), core.runtimeCompatibility.map { it.runtime })
        assertEquals(listOf("memory64"), core.runtimeCompatibility[1].unsupportedProposals)
    }

    @Test
    fun `component skips core services and does not consult supplied targets`() {
        val evidence = COMPONENT_HEADER.readValidateAndExplainEvidence(listOf("invalid-runtime", "invalid-runtime"))

        val structural = assertIs<IdentifiedStructuralParseResult>(evidence.structural)
        assertEquals(StructuralKind.COMPONENT, structural.summary.kind)
        assertNull(evidence.core)
    }

    @Test
    fun `unidentified input has no core evidence`() {
        val evidence = byteArrayOf(1, 2, 3, 4).readValidateAndExplainEvidence(listOf("invalid-runtime"))

        assertIs<UnidentifiedStructuralParseResult>(evidence.structural)
        assertNull(evidence.core)
    }

    @Test
    fun `partial core preserves scan evidence`() {
        val evidence = (CORE_HEADER + byteArrayOf(0)).readValidateAndExplainEvidence(emptyList())
        val structural = assertIs<IdentifiedStructuralParseResult>(evidence.structural)

        assertEquals(StructuralParseStatus.PARTIAL, structural.status)
        assertEquals(8, structural.sectionScanFailures.single().byteOffset)
        assertNotNull(evidence.core)
    }

    @Test
    fun `partial core retains detected proposals without a compatibility verdict`() {
        val evidence = (MEMORY64_CORE + byteArrayOf(0))
            .readValidateAndExplainEvidence(listOf("wasmtime"))
        val structural = assertIs<IdentifiedStructuralParseResult>(evidence.structural)
        val core = assertNotNull(evidence.core)

        assertEquals(StructuralParseStatus.PARTIAL, structural.status)
        assertTrue(core.enabledFeatures.features.memory64)
        assertEquals(listOf("memory64"), core.requiredProposals.proposals.map { it.proposal })
        assertTrue(core.runtimeCompatibility.isEmpty())
        val malformedMemory = (CORE_HEADER + byteArrayOf(5, 0))
            .readValidateAndExplainEvidence(listOf("wasmtime"))
        assertTrue(assertNotNull(malformedMemory.core).runtimeCompatibility.isEmpty())
        assertFailsWith<org.jetbrains.hexana.mcp.HexanaAnalysisException> {
            (CORE_HEADER + byteArrayOf(5, 0)).readValidateAndExplainEvidence(listOf("unknown-runtime"))
        }
    }

    @Test
    fun `malformed decoded section remains a partial report source`() {
        val evidence = (CORE_HEADER + byteArrayOf(1, 2, 1, 0xff.toByte()))
            .readValidateAndExplainEvidence(emptyList())
        val structural = assertIs<IdentifiedStructuralParseResult>(evidence.structural)

        assertEquals(StructuralParseStatus.PARTIAL, structural.status)
        assertEquals(1, structural.parseProblems.single().sectionId)
        assertNotNull(evidence.core)
    }

    @Test
    fun `malformed code vector keeps structural evidence when feature extraction fails`() {
        val evidence = (CORE_HEADER + byteArrayOf(0x0a, 0x01, 0x01))
            .readValidateAndExplainEvidence(listOf("wasmtime"))
        val structural = assertIs<IdentifiedStructuralParseResult>(evidence.structural)

        assertEquals(StructuralParseStatus.PARTIAL, structural.status)
        assertTrue(evidence.featureExtractionFailure.orEmpty().contains("Vector count exceeds its boundary"))
        assertNull(evidence.core)
        assertFailsWith<org.jetbrains.hexana.mcp.HexanaAnalysisException> {
            (CORE_HEADER + byteArrayOf(0x0a, 0x01, 0x01))
                .readValidateAndExplainEvidence(listOf("unknown-runtime"))
        }
    }

    @Test
    fun `duplicate core targets are rejected after normalization`() {
        assertFailsWith<IllegalArgumentException> {
            CORE_HEADER.readValidateAndExplainEvidence(listOf(" Wasmtime ", "wasmtime"))
        }
    }

    private companion object {
        val CORE_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        val COMPONENT_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0)
        val MEMORY64_CORE = CORE_HEADER + byteArrayOf(5, 3, 1, 4, 1)
    }
}
