package org.jetbrains.hexana.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolvedToolCatalogReportTest {
    @Test
    fun `report JSON and text are byte deterministic in canonical order`() {
        val report = ResolvedToolCatalogReport()
        assertEquals(report.json(), report.json())
        assertEquals(report.text(), report.text())
        assertTrue(IDENTITY_TOKEN.find(report.json()) == null)
        assertTrue(IDENTITY_TOKEN.find(report.text()) == null)
        val tools = Json.parseToJsonElement(report.json()).jsonObject.getValue("tools").jsonArray
        assertEquals("2", Json.parseToJsonElement(report.json()).jsonObject.getValue("formatVersion").jsonPrimitive.content)
        assertEquals(HexanaTools.map { it.name }, tools.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun `report keeps canonical order when resolved authority inputs are shuffled`() {
        val shuffledProduct = HexanaMcpProductSpec.copy(
            profiles = HexanaMcpProductSpec.profiles.reversed().map { it.copy(toolIds = it.toolIds.reversed()) },
        )
        val shuffledAuthority = StandaloneToolAuthority(
            HexanaStandaloneAuthority.registrations.reversed(),
            McpProductRegistry(listOf(shuffledProduct)),
            McpRuntimeBinding(shuffledProduct.id),
        )
        val shuffled = ResolvedToolCatalogReport(
            ideaTools = HexanaIdeaTools.reversed(),
            standaloneAuthority = shuffledAuthority,
            cliTools = HexanaCliTools.reversed(),
        )
        val normal = ResolvedToolCatalogReport()
        assertEquals(normal.json(), shuffled.json())
        assertEquals(normal.text(), shuffled.text())
    }

    @Test
    fun `every selected and excluded view has stable provenance`() {
        val tools = Json.parseToJsonElement(ResolvedToolCatalogReport().json()).jsonObject.getValue("tools").jsonArray
        tools.forEach { tool ->
            tool.jsonObject.getValue("views").jsonObject.values.forEach { view ->
                assertTrue(view.jsonObject.getValue("provenance").jsonPrimitive.content.isNotBlank())
            }
        }
    }

    @Test
    fun `text renders complete stable report details`() {
        val text = ResolvedToolCatalogReport().text()
        assertTrue(text.contains("excluded because it is absent from standalone default profile"))
        assertTrue(text.contains("executionStatus: \"Executable\""))
        assertTrue(text.contains("formats:"))
        assertTrue(text.contains("products:"))
        assertTrue(text.contains("inputBinding:"))
        assertTrue(text.contains("closedRange(parameter=entriesRange)"))
        assertTrue(IDENTITY_TOKEN.find(text) == null)
    }

    @Test
    fun `report main is byte deterministic across fresh JVMs`() {
        val first = Files.createTempDirectory("hexana-tool-report-first")
        val second = Files.createTempDirectory("hexana-tool-report-second")
        try {
            runReportMain(first)
            runReportMain(second)
            listOf("resolved-tool-catalog.json", "resolved-tool-catalog.txt").forEach { name ->
                assertEquals(Files.readAllBytes(first.resolve(name)).toList(), Files.readAllBytes(second.resolve(name)).toList())
            }
        } finally {
            try {
                deleteRecursively(second)
            } finally {
                deleteRecursively(first)
            }
        }
    }

    @Test
    fun `blank exclusion provenance names the tool and view`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ResolvedToolCatalogReport.requireNonBlankProvenance("query_binary", "mcpb", false, " ")
        }
        assertTrue(error.message!!.contains("query_binary"))
        assertTrue(error.message!!.contains("mcpb"))
    }

    @Test
    fun `CLI product membership mutation changes only that tool CLI view`() {
        val target = HexanaCliTools.first { it.binding.products.isNotEmpty() }
        val mutated = HexanaCliTools.map {
            if (it.id == target.id) CliTool(it.contract, it.binding.copy(products = emptySet())) else it
        }
        val normal = document(ResolvedToolCatalogReport())
        val changed = document(ResolvedToolCatalogReport(cliTools = mutated))
        normal.forEach { (toolId, tool) ->
            val changedTool = changed.getValue(toolId)
            if (toolId == target.name) {
                assertEquals(tool.jsonObject.getValue("views").jsonObject - "cli", changedTool.jsonObject.getValue("views").jsonObject - "cli")
            } else {
                assertEquals(tool, changedTool)
            }
        }
    }

    @Test
    fun `static exposure does not probe runtime and MCPB remains standalone-default derived`() {
        val tools = document(ResolvedToolCatalogReport())
        tools.values.forEach { tool ->
            assertEquals("not evaluated", tool.jsonObject.getValue("runtimeAvailability").jsonPrimitive.content)
            assertEquals(0, tool.jsonObject.getValue("requirements").jsonArray.size)
            val views = tool.jsonObject.getValue("views").jsonObject
            val cli = views.getValue("cli").jsonObject
            if (cli.getValue("selected").jsonPrimitive.content == "true") {
                assertTrue(cli.getValue("details").jsonObject.containsKey("formats"))
            }
            assertEquals(
                views.getValue("standaloneDefault").jsonObject.getValue("selected"),
                views.getValue("mcpb").jsonObject.getValue("selected"),
            )
        }
    }

    @Test
    fun `MCP product projection includes an unpublished fixture without changing public views`() {
        val document = Json.parseToJsonElement(ResolvedToolCatalogReport().json()).jsonObject
        val fixture = document.getValue("mcpProducts").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == FixtureMcpProductSpec.id.value
        }.jsonObject

        assertEquals(false, fixture.getValue("published").jsonPrimitive.content.toBoolean())
        assertEquals("default", fixture.getValue("defaultProfile").jsonPrimitive.content)
        assertEquals(
            listOf("query_artifacts"),
            fixture.getValue("profiles").jsonArray.single().jsonObject.getValue("selections").jsonArray.map { it.jsonPrimitive.content },
        )
        val fixtureToolViews = document.getValue("tools").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "query_artifacts"
        }.jsonObject.getValue("views").jsonObject
        assertFalse(fixtureToolViews.getValue("mcpb").jsonObject.getValue("selected").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `fixture CLI product appears only in the CLI product projection`() {
        val fixture = CliProductSpec(ProductId("fixture-cli"), "fixture-cli", "Non-published fixture product.")
        val products = CliProductRegistry(HexanaCliProductRegistry.products + fixture)
        val target = HexanaCliTools.first { it.binding.executionStatus == CliExecutionStatus.Executable }
        val fixtureTools = HexanaCliTools.map { tool ->
            if (tool.id == target.id) tool.copy(binding = tool.binding.copy(products = setOf(fixture.id))) else tool
        }
        val baseline = Json.parseToJsonElement(ResolvedToolCatalogReport().json()).jsonObject
        val document = Json.parseToJsonElement(
            ResolvedToolCatalogReport(cliTools = fixtureTools, cliProducts = products).json(),
        ).jsonObject

        val fixtureProduct = document.getValue("cliProducts").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == fixture.id.value }
            .jsonObject
        assertEquals("fixture-cli", fixtureProduct.getValue("executableName").jsonPrimitive.content)
        assertEquals(target.name, fixtureProduct.getValue("selections").jsonArray.single().jsonObject.getValue("tool").jsonPrimitive.content)
        val targetViews = document.getValue("tools").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == target.name }
            .jsonObject.getValue("views").jsonObject
        val baselineViews = baseline.getValue("tools").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == target.name }
            .jsonObject.getValue("views").jsonObject
        assertEquals(baselineViews - "cli", targetViews - "cli")
        assertEquals(listOf(fixture.id.value), targetViews.getValue("cli").jsonObject.getValue("details").jsonObject.getValue("products").jsonArray.map { it.jsonPrimitive.content })
    }

    private fun document(report: ResolvedToolCatalogReport) = Json.parseToJsonElement(report.json()).jsonObject
        .getValue("tools").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }

    private fun runReportMain(directory: Path) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "org.jetbrains.hexana.mcp.tools.generator.ToolCatalogReportMainKt",
            directory.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        val IDENTITY_TOKEN = Regex("@[0-9a-fA-F]{4,}")
    }
}
