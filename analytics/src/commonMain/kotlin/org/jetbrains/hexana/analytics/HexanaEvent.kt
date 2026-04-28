package org.jetbrains.hexana.analytics

/**
 * Typed event taxonomy for Hexana analytics.
 * All events produce a PostHog-compatible name (snake_case) and a property map.
 * No PII is included in any event.
 */
sealed class HexanaEvent {
    abstract val name: String
    abstract val properties: Map<String, Any>

    // -- WASM file / parsing --

    data class WasmFileOpened(
        val fileSizeBytes: Long,
        val surface: Surface
    ) : HexanaEvent() {
        override val name = "wasm_file_opened"
        override val properties = mapOf(
            "file_size_bytes" to fileSizeBytes,
            "surface" to surface.value
        )
    }

    data class WasmFileParsed(
        val fileSizeBytes: Long,
        val durationMs: Long,
        val success: Boolean,
        val functionCount: Int?,
        val surface: Surface
    ) : HexanaEvent() {
        override val name = "wasm_file_parsed"
        override val properties = buildMap {
            put("file_size_bytes", fileSizeBytes)
            put("duration_ms", durationMs)
            put("success", success)
            put("surface", surface.value)
            functionCount?.let { put("function_count", it) }
        }
    }

    // -- MCP --

    data class McpToolInvoked(
        val toolName: String,
        val transport: McpTransport,
        val durationMs: Long,
        val success: Boolean
    ) : HexanaEvent() {
        override val name = "mcp_tool_invoked"
        override val properties = mapOf(
            "tool_name" to toolName,
            "transport" to transport.value,
            "duration_ms" to durationMs,
            "success" to success
        )
    }

    data class McpServerStarted(
        val transport: McpTransport,
        val embeddedInIde: Boolean
    ) : HexanaEvent() {
        override val name = "mcp_server_started"
        override val properties = mapOf(
            "transport" to transport.value,
            "embedded_in_ide" to embeddedInIde
        )
    }

    // -- UI --

    data class WatViewActivated(
        val surface: Surface
    ) : HexanaEvent() {
        override val name = "wat_view_activated"
        override val properties = mapOf("surface" to surface.value)
    }

    data class HexViewActivated(
        val surface: Surface
    ) : HexanaEvent() {
        override val name = "hex_view_activated"
        override val properties = mapOf("surface" to surface.value)
    }

    data class HexTabSelected(
        val tabName: String,
        val surface: Surface
    ) : HexanaEvent() {
        override val name = "hex_tab_selected"
        override val properties = mapOf(
            "tab_name" to tabName,
            "surface" to surface.value
        )
    }

    data class WasmUnparsedOpcodes(
        val unparsedCount: Int,
        val totalFunctions: Int
    ) : HexanaEvent() {
        override val name = "wasm_unparsed_opcodes"
        override val properties = mapOf(
            "unparsed_count" to unparsedCount,
            "total_functions" to totalFunctions
        )
    }

    // -- Enums --

    enum class Surface(val value: String) {
        IDE_PLUGIN("ide_plugin"),
        VSCODE_EXTENSION("vscode_extension"),
        MCP_SERVER("mcp_server")
    }

    enum class McpTransport(val value: String) {
        STDIO("stdio"),
        HTTP("http"),
        IDE_EMBEDDED("ide_embedded")
    }
}
