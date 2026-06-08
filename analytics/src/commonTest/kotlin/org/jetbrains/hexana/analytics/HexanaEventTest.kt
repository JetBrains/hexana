package org.jetbrains.hexana.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexanaEventTest {

    @Test
    fun binaryOpenedMinimal() {
        val event = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.WASM,
            fileSizeBytes = 1024L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
        )
        assertEquals("binary_opened", event.name)
        assertEquals("wasm", event.properties["format"])
        assertEquals(1024L, event.properties["file_size_bytes"])
        assertEquals("ide_plugin", event.properties["surface"])
    }

    @Test
    fun binaryOpenedFullyTagged() {
        val event = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.ELF,
            fileSizeBytes = 8_000_000L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            arch = HexanaEvent.Arch.X86_64,
            isStripped = false,
            hasDebugInfo = true,
            symbolCountBucket = HexanaEvent.SymbolCountBucket.B_1001_10000,
        )
        assertEquals("binary_opened", event.name)
        assertEquals("elf", event.properties["format"])
        assertEquals(8_000_000L, event.properties["file_size_bytes"])
        assertEquals("ide_plugin", event.properties["surface"])
        assertEquals("x86_64", event.properties["arch"])
        assertEquals(false, event.properties["is_stripped"])
        assertEquals(true, event.properties["has_debug_info"])
        assertEquals("1001-10000", event.properties["symbol_count_bucket"])
    }

    @Test
    fun binaryOpenedDropsNullOptionalProps() {
        val event = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.GENERIC,
            fileSizeBytes = 64L,
            surface = HexanaEvent.Surface.VSCODE_EXTENSION,
            // arch, isStripped, hasDebugInfo, symbolCountBucket all null
        )
        assertFalse("arch" in event.properties)
        assertFalse("is_stripped" in event.properties)
        assertFalse("has_debug_info" in event.properties)
        assertFalse("symbol_count_bucket" in event.properties)
        assertFalse("generic_ext" in event.properties)
        assertFalse("detected_format" in event.properties)
    }

    @Test
    fun binaryOpenedGenericBreakdown() {
        val event = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.GENERIC,
            fileSizeBytes = 4_000_000_000L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            genericExt = HexanaEvent.GenericExt.BIN,
            detectedFormat = HexanaEvent.DetectedFormat.GGUF,
        )
        assertEquals("generic", event.properties["format"])
        // A GGUF model named .bin: extension hint and magic sniff disagree,
        // and both are reported.
        assertEquals("bin", event.properties["generic_ext"])
        assertEquals("gguf", event.properties["detected_format"])
    }

    @Test
    fun genericExtFromExtensionAllowlist() {
        assertEquals(HexanaEvent.GenericExt.GGUF, HexanaEvent.GenericExt.fromExtension("gguf"))
        assertEquals(HexanaEvent.GenericExt.PARQUET, HexanaEvent.GenericExt.fromExtension("parquet"))
        // Case-insensitive.
        assertEquals(HexanaEvent.GenericExt.ONNX, HexanaEvent.GenericExt.fromExtension("ONNX"))
        // Unknown extension and missing extension both bucket to OTHER, never raw.
        assertEquals(HexanaEvent.GenericExt.OTHER, HexanaEvent.GenericExt.fromExtension("mycompany_secret"))
        assertEquals(HexanaEvent.GenericExt.OTHER, HexanaEvent.GenericExt.fromExtension(null))
        assertEquals(HexanaEvent.GenericExt.OTHER, HexanaEvent.GenericExt.fromExtension(""))
    }

    @Test
    fun genericBreakdownEnumValuesAreSnakeCase() {
        val valueRegex = Regex("^[a-z][a-z0-9_]*$")
        for (ext in HexanaEvent.GenericExt.entries) {
            assertTrue(valueRegex.matches(ext.value), "GenericExt value '${ext.value}' is not snake_case")
        }
        for (fmt in HexanaEvent.DetectedFormat.entries) {
            assertTrue(valueRegex.matches(fmt.value), "DetectedFormat value '${fmt.value}' is not snake_case")
        }
    }

    @Test
    fun binaryFormatFromValueRoundTrips() {
        for (format in HexanaEvent.BinaryFormat.entries) {
            assertEquals(format, HexanaEvent.BinaryFormat.fromValue(format.value))
        }
        assertNull(HexanaEvent.BinaryFormat.fromValue("not_a_format"))
    }

    @Test
    fun symbolCountBucketBoundaries() {
        assertEquals(HexanaEvent.SymbolCountBucket.NONE, HexanaEvent.SymbolCountBucket.of(0))
        assertEquals(HexanaEvent.SymbolCountBucket.NONE, HexanaEvent.SymbolCountBucket.of(-5))
        assertEquals(HexanaEvent.SymbolCountBucket.B_1_100, HexanaEvent.SymbolCountBucket.of(1))
        assertEquals(HexanaEvent.SymbolCountBucket.B_1_100, HexanaEvent.SymbolCountBucket.of(100))
        assertEquals(HexanaEvent.SymbolCountBucket.B_101_1000, HexanaEvent.SymbolCountBucket.of(101))
        assertEquals(HexanaEvent.SymbolCountBucket.B_101_1000, HexanaEvent.SymbolCountBucket.of(1000))
        assertEquals(HexanaEvent.SymbolCountBucket.B_1001_10000, HexanaEvent.SymbolCountBucket.of(1001))
        assertEquals(HexanaEvent.SymbolCountBucket.B_1001_10000, HexanaEvent.SymbolCountBucket.of(10_000))
        assertEquals(HexanaEvent.SymbolCountBucket.B_10000_PLUS, HexanaEvent.SymbolCountBucket.of(10_001))
        assertEquals(HexanaEvent.SymbolCountBucket.B_10000_PLUS, HexanaEvent.SymbolCountBucket.of(1_000_000))
    }

    @Test
    fun jitDumpOpenedWithCount() {
        val event = HexanaEvent.JitDumpOpened(
            fileSizeBytes = 1_048_576L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            version = 5,
            compiledMethodCount = 1842,
        )
        assertEquals("jit_dump_opened", event.name)
        assertEquals(1_048_576L, event.properties["file_size_bytes"])
        assertEquals("ide_plugin", event.properties["surface"])
        assertEquals(5, event.properties["version"])
        assertEquals(1842, event.properties["compiled_method_count"])
    }

    @Test
    fun jitDumpOpenedWithoutCount() {
        val event = HexanaEvent.JitDumpOpened(
            fileSizeBytes = 256L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            version = 4,
            compiledMethodCount = null,
        )
        assertEquals("jit_dump_opened", event.name)
        assertEquals(4, event.properties["version"])
        assertNull(event.properties["compiled_method_count"])
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
        val event = HexanaEvent.HexTabSelected(tabName = "Functions", surface = HexanaEvent.Surface.VSCODE_EXTENSION)
        assertEquals("hex_tab_selected", event.name)
        assertEquals("Functions", event.properties["tab_name"])
        assertEquals("vscode_extension", event.properties["surface"])
        assertFalse("binary_format" in event.properties)
    }

    @Test
    fun hexTabSelectedWithBinaryFormat() {
        val event = HexanaEvent.HexTabSelected(
            tabName = "Disassembly",
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            binaryFormat = HexanaEvent.BinaryFormat.ELF,
        )
        assertEquals("hex_tab_selected", event.name)
        assertEquals("Disassembly", event.properties["tab_name"])
        assertEquals("ide_plugin", event.properties["surface"])
        assertEquals("elf", event.properties["binary_format"])
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
        val event = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.WASM,
            fileSizeBytes = 512L,
            surface = HexanaEvent.Surface.MCP_SERVER,
        )
        client.capture(event)
        assertEquals(1, client.captured.size)
        assertEquals(event, client.captured.first())
    }

    @Test
    fun noOpClientDoesNothing() {
        val client = NoOpAnalyticsClient
        client.capture(
            HexanaEvent.BinaryOpened(
                format = HexanaEvent.BinaryFormat.WASM,
                fileSizeBytes = 0L,
                surface = HexanaEvent.Surface.IDE_PLUGIN,
            )
        )
        client.flush()
        client.shutdown()
        // Should not throw
    }

    @Test
    fun allEventNamesAreSnakeCase() {
        val events = listOf(
            HexanaEvent.BinaryOpened(HexanaEvent.BinaryFormat.WASM, 0L, HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.JitDumpOpened(0L, HexanaEvent.Surface.IDE_PLUGIN, 5, null),
            HexanaEvent.WasmFileParsed(0L, 0L, true, null, HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.McpToolInvoked("test", HexanaEvent.McpTransport.STDIO, 0L, true),
            HexanaEvent.McpServerStarted(HexanaEvent.McpTransport.HTTP, false),
            HexanaEvent.WatViewActivated(HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.HexViewActivated(HexanaEvent.Surface.IDE_PLUGIN),
            HexanaEvent.HexTabSelected("Test", HexanaEvent.Surface.IDE_PLUGIN),
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