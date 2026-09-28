package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.CliTool
import org.jetbrains.hexana.mcp.tools.HexanaCliProductSpec
import org.jetbrains.hexana.mcp.tools.HexanaCliTools
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolId
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.UnknownFieldPolicy
import org.jetbrains.hexana.mcp.tools.WasmInputClass
import org.jetbrains.hexana.mcp.tools.class_
import org.jetbrains.hexana.mcp.tools.cliToolBinding
import org.jetbrains.hexana.mcp.tools.int
import org.jetbrains.hexana.mcp.tools.name
import org.jetbrains.hexana.mcp.tools.toolInputModel
import org.jetbrains.hexana.mcp.tools.validateCliBinding
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliInputAdapterGenerationTest {
    @Test
    fun `summarize module generates one typed source request and existing option aliases`() {
        val tool = HexanaCliTools.single { it.name == "summarize_module" }
        val adapter = requireNotNull(buildCliInputAdapter(tool))
        val decoder = adapter.decoder.toString()

        assertTrue(adapter.request.toString().contains("ToolInputSourceSelection"))
        assertTrue(decoder.contains("sourceSelections.singleOrNull()"))
        assertTrue(decoder.contains("requires exactly one wasm source selection"))
        assertTrue(adapter.converter.toString().contains("decodeSummarizeModuleCliInput(request)"))
        assertEquals(
            listOf("wasm", "--stdin", "--bytes-base64", "--wasm-base64"),
            tool.cliInputOptions().map { it.name },
        )
    }

    @Test
    fun `parameterless request honors ignored unknown fields`() {
        val tool = HexanaCliTools.single { it.name == "summarize_module" }
        val ignoringTool = tool.copy(binding = tool.binding.copy(unknownFieldPolicy = UnknownFieldPolicy.Ignore))
        val decoder = requireNotNull(buildCliInputAdapter(ignoringTool)).decoder.toString()

        assertFalse(decoder.contains("rawArguments.isEmpty()"), decoder)
        assertTrue(decoder.contains("sourceSelections.singleOrNull()"), decoder)
    }

    @Test
    fun `private fixture emits multiple typed sources and scalar list JSON parameters`() {
        val input = class_("ComplexCliInput") {
            embed("current", WasmInputClass).describe("Current module.")
            nullable("baseline", of = WasmInputClass).describe("Baseline module.")
            int("limit", default = 25).describe("Maximum results.").minimum(1)
            list("indices", of = int()).describe("Function indices.").itemsMinimum(0).maxItems(5)
            jsonObject("filters").describe("Filter object.")
            string("query", default = "").describe("Search text.")
        }
        val model = toolInputModel {
            wasmSource("current", input.fieldRef("current")) {
                cli { positional("current"); stdin("--stdin"); autoStdin() }
            }
            wasmSource("baseline", input.fieldRef("baseline"), required = false) {
                cli { pathOption("--baseline") }
            }
            param("limit", input.fieldRef("limit"), "--limit")
            param("indices", input.fieldRef("indices"), "--indices")
            jsonParam("filters", input.fieldRef("filters"), "--filters")
            param("query", input.fieldRef("query"), "--query")
        }
        val contract = ToolSpec(ToolId("complex_cli_fixture"), "Private CLI fixture.", null, input,
            class_("ComplexCliOutput") { string("status").describe("Status.") })
        val binding = cliToolBinding(contract.id, "complex-cli-fixture", ToolGroup.Module,
            ToolOutputPolicy.ToolDefined, executionStatus = CliExecutionStatus.Executable,
            products = setOf(HexanaCliProductSpec.id), inputModel = model,
            generatedInputRequest = "ComplexCliInputRequest")
        validateCliBinding(contract, binding)
        val tool = CliTool(contract, binding)
        val adapter = requireNotNull(buildCliInputAdapter(tool))
        val request = adapter.request.toString()
        val decoder = adapter.decoder.toString()
        val converter = adapter.converter.toString()

        assertTrue(request.contains("current: org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection"), request)
        assertTrue(request.contains("baseline: org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection?"), request)
        assertTrue(request.contains("limit: kotlin.Int"), request)
        assertTrue(request.contains("indices: kotlin.collections.List<kotlin.Int>"), request)
        assertTrue(request.contains("filters: kotlinx.serialization.json.JsonObject"), request)
        assertTrue(decoder.contains("CliInputDecoding.int(value, \"limit\", 1, null)"), decoder)
        assertTrue(decoder.contains("CliInputDecoding.intList(value, \"indices\", 5, 0, null)"), decoder)
        assertTrue(decoder.contains("CliInputDecoding.jsonObject(value, \"filters\")"), decoder)
        assertTrue(decoder.contains("null -> 25"))
        assertTrue(converter.contains("arguments[\"limit\"] = kotlinx.serialization.json.JsonPrimitive(decoded.limit)"), converter)
        assertEquals("25", tool.cliInputOptions().single { it.name == "--limit" }.defaultValue)
        assertEquals("list<integer>", tool.cliInputOptions().single { it.name == "--indices" }.valueType)
        assertEquals("Filter object.", tool.cliInputOptions().single { it.name == "--filters" }.description)
    }

    @Test
    fun `generated names escape keywords and avoid synthetic local collisions`() {
        val input = class_("KeywordCliInput") {
            embed("current", WasmInputClass).describe("Current module.")
            nullable("baseline", of = WasmInputClass).describe("Baseline module.")
            int("when", default = 1).describe("Selection limit.")
            int("currentSources", default = 2).describe("Source count.")
            int("request", default = 3).describe("Request count.")
        }
        val model = toolInputModel {
            wasmSource("current", input.fieldRef("current")) { cli { positional("current") } }
            wasmSource("baseline", input.fieldRef("baseline"), required = false) { cli { pathOption("--baseline") } }
            param("when", input.fieldRef("when"), "--when")
            param("currentSources", input.fieldRef("currentSources"), "--current-sources")
            param("request", input.fieldRef("request"), "--request")
        }
        val contract = ToolSpec(ToolId("keyword_cli_fixture"), "Private keyword fixture.", null, input,
            class_("KeywordCliOutput") { string("status").describe("Status.") })
        val binding = cliToolBinding(contract.id, "keyword-cli-fixture", ToolGroup.Module,
            ToolOutputPolicy.ToolDefined, executionStatus = CliExecutionStatus.Executable,
            products = setOf(HexanaCliProductSpec.id), inputModel = model,
            generatedInputRequest = "KeywordCliInputRequest")
        validateCliBinding(contract, binding)
        val adapter = requireNotNull(buildCliInputAdapter(CliTool(contract, binding)))
        val decoder = adapter.decoder.toString()

        assertTrue(adapter.request.toString().contains("`when`: kotlin.Int"))
        assertTrue(decoder.contains("val currentSources_ ="), decoder)
        assertTrue(decoder.contains("return org.jetbrains.hexana.cli.metadata.KeywordCliInputRequest(current, baseline, when_, currentSources, request_)"), decoder)
        assertTrue(adapter.converter.toString().contains("decoded.`when`"))
    }

    @Test
    fun `generated adapters reject built-in type and alias names`() {
        val tool = HexanaCliTools.single { it.name == "summarize_module" }
        listOf("HexanaCliToolSpec", "HexanaCliOutputFormat", "HexanaCliProductDescriptor").forEach { requestName ->
            val namedTool = tool.copy(binding = tool.binding.copy(generatedInputRequest = requestName))
            val error = assertFailsWith<IllegalArgumentException> {
                buildCliMetadataToolsetFile(listOf(namedTool))
            }
            assertTrue(error.message.orEmpty().contains("CLI metadata contains colliding top-level type declarations: `$requestName`"))
        }
    }

    @Test
    fun `generated adapters reject duplicate and derived converter names`() {
        val module = HexanaCliTools.single { it.name == "summarize_module" }
        val binary = HexanaCliTools.single { it.name == "summarize_binary" }
        listOf("Foo", "FooConverter").forEach { secondName ->
            val first = module.copy(binding = module.binding.copy(generatedInputRequest = "Foo"))
            val second = binary.copy(binding = binary.binding.copy(generatedInputRequest = secondName))
            val error = assertFailsWith<IllegalArgumentException> {
                buildCliMetadataToolsetFile(listOf(first, second))
            }
            assertTrue(error.message.orEmpty().contains("CLI metadata contains colliding top-level type declarations"))
        }
    }
}
