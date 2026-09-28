package org.jetbrains.hexana.cli.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.model.inspections.InspectBinaryResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InspectBinaryCliRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun anErrorFailsTheRunAndIsListedWithItsCodeAndBytes() {
        val output = inspect(file("truncated.wasm", TRUNCATED_CODE_SECTION), ToolOutputFormat.PRETTY)

        assertEquals(INSPECT_BINARY_ERRORS_EXIT_CODE, output.exitCode)
        val text = output.text()
        assertTrue(text.contains(": 1 error"), text)
        assertTrue(text.contains("[wasm.section_truncated]"), text)
        assertTrue(text.contains("0x8+0x1"), text)
        assertTrue(text.contains("Runtime targets: none declared"), text)
    }

    @Test
    fun warningsAloneDoNotFailTheRun() {
        val output = inspect(file("unknown-type.wasm", UNKNOWN_TYPE_BYTE), ToolOutputFormat.JSON)

        assertEquals(0, output.exitCode)
        val result = HexanaJson.compact.decodeFromString(InspectBinaryResult.serializer(), output.text())
        assertEquals(0, result.counts.errors)
        assertEquals(1, result.counts.warnings)
        assertEquals("wasm.unknown_type_byte", result.problems.single().code)
        assertEquals(
            listOf("wasm.parse", "native.parse", "wasm.runtime-compatibility"),
            result.inspections.map { it.id },
            "every inspection is listed, including the ones that found nothing",
        )
    }

    @Test
    fun aCleanBinaryReportsNoProblems() {
        val output = inspect(file("empty.wasm", EMPTY_MODULE), ToolOutputFormat.PRETTY)

        assertEquals(0, output.exitCode)
        assertTrue(output.text().contains(": no problems"), output.text())
    }

    @Test
    fun declaredRuntimesEnableTheCompatibilityInspection() {
        val binary = file("sign-ext.wasm", SIGN_EXTENSION_MODULE)
        val targets = file("runtimes.json", """{"targets":[{"runtime":"Chrome","version":"1"}]}""".toByteArray())

        val without = decode(inspect(binary, ToolOutputFormat.JSON))
        assertEquals(emptyList(), without.problems)

        val with = decode(inspect(binary, ToolOutputFormat.JSON, runtimeTargets = targets))
        val problem = with.problems.single()
        assertEquals("wasm.proposal_unsupported", problem.code)
        assertEquals("warning", problem.severity)
        assertEquals(listOf("Chrome 1"), with.runtimeTargets)
    }

    @Test
    fun sarifHasARulePerInspectionAndAByteRegionPerProblem() {
        val output = inspect(file("truncated.wasm", TRUNCATED_CODE_SECTION), ToolOutputFormat.SARIF)
        val rendered = assertIs<RenderedToolOutput.Text>(output.rendered)
        assertTrue(rendered.contentType.startsWith("application/sarif+json"))

        val run = HexanaJson.compact.parseToJsonElement(rendered.text).jsonObject["runs"]!!.jsonArray.single().jsonObject
        val rules = run["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(3, rules.size)
        val result = run["results"]!!.jsonArray.single().jsonObject
        assertEquals("wasm.parse", result["ruleId"]!!.jsonPrimitive.content)
        assertEquals("error", result["level"]!!.jsonPrimitive.content)
        val physical = result["locations"]!!.jsonArray.single().jsonObject["physicalLocation"]!!.jsonObject
        assertTrue(physical["artifactLocation"]!!.jsonObject["uri"]!!.jsonPrimitive.content.endsWith("truncated.wasm"))
        assertEquals(8L, physical["region"]!!.jsonObject["byteOffset"]!!.jsonPrimitive.long)
    }

    @Test
    fun sarifFingerprintsAreStableAcrossRuns() {
        val binary = file("truncated.wasm", TRUNCATED_CODE_SECTION)
        fun fingerprint() = HexanaJson.compact.parseToJsonElement(inspect(binary, ToolOutputFormat.SARIF).text())
            .jsonObject["runs"]!!.jsonArray.single().jsonObject["results"]!!.jsonArray.single()
            .jsonObject["partialFingerprints"]!!.jsonObject["hexanaProblem/v1"]!!.jsonPrimitive.content

        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun tableListsOneRowPerProblem() {
        val text = inspect(file("truncated.wasm", TRUNCATED_CODE_SECTION), ToolOutputFormat.TABLE).text()

        assertTrue(text.lines().first().startsWith("Severity"), text)
        assertTrue(text.contains("wasm.section_truncated"), text)
    }

    @Test
    fun unknownParametersAreRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            standardCli(
                StandardCliRequest(
                    toolName = INSPECT_BINARY_TOOL_NAME,
                    sourceSelections = listOf(ToolInputSourceSelection.Path(file("empty.wasm", EMPTY_MODULE).toString(), "binary")),
                    params = mapOf("severity" to JsonPrimitive("error")),
                    format = ToolOutputFormat.JSON,
                ),
                DefaultHexanaAnalysisService(),
            )
        }
        assertTrue(error.message!!.contains("severity"), error.message)
    }

    @Test
    fun theRendererAdvertisesExactlyTheImplementedFormats() {
        assertEquals(
            setOf(ToolOutputFormat.JSON, ToolOutputFormat.PRETTY, ToolOutputFormat.TABLE, ToolOutputFormat.SARIF),
            standardCliSupportedOutputFormats(INSPECT_BINARY_TOOL_NAME),
        )
    }

    private fun inspect(binary: Path, format: ToolOutputFormat, runtimeTargets: Path? = null): RenderedStandardCliOutput =
        standardCli(
            StandardCliRequest(
                toolName = INSPECT_BINARY_TOOL_NAME,
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = runtimeTargets?.let { mapOf("runtimeTargetsPath" to JsonPrimitive(it.toString())) } ?: emptyMap(),
                format = format,
            ),
            DefaultHexanaAnalysisService(),
        )

    private fun decode(output: RenderedStandardCliOutput): InspectBinaryResult =
        HexanaJson.compact.decodeFromString(InspectBinaryResult.serializer(), output.text())

    private fun RenderedStandardCliOutput.text(): String = assertIs<RenderedToolOutput.Text>(rendered).text

    private fun file(name: String, bytes: ByteArray): Path =
        temporaryFolder.root.toPath().resolve(name).also { Files.write(it, bytes) }

    private companion object {
        val HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val EMPTY_MODULE = HEADER

        /** A code section header declaring 16 payload bytes, followed by one. */
        val TRUNCATED_CODE_SECTION = HEADER + byteArrayOf(0x0A, 0x10, 0x01)

        /** invalid/binary.63.wasm: the type section declares two functypes but holds one. */
        val UNKNOWN_TYPE_BYTE = HEADER + byteArrayOf(0x01, 0x07, 0x02, 0x60, 0x00, 0x00)

        /** One function: `i32.const 0; i32.extend8_s; drop` — the sign-extension proposal. */
        val SIGN_EXTENSION_MODULE = HEADER + byteArrayOf(
            0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
            0x03, 0x02, 0x01, 0x00,
            0x0A, 0x08, 0x01, 0x06, 0x00, 0x41, 0x00, 0xC0.toByte(), 0x1A, 0x0B,
        )
    }
}
