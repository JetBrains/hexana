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
    fun binaryOpenedWebappFormats() {
        val expectedFormats = mapOf(
            HexanaEvent.BinaryFormat.DEX to "dex",
            HexanaEvent.BinaryFormat.PCAP to "pcap",
            HexanaEvent.BinaryFormat.PROTOBUF to "protobuf",
            HexanaEvent.BinaryFormat.ARCHIVE to "archive",
            HexanaEvent.BinaryFormat.OTHER to "other",
        )

        for ((format, expectedValue) in expectedFormats) {
            val event = HexanaEvent.BinaryOpened(
                format = format,
                fileSizeBytes = 1024L,
                surface = HexanaEvent.Surface.WEBAPP,
            )

            assertEquals("webapp", event.properties["surface"])
            assertEquals(expectedValue, event.properties["format"])
        }
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
    fun binaryFormatForZipContentKeepsJarWarApk() {
        assertEquals(HexanaEvent.BinaryFormat.JAR, HexanaEvent.BinaryFormat.forZipContent("jar"))
        assertEquals(HexanaEvent.BinaryFormat.WAR, HexanaEvent.BinaryFormat.forZipContent("WAR"))
        assertEquals(HexanaEvent.BinaryFormat.APK, HexanaEvent.BinaryFormat.forZipContent("apk"))
        assertEquals(HexanaEvent.BinaryFormat.ZIP, HexanaEvent.BinaryFormat.forZipContent("aar"))
        assertEquals(HexanaEvent.BinaryFormat.ZIP, HexanaEvent.BinaryFormat.forZipContent("bin"))
        assertEquals(HexanaEvent.BinaryFormat.ZIP, HexanaEvent.BinaryFormat.forZipContent(null))
    }

    @Test
    fun binaryFormatFromExtension() {
        for (ext in HexanaEvent.BinaryFormat.ZIP_FAMILY_EXTENSIONS) {
            assertEquals(HexanaEvent.BinaryFormat.ZIP, HexanaEvent.BinaryFormat.fromExtension(ext), ext)
        }
        assertEquals(HexanaEvent.BinaryFormat.ZIP, HexanaEvent.BinaryFormat.fromExtension("NUPKG"))
        assertEquals(HexanaEvent.BinaryFormat.APK, HexanaEvent.BinaryFormat.fromExtension("apk"))
        assertEquals(HexanaEvent.BinaryFormat.TAR, HexanaEvent.BinaryFormat.fromExtension("tgz"))
        assertEquals(HexanaEvent.BinaryFormat.DEX, HexanaEvent.BinaryFormat.fromExtension("dex"))
        assertEquals(HexanaEvent.BinaryFormat.PCAP, HexanaEvent.BinaryFormat.fromExtension("pcapng"))
        assertEquals(HexanaEvent.BinaryFormat.PDB, HexanaEvent.BinaryFormat.fromExtension("pdb"))
        assertNull(HexanaEvent.BinaryFormat.fromExtension("xyz"))
        assertNull(HexanaEvent.BinaryFormat.fromExtension(null))
    }

    @Test
    fun zipFamilyExcludesFormatsReportedSeparately() {
        for (ext in listOf("jar", "war", "apk")) {
            assertFalse(ext in HexanaEvent.BinaryFormat.ZIP_FAMILY_EXTENSIONS, ext)
        }
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
        for (fmt in HexanaEvent.BinaryFormat.entries) {
            assertTrue(valueRegex.matches(fmt.value), "BinaryFormat value '${fmt.value}' is not snake_case")
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

    // -- GenericHeader classifier --

    @Test
    fun genericHeaderTooFewBytesReturnsNull() {
        assertNull(HexanaEvent.GenericHeader.of(ByteArray(7)))
        assertNull(HexanaEvent.GenericHeader.of(ByteArray(0)))
    }

    @Test
    fun genericHeaderElfMagicIsHex() {
        val elfHeader = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00)
        val result = HexanaEvent.GenericHeader.of(elfHeader)!!
        assertFalse(result == HexanaEvent.GenericHeader.TEXT_SENTINEL)
        assertEquals(16, result.length)
        assertEquals("7f454c4602010100", result)
    }

    @Test
    fun genericHeaderAsciiLettersIsText() {
        val ascii = "ABCDEFgh".encodeToByteArray()
        assertEquals(HexanaEvent.GenericHeader.TEXT_SENTINEL, HexanaEvent.GenericHeader.of(ascii))
    }

    @Test
    fun genericHeaderCyrillicUtf8IsText() {
        // "Привет!" is Cyrillic UTF-8; take first 8 bytes of the encoding.
        val cyrillic = "Привет!!".encodeToByteArray().copyOf(8)
        // May truncate a multibyte sequence; should still be "text".
        assertEquals(HexanaEvent.GenericHeader.TEXT_SENTINEL, HexanaEvent.GenericHeader.of(cyrillic))
    }

    @Test
    fun genericHeaderNullByteIsHex() {
        val withNull = byteArrayOf(0x00, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)
        val result = HexanaEvent.GenericHeader.of(withNull)!!
        assertFalse(result == HexanaEvent.GenericHeader.TEXT_SENTINEL)
        assertEquals(16, result.length)
    }

    @Test
    fun genericHeaderControlByteIsHex() {
        // 0x01 is a control byte (not \t \n \r).
        val withCtrl = byteArrayOf(0x01, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)
        val result = HexanaEvent.GenericHeader.of(withCtrl)!!
        assertFalse(result == HexanaEvent.GenericHeader.TEXT_SENTINEL)
        assertEquals(16, result.length)
    }

    @Test
    fun genericHeaderTabNewlineIsText() {
        // \t \n \r are allowed whitespace.
        val ws = byteArrayOf(0x09, 0x0A, 0x0D, 0x20, 0x41, 0x42, 0x43, 0x44)
        assertEquals(HexanaEvent.GenericHeader.TEXT_SENTINEL, HexanaEvent.GenericHeader.of(ws))
    }

    @Test
    fun genericHeaderTruncatedUtf8IsText() {
        // "ABCDE" (5 bytes) + first 3 bytes of a 3-byte Cyrillic char (truncated at 8-byte boundary).
        // "А" in UTF-8 is 0xD0 0x90 (2 bytes). "Д" is 0xD0 0x94. Use a 3-byte char: "€" = 0xE2 0x82 0xAC.
        // Construct: "ABCDE" + 0xE2 0x82 0xAC (fully 3 bytes, total 8, not truncated at boundary).
        // For a truncated case: "ABCDE\x00" is 6 bytes + 0xE2 0x82 (2-byte partial of a 3-byte sequence).
        val truncated = byteArrayOf(
            0x41, 0x42, 0x43, 0x44, 0x45, // "ABCDE"
            0xE2.toByte(), 0x82.toByte(), 0xAC.toByte() // "€" complete, still text
        )
        assertEquals(HexanaEvent.GenericHeader.TEXT_SENTINEL, HexanaEvent.GenericHeader.of(truncated))
        // Partial: only 2 of 3 bytes of "€" included (truncated at boundary).
        val partial = byteArrayOf(
            0x41, 0x42, 0x43, 0x44, 0x45, 0x46, // "ABCDEF"
            0xE2.toByte(), 0x82.toByte()          // first 2 bytes of 3-byte "€"
        )
        assertEquals(HexanaEvent.GenericHeader.TEXT_SENTINEL, HexanaEvent.GenericHeader.of(partial))
    }

    @Test
    fun genericHeaderPropertyPresentOnlyWhenNonNull() {
        val withHeader = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.GENERIC,
            fileSizeBytes = 100L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            genericHeader = "7f454c4602010100",
        )
        assertEquals("7f454c4602010100", withHeader.properties["generic_header"])

        val withoutHeader = HexanaEvent.BinaryOpened(
            format = HexanaEvent.BinaryFormat.GENERIC,
            fileSizeBytes = 100L,
            surface = HexanaEvent.Surface.IDE_PLUGIN,
            genericHeader = null,
        )
        assertFalse("generic_header" in withoutHeader.properties)
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
