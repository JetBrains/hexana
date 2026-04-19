package org.jetbrains.hexana.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexanaEventTest {

    @Test
    fun wasmFileOpenedEvent() {
        val event = HexanaEvent.WasmFileOpened(
            fileSizeBytes = 1024L,
            surface = HexanaEvent.Surface.IDE_PLUGIN
        )
        assertEquals("wasm_file_opened", event.name)
        assertEquals(1024L, event.properties["file_size_bytes"])
        assertEquals("ide_plugin", event.properties["surface"])
    }

    @Test
    fun wasmFileParsedWithFunctionCount() {
        val event = HexanaEvent.WasmFileParsed(
            fileSizeBytes = 8_000_000L,
            durationMs = 150L,
            success = true,
            functionCount = 14000,
            surface = HexanaEvent.Surface.MCP_SERVER
        )
        assertEquals("wasm_file_parsed", event.name)
        assertEquals(8_000_000L, event.properties["file_size_bytes"])
        assertEquals(150L, event.properties["duration_ms"])
        assertEquals(true, event.properties["success"])
        assertEquals(14000, event.properties["function_count"])
        assertEquals("mcp_server", event.properties["surface"])
    }

    @Test
    fun wasmFileParsedWithoutFunctionCount() {
        val event = HexanaEvent.WasmFileParsed(
            fileSizeBytes = 100L,
            durationMs = 5L,
            success = false,
            functionCount = null,
            surface = HexanaEvent.Surface.IDE_PLUGIN
        )
        assertEquals("wasm_file_parsed", event.name)
        assertEquals(false, event.properties["success"])
        assertNull(event.properties["function_count"])
    }

    @Test
    fun mcpToolInvokedEvent() {
        val event = HexanaEvent.McpToolInvoked(
            toolName = "summarize_module",
            transport = HexanaEvent.McpTransport.STDIO,
            durationMs = 42L,
            success = true
        )
        assertEquals("mcp_tool_invoked", event.name)
        assertEquals("summarize_module", event.properties["tool_name"])
        assertEquals("stdio", event.properties["transport"])
        assertEquals(42L, event.properties["duration_ms"])
        assertEquals(true, event.properties["success"])
    }

    @Test
    fun mcpServerStartedEvent() {
        val event = HexanaEvent.McpServerStarted(
            transport = HexanaEvent.McpTransport.HTTP,
            embeddedInIde = false
        )
        assertEquals("mcp_server_started", event.name)
        assertEquals("http", event.properties["transport"])
        assertEquals(false, event.properties["embedded_in_ide"])
    }

    @Test
    fun watViewActivatedEvent() {
        val event = HexanaEvent.WatViewActivated(surface = HexanaEvent.Surface.IDE_PLUGIN)
        assertEquals("wat_view_activated", event.name)
        assertEquals("ide_plugin", event.properties["surface"])
    }

    @Test
    fun hexViewActivatedEvent() {
        val event = HexanaEvent.HexViewActivated(surface = HexanaEvent.Surface.VSCODE_EXTENSION)
        assertEquals("hex_view_activated", event.name)
        assertEquals("vscode_extension", event.properties["surface"])
    }

    @Test
    fun hexTabSelectedEvent() {
        val event = HexanaEvent.HexTabSelected(tabName = "Functions")
        assertEquals("hex_tab_selected", event.name)
        assertEquals("Functions", event.properties["tab_name"])
    }

    @Test
    fun wasmUnparsedOpcodesEvent() {
        val event = HexanaEvent.WasmUnparsedOpcodes(unparsedCount = 3, totalFunctions = 14000)
        assertEquals("wasm_unparsed_opcodes", event.name)
        assertEquals(3, event.properties["unparsed_count"])
        assertEquals(14000, event.properties["total_functions"])
    }

    @Test
    fun fakeClientCapturesEvents() {
        val client = FakeAnalyticsClient()
        val event = HexanaEvent.WasmFileOpened(512L, HexanaEvent.Surface.MCP_SERVER)
        client.capture(event)
        assertEquals(1, client.captured.size)
        assertEquals(event, client.captured.first())
    }

    @Test
    fun noOpClientDoesNothing() {
        val client = NoOpAnalyticsClient
        client.capture(HexanaEvent.WasmFileOpened(0L, HexanaEvent.Surface.IDE_PLUGIN))
        client.flush()
        client.shutdown()
        // Should not throw
    }

    @Test
    fun allEventNamesAreSnakeCase() {
        val events = listOf(
            HexanaEvent.WasmFileOpened(0L, HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.WasmFileParsed(0L, 0L, true, null, HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.McpToolInvoked("test", HexanaEvent.McpTransport.STDIO, 0L, true),
            HexanaEvent.McpServerStarted(HexanaEvent.McpTransport.HTTP, false),
            HexanaEvent.WatViewActivated(HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.HexViewActivated(HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.HexTabSelected("Test"),
            HexanaEvent.WasmUnparsedOpcodes(1, 100)
        )
        val snakeCaseRegex = Regex("^[a-z][a-z0-9_]*$")
        for (event in events) {
            assertTrue(
                snakeCaseRegex.matches(event.name),
                "Event name '${event.name}' is not snake_case"
            )
        }
    }
}
