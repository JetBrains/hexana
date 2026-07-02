package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.hexana.analytics.AnalyticsConfig
import org.jetbrains.hexana.analytics.ConsentProvider
import org.jetbrains.hexana.analytics.DistinctIdProvider
import org.jetbrains.hexana.analytics.HexanaAnalytics
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.exports.SearchExportsResult
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.functions.DisassembleFunctionResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetFunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.ListCodeSectionOffsetsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.SearchFunctionsByNameResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.sections.ListCustomSectionsResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleImports
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.ListReferenceTypesResult
import org.jetbrains.hexana.mcp.model.types.ListValueTypesResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HexanaMcpAnalyticsTest {
    @AfterTest
    fun tearDown() {
        HexanaAnalytics.shutdown()
    }

    @Test
    fun aStdioStartupSendsServerStartedEventWhenTelemetryIsEnabled() {
        FakePostHogServer.start().use { server ->
            withMcpAnalytics(startupAnalytics(HexanaEvent.McpTransport.STDIO, server, analyticsEnabled = true)) {
                HexanaAnalytics.client.flush()
            }

            server.awaitEvents(1).singleEvent("mcp_server_started")
                .requireDistinctId()
                .assertProperty("transport", "stdio")
                .assertProperty("embedded_in_ide", false)
        }
    }

    @Test
    fun bCommandAnalyticsMapsHttpAndStdioStartupTransports() {
        val stdioAnalytics = stdioCommand(analyticsEnabled = true).analytics()
        val httpAnalytics = httpCommand(analyticsEnabled = true).analytics()

        assertEquals(HexanaEvent.McpTransport.STDIO, stdioAnalytics?.transport)
        assertEquals(HexanaEvent.McpTransport.HTTP, httpAnalytics?.transport)
        assertTrue(stdioAnalytics?.analyticsEnabled == true)
        assertTrue(httpAnalytics?.analyticsEnabled == true)
    }

    @Test
    fun cStartupSendsNoRequestsWhenTelemetryIsDisabled() {
        FakePostHogServer.start().use { server ->
            withMcpAnalytics(startupAnalytics(HexanaEvent.McpTransport.STDIO, server, analyticsEnabled = false)) {
                HexanaAnalytics.client.flush()
            }

            server.assertNoRequests()
        }
    }

    @Test
    fun dSuccessfulToolInvocationSendsToolEventWithConfiguredTransport() {
        FakePostHogServer.start().use { server ->
            initializeTestAnalytics(server)

            val result = HexanaToolHandlers(
                analysisService = SummarizeOnlyAnalysisService,
                transport = HexanaEvent.McpTransport.HTTP
            ).summarizeModule(jsonBytes())
            assertFalse(result.isError == true)
            HexanaAnalytics.client.flush()

            server.awaitEvents(1).singleEvent("mcp_tool_invoked")
                .requireDistinctId()
                .assertProperty("tool_name", "summarize_module")
                .assertProperty("transport", "http")
                .assertProperty("success", true)
            HexanaAnalytics.shutdown()
        }
    }

    @Test
    fun eFailedToolInvocationSendsToolEvent() {
        FakePostHogServer.start().use { server ->
            initializeTestAnalytics(server)

            val result = HexanaToolHandlers(transport = HexanaEvent.McpTransport.STDIO)
                .listFunctions(
                    buildJsonObject {
                        put("path", JsonPrimitive("/missing/module.wasm"))
                        put("offset", JsonPrimitive(0))
                        put("limit", JsonPrimitive(10))
                    }
                )
            assertEquals(true, result.isError)
            HexanaAnalytics.client.flush()

            server.awaitEvents(1).singleEvent("mcp_tool_invoked")
                .requireDistinctId()
                .assertProperty("tool_name", "list_functions")
                .assertProperty("transport", "stdio")
                .assertProperty("success", false)
            HexanaAnalytics.shutdown()
        }
    }

    private fun initializeTestAnalytics(server: FakePostHogServer) {
        HexanaAnalytics.initialize(
            config = AnalyticsConfig(
                apiKey = testApiKey(),
                host = server.url,
                flushAt = 1,
                flushIntervalMs = 1_000,
            ),
            consentProvider = ConsentProvider { true },
            distinctIdProvider = DistinctIdProvider { TEST_DISTINCT_ID },
        )
    }

    private fun startupAnalytics(
        transport: HexanaEvent.McpTransport,
        server: FakePostHogServer,
        analyticsEnabled: Boolean,
    ): CommandAnalytics {
        return CommandAnalytics(
            analyticsKey = testApiKey(),
            analyticsEnabled = analyticsEnabled,
            transport = transport,
            analyticsHost = server.url,
        )
    }

    private fun stdioCommand(analyticsEnabled: Boolean): HexanaCommand.Stdio {
        return HexanaCommand.Stdio(
            logLevel = HexanaLogLevel.QUIET,
            analyticsKey = testApiKey(),
            analyticsEnabled = analyticsEnabled,
            toolProfile = HexanaMcpToolProfile.PRODUCTION,
            allowedRoots = emptyList(),
            resourceLimits = HexanaResourceLimits(),
        )
    }

    private fun httpCommand(analyticsEnabled: Boolean): HexanaCommand.Http {
        return HexanaCommand.Http(
            host = "127.0.0.1",
            port = 3000,
            endpoint = "/mcp",
            allowPublicBind = false,
            logLevel = HexanaLogLevel.QUIET,
            analyticsKey = testApiKey(),
            analyticsEnabled = analyticsEnabled,
            toolProfile = HexanaMcpToolProfile.PRODUCTION,
            allowedRoots = emptyList(),
            resourceLimits = HexanaResourceLimits(),
        )
    }

    private fun jsonBytes() = buildJsonObject {
        put("bytes", buildJsonArray {})
    }

    private fun testApiKey(): String = "test-key-${System.nanoTime()}"

    private object SummarizeOnlyAnalysisService : HexanaAnalysisService {
        override fun summarizeModule(input: WasmInput): SummarizeModuleResult {
            return SummarizeModuleResult(
                functions = 1,
                exports = 1,
                memories = 1,
                globals = 0,
                tables = 0,
                types = 1,
                imports = SummarizeModuleImports(
                    functions = 0,
                    memories = 0,
                    tables = 0,
                    globals = 0,
                ),
                moduleSize = 8,
            )
        }

        override fun listImports(input: WasmInput): ListImportsResult = error("unused")
        override fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult = error("unused")
        override fun listCustomSections(input: WasmInput): ListCustomSectionsResult = error("unused")
        override fun listExports(input: WasmInput): ListExportsResult = error("unused")
        override fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult =
            error("unused")

        override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult = error("unused")
        override fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult = error("unused")
        override fun resolveFunctionSignature(input: WasmInput, funcidx: Int): ResolveFunctionSignatureResult =
            error("unused")

        override fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult =
            error("unused")

        override fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult =
            error("unused")

        override fun searchFunctionsByName(input: WasmInput, query: String, limit: Int): SearchFunctionsByNameResult =
            error("unused")

        override fun listExportedFunctions(input: WasmInput, entriesRange: IntRange): ListExportedFunctionsResult =
            error("unused")

        override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult = error("unused")
        override fun listGlobals(input: WasmInput): ListGlobalsResult = error("unused")
        override fun listTypes(input: WasmInput): TypesResult = error("unused")
        override fun listValueTypes(input: WasmInput): ListValueTypesResult = error("unused")
        override fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult = error("unused")
        override fun listMemory(input: WasmInput): ListMemoryResult = error("unused")
        override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult = error("unused")
        override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult = error("unused")
        override fun listDataSegments(
            input: WasmInput,
            segmentStartInclusive: Int,
            segmentEndExclusive: Int,
            includeExports: Boolean,
            includeGlobals: Boolean,
            minByteLength: Int?
        ): ListDataSegmentsResult = error("unused")

        override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult = error("unused")
        override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult = error("unused")
        override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult =
            error("unused")

        override fun getInstructionsForFunctions(input: WasmInput, indices: List<Int>): GetInstructionsForFunctionsResult =
            error("unused")

        override fun listElementSegments(input: WasmInput): ListElementSegmentsResult = error("unused")
    }

    private companion object {
        const val TEST_DISTINCT_ID = "mcp-test-distinct-id"
    }
}
