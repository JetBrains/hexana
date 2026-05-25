package org.jetbrains.hexana.analytics

/**
 * Typed event taxonomy for Hexana analytics.
 * All events produce a PostHog-compatible name (snake_case) and a property map.
 * No PII is included in any event.
 */
sealed class HexanaEvent {
    abstract val name: String
    abstract val properties: Map<String, Any>

    // -- Binary lifecycle --

    /**
     * Fires every time the user opens a binary in Hexana — WASM, native (ELF,
     * Mach-O, PE, COFF, FatMachO), Java class, archive, or generic.
     *
     * Properties carry only non-identifying metadata: format, file size, and
     * (for native binaries when discoverable) architecture, whether the
     * binary is stripped, whether debug info is present, and a bucketed
     * symbol count. Symbol names, paths, basenames, and content hashes are
     * never sent.
     */
    data class BinaryOpened(
        val format: BinaryFormat,
        val fileSizeBytes: Long,
        val surface: Surface,
        val arch: Arch? = null,
        val isStripped: Boolean? = null,
        val hasDebugInfo: Boolean? = null,
        val symbolCountBucket: SymbolCountBucket? = null,
    ) : HexanaEvent() {
        override val name = "binary_opened"
        override val properties = buildMap {
            put("format", format.value)
            put("file_size_bytes", fileSizeBytes)
            put("surface", surface.value)
            arch?.let { put("arch", it.value) }
            isStripped?.let { put("is_stripped", it) }
            hasDebugInfo?.let { put("has_debug_info", it) }
            symbolCountBucket?.let { put("symbol_count_bucket", it.value) }
        }
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

    /**
     * Binary container format detected at open time. Drives the `format`
     * property on [BinaryOpened]. The `value` is the snake_case PostHog
     * property value.
     */
    enum class BinaryFormat(val value: String) {
        WASM("wasm"),
        WASM_COMPONENT("wasm_component"),
        ELF("elf"),
        MACHO("macho"),
        PE("pe"),
        COFF("coff"),
        FAT_MACHO("fat_macho"),
        JAR("jar"),
        WAR("war"),
        ZIP("zip"),
        APK("apk"),
        CLASS("class"),
        GENERIC("generic"),
        ;

        companion object {
            /**
             * Resolves a [BinaryFormat] by its [value] (snake_case PostHog
             * string). Used by the VS Code surface where the typed enum
             * isn't reachable across the JS boundary. Returns null when the
             * value doesn't match any enum entry.
             */
            fun fromValue(value: String): BinaryFormat? =
                entries.firstOrNull { it.value == value }
        }
    }

    /**
     * Machine architecture decoded from a native binary's header at open
     * time. Analytics-local enum — kept separate from the disassembly
     * module's `Arch` so the analytics taxonomy doesn't take an upstream
     * dependency on a UI module's enum.
     */
    enum class Arch(val value: String) {
        X86("x86"),
        X86_64("x86_64"),
        ARM("arm"),
        ARM64("arm64"),
        RISCV32("riscv32"),
        RISCV64("riscv64"),
    }

    /**
     * Bucketed symbol count for native binaries. Bucketed rather than raw
     * to keep PostHog cardinality bounded — funnel queries on "stripped vs
     * lightly-symboled vs fully-symboled" work across the buckets without
     * needing one cohort per integer.
     */
    enum class SymbolCountBucket(val value: String) {
        NONE("none"),
        B_1_100("1-100"),
        B_101_1000("101-1000"),
        B_1001_10000("1001-10000"),
        B_10000_PLUS("10000+"),
        ;

        companion object {
            /** Bucket [count] into one of the predefined ranges. */
            fun of(count: Int): SymbolCountBucket = when {
                count <= 0 -> NONE
                count <= 100 -> B_1_100
                count <= 1000 -> B_101_1000
                count <= 10_000 -> B_1001_10000
                else -> B_10000_PLUS
            }
        }
    }
}