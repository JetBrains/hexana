package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.components.CheckComponentCompatibilityExplicitResult
import org.jetbrains.hexana.mcp.model.components.CheckComponentCompatibilityResult
import org.jetbrains.hexana.mcp.model.components.ResolveComponentDependenciesResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentWitReportTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun coreModuleHasReviewedNotApplicableStagesAndNeverChecksDependencies() {
        val bytes = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        var compatibilityCalls = 0
        val service = object : HexanaAnalysisService by DefaultHexanaAnalysisService() {
            override fun checkComponentCompatibilityExplicit(input: WasmInput, dependencyPaths: List<String>): CheckComponentCompatibilityExplicitResult {
                compatibilityCalls++
                error("Core module must not check dependencies")
            }
        }
        val report = render(WasmInput.RawBytes(bytes), WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()),
            dependencyPaths = listOf("missing.component.wasm"), service = service)

        assertEquals(0, compatibilityCalls)
        assertFalse(report.obj("component").getValue("is_component").jsonPrimitive.content.toBoolean())
        assertEquals("null", report.obj("component").getValue("layer").toString())
        assertEquals("not_applicable", report.obj("types").str("stage"))
        assertTrue(report.obj("types").arr("items").isEmpty())
        assertEquals("not_applicable", report.obj("dependencies").str("stage"))
        assertEquals("missing.component.wasm", report.obj("dependencies").arr("paths").single().jsonPrimitive.content)
        assertEquals("not_available", report.obj("wit").str("status"))
        assertFalse(report.toString().contains("wit_source"))
    }

    @Test
    fun malformedInputIsNotReportedAsAnInapplicableCoreModule() {
        val bytes = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0, 0x0a, 1, 0x80.toByte())
        val error = assertFailsWith<Exception> {
            ComponentWitReport.compose(
                WasmInput.RawBytes(bytes), WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()),
                "test-version",
            )
        }
        assertTrue(error.message.orEmpty().contains("Truncated"))
    }

    @Test
    fun componentWithoutDependenciesKeepsFlatGraphAndSkipsCompatibility() {
        val component = fixture("calculator.wasm")
        var compatibilityCalls = 0
        val service = object : HexanaAnalysisService by DefaultHexanaAnalysisService() {
            override fun checkComponentCompatibilityExplicit(input: WasmInput, dependencyPaths: List<String>): CheckComponentCompatibilityExplicitResult {
                compatibilityCalls++
                error("No dependency was requested")
            }
        }
        val report = renderComponent(component, service = service)

        assertEquals(0, compatibilityCalls)
        assertEquals("true", report.obj("component").getValue("is_component").jsonPrimitive.content)
        assertEquals(1, report.obj("component").getValue("layer").jsonPrimitive.content.toInt())
        assertTrue(report.obj("component").arr("imports").isNotEmpty())
        assertEquals("completed", report.obj("types").str("stage"))
        val types = report.obj("types")
        val roots = types.arr("items").map { it.jsonObject.str("root_node_id") }
        val nodes = types.arr("nodes").map { it.jsonObject }
        assertTrue(roots.isNotEmpty())
        assertTrue(roots.all { it.startsWith("type/") && nodes.any { node -> node.str("node_id") == it } })
        assertTrue(nodes.all { node -> node.getValue("attributes") is JsonObject })
        assertEquals(nodes.map { it.str("node_id") }.sorted(), nodes.map { it.str("node_id") })
        assertEquals("not_requested", report.obj("dependencies").str("stage"))
        assertTrue(report.obj("dependencies").arr("paths").isEmpty())
        assertEquals("null", report.obj("dependencies").getValue("compatibility").toString())
        assertEquals("not_available", report.obj("wit").str("status"))
        assertFalse(report.toString().contains("\"package\""))
        assertFalse(report.toString().contains("\"world\""))
    }

    @Test
    fun normalizedDuplicateDependenciesAreRejectedBeforeCompatibility() {
        val component = fixture("calculator.wasm")
        var compatibilityCalls = 0
        val service = object : HexanaAnalysisService by DefaultHexanaAnalysisService() {
            override fun checkComponentCompatibilityExplicit(input: WasmInput, dependencyPaths: List<String>): CheckComponentCompatibilityExplicitResult {
                compatibilityCalls++
                error("Duplicate paths must be rejected first")
            }
        }
        val dependency = temporaryFolder.root.toPath().resolve("provider.wasm")

        assertFailsWith<IllegalArgumentException> {
            renderComponent(component, listOf(dependency.toString(), dependency.parent.resolve("deps/../provider.wasm").toString()), service)
        }
        assertEquals(0, compatibilityCalls)
    }

    @Test
    fun acceptedProviderWithoutExportsKeepsItsClassificationWhenTrimmed() {
        val root = temporaryFolder.newFolder("empty-provider").toPath()
        val provider = Files.write(root.resolve("a-empty.component.wasm"), byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0))
        val missing = root.resolve("z-missing.wasm")
        val component = fixture("calculator.wasm")
        val report = renderComponent(component, listOf(provider.toString(), missing.toString()))
        val compatibility = report.obj("dependencies").obj("compatibility")

        assertEquals(provider.toString(), compatibility.arr("accepted_providers").single().jsonObject.str("dependency_path"))
        assertTrue(compatibility.arr("candidate_providers").isEmpty())
        assertEquals(missing.toString(), compatibility.arr("rejected_dependencies").single().jsonObject.str("dependency_path"))

        val limited = renderComponent(component, listOf(provider.toString(), missing.toString()), maxItems = 1)
        val retainedPaths = limited.obj("dependencies").arr("paths").map { it.jsonPrimitive.content }
        val limitedCompatibility = limited.obj("dependencies").obj("compatibility")
        val classifiedPaths = limitedCompatibility.arr("accepted_providers").map { it.jsonObject.str("dependency_path") } +
            limitedCompatibility.arr("rejected_dependencies").map { it.jsonObject.str("dependency_path") }
        assertEquals(listOf(provider.toString()), retainedPaths)
        assertEquals(retainedPaths, classifiedPaths)
    }

    @Test
    fun explicitProvidersAndAllRejectionsRemainVisibleWithoutLegacyCalls() {
        val root = temporaryFolder.newFolder("dependencies").toPath()
        val component = Files.copy(fixture("calculator.wasm"), root.resolve("calculator.wasm"))
        val provider = Files.copy(fixture("add.component.wasm"), root.resolve("add.component.wasm"))
        val core = Files.write(root.resolve("core.wasm"), byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0))
        val malformed = Files.write(root.resolve("malformed.wasm"), byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0, 0x0a, 1, 0x80.toByte()))
        val maxBytes = maxOf(Files.size(component), Files.size(provider)).toInt()
        val oversized = Files.write(root.resolve("oversized.wasm"), ByteArray(maxBytes + 1))
        val paths = listOf(provider.toString(), "", root.resolve("missing.wasm").toString(), oversized.toString(), malformed.toString(), core.toString())
        var explicitCalls = 0
        val delegate = DefaultHexanaAnalysisService(limits = HexanaResourceLimits(maxFileWasmBytes = maxBytes))
        val service = object : HexanaAnalysisService by delegate {
            override fun checkComponentCompatibilityExplicit(input: WasmInput, dependencyPaths: List<String>): CheckComponentCompatibilityExplicitResult {
                explicitCalls++
                return delegate.checkComponentCompatibilityExplicit(input, dependencyPaths)
            }
            override fun resolveComponentDependencies(input: WasmInput, projectRoot: String?, scanRoots: List<String>): ResolveComponentDependenciesResult =
                error("Legacy dependency discovery was invoked")
            override fun checkComponentCompatibility(input: WasmInput, dependencyPaths: List<String>, projectRoot: String?, previousPath: String?, dependencyLockPath: String?): CheckComponentCompatibilityResult =
                error("Legacy compatibility was invoked")
        }
        val rendered = renderResult(component, paths, service)
        val report = rendered.semanticReport
        val compatibility = report.obj("dependencies").obj("compatibility")

        assertEquals(1, explicitCalls)
        assertEquals(1, rendered.exitCode)
        assertEquals("completed", report.obj("dependencies").str("stage"))
        assertEquals(0, compatibility.getValue("directory_scan_count").jsonPrimitive.content.toInt())
        assertTrue(compatibility.arr("matches").isNotEmpty())
        assertTrue(compatibility.arr("missing_imports").isNotEmpty())
        assertEquals(setOf("invalid_path", "unreadable", "oversized", "malformed", "not_component"),
            compatibility.arr("rejected_dependencies").map { it.jsonObject.str("reason") }.toSet())
        assertEquals(paths.size, report.obj("dependencies").arr("paths").size)
        assertTrue(report.arr("findings").any { it.jsonObject.str("code") == "oversized" })
    }

    @Test
    fun ambiguousProviderEvidenceAndAtomicTypeTrimming() {
        val root = temporaryFolder.newFolder("ambiguous").toPath()
        val component = Files.copy(fixture("calculator.wasm"), root.resolve("calculator.wasm"))
        val first = Files.copy(fixture("add.component.wasm"), root.resolve("a.wasm"))
        val second = Files.copy(fixture("add.component.wasm"), root.resolve("b.wasm"))
        val report = renderComponent(component, listOf(first.toString(), second.toString()))
        val compatibility = report.obj("dependencies").obj("compatibility")
        assertTrue(compatibility.arr("issues").any { it.jsonObject.str("reason") == "ambiguous_provider" })
        assertTrue(compatibility.arr("candidate_providers").isNotEmpty())

        val limited = renderComponent(component, maxItems = 1)
        val items = limited.obj("types").arr("items")
        val nodes = limited.obj("types").arr("nodes")
        val retainedRoots = items.map { it.jsonObject.str("root_node_id") }
        assertEquals(1, retainedRoots.size)
        assertTrue(nodes.all { node -> retainedRoots.any { root -> node.jsonObject.str("node_id") == root || node.jsonObject.str("node_id").startsWith("$root/") } })
        assertEquals("true", limited.obj("truncation").getValue("truncated").jsonPrimitive.content)
        assertTrue(limited.arr("findings").any { it.jsonObject.str("code") == "output_truncated" })
    }

    private fun renderComponent(path: Path, dependencyPaths: List<String> = emptyList(), service: HexanaAnalysisService = DefaultHexanaAnalysisService(), maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS): JsonObject =
        renderResult(path, dependencyPaths, service, maxItems).semanticReport

    private fun renderResult(path: Path, dependencyPaths: List<String>, service: HexanaAnalysisService, maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS): WasmToolsReportRenderResult.Rendered {
        val input = WasmInput.FilePath(path.toString())
        return renderResult(input, WasmToolsReportInput(ReportInputKind.PATH, path.toString(), Files.size(path)), dependencyPaths, service, maxItems)
    }

    private fun render(input: WasmInput, reportInput: WasmToolsReportInput, dependencyPaths: List<String>, service: HexanaAnalysisService): JsonObject =
        renderResult(input, reportInput, dependencyPaths, service).semanticReport

    private fun renderResult(input: WasmInput, reportInput: WasmToolsReportInput, dependencyPaths: List<String>, service: HexanaAnalysisService, maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS): WasmToolsReportRenderResult.Rendered {
        val request = ComponentWitReport.compose(input, reportInput, "test-version", dependencyPaths, service, maxItems = maxItems)
        return WasmToolsReportRenderer.render(request) as WasmToolsReportRenderResult.Rendered
    }

    private fun fixture(name: String): Path {
        var root = Path.of("").toAbsolutePath()
        while (root.parent != null) {
            if (Files.exists(root.resolve("settings.gradle.kts")) && Files.exists(root.resolve("wasmParser"))) {
                return root.resolve("wasmParser/src/commonTest/resources/component/$name")
            }
            root = root.parent
        }
        error("Repository root not found")
    }

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray
    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
}
