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
     *
     * When [format] is [BinaryFormat.GENERIC] — the catch-all for files
     * Hexana opened but could not structurally identify — two extra
     * sub-classification fields break the bucket down so we can see *what*
     * those files actually are: [genericExt] (the file extension, mapped
     * through a bounded allowlist — never the raw string) and
     * [detectedFormat] (a content magic-byte sniff into a bounded enum).
     * Both are bounded-cardinality enum values; no raw extension string and
     * no file bytes are ever transmitted. They are populated only when
     * [format] is GENERIC and stay null otherwise.
     */
    data class BinaryOpened(
        val format: BinaryFormat,
        val fileSizeBytes: Long,
        val surface: Surface,
        val arch: Arch? = null,
        val isStripped: Boolean? = null,
        val hasDebugInfo: Boolean? = null,
        val symbolCountBucket: SymbolCountBucket? = null,
        val genericExt: GenericExt? = null,
        val detectedFormat: DetectedFormat? = null,
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
            genericExt?.let { put("generic_ext", it.value) }
            detectedFormat?.let { put("detected_format", it.value) }
        }
    }

    /**
     * Fires when the user opens a `.jit` dump produced by Hexana's
     * libjitview JVMTI agent. The dump's format version is part of the
     * header (`MIN` magic + version byte). The compiled-method count is
     * the number of `sym_entry_t` records in the chain — provided when
     * the agent's symbol loading completes; null when the dump failed to
     * parse far enough to enumerate symbols.
     */
    data class JitDumpOpened(
        val fileSizeBytes: Long,
        val surface: Surface,
        val version: Int,
        val compiledMethodCount: Int? = null,
    ) : HexanaEvent() {
        override val name = "jit_dump_opened"
        override val properties = buildMap {
            put("file_size_bytes", fileSizeBytes)
            put("surface", surface.value)
            put("version", version)
            compiledMethodCount?.let { put("compiled_method_count", it) }
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
        val surface: Surface,
        val binaryFormat: BinaryFormat? = null,
    ) : HexanaEvent() {
        override val name = "hex_tab_selected"
        override val properties = buildMap {
            put("tab_name", tabName)
            put("surface", surface.value)
            binaryFormat?.let { put("binary_format", it.value) }
        }
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
     * Extension-based sub-classification of a [BinaryFormat.GENERIC] open.
     * Derived from the file extension (lowercased), mapped through this
     * bounded allowlist so PostHog cardinality stays fixed and no raw
     * extension string is ever transmitted. Any extension not on the
     * allowlist — and a missing extension — maps to [OTHER]; that bucket is
     * permanent and is the "unknown-unknown" residual to watch over time.
     *
     * Populated only when the event's format is GENERIC.
     */
    enum class GenericExt(val value: String) {
        GGUF("gguf"),
        SAFETENSORS("safetensors"),
        ONNX("onnx"),
        PARQUET("parquet"),
        ARROW("arrow"),
        FEATHER("feather"),
        PB("pb"),
        DEX("dex"),
        SPV("spv"),
        NPY("npy"),
        NPZ("npz"),
        PT("pt"),
        PTH("pth"),
        H5("h5"),
        TFLITE("tflite"),
        MLMODEL("mlmodel"),
        PKL("pkl"),
        WHL("whl"),
        BIN("bin"),
        DAT("dat"),
        SO("so"),
        DYLIB("dylib"),
        DLL("dll"),
        SQLITE("sqlite"),
        DB("db"),
        IMG("img"),
        ISO("iso"),
        OTHER("other"),
        ;

        companion object {
            /** Maps a (possibly null) file extension to a bounded bucket; unknown → [OTHER]. */
            fun fromExtension(extension: String?): GenericExt {
                val lower = extension?.lowercase() ?: return OTHER
                return entries.firstOrNull { it.value == lower } ?: OTHER
            }
        }
    }

    /**
     * Content magic-byte sub-classification of a [BinaryFormat.GENERIC] open.
     * Extension-independent — a GGUF file named `.bin` still resolves to
     * [GGUF] here. Carries only the bounded enum value; the sniffed bytes
     * are never stored, logged, or transmitted. Absent when no magic matched.
     *
     * Conservative by design: formats without a reliable fixed magic at the
     * file head (safetensors' u64-length-prefixed JSON, ONNX protobuf) are
     * intentionally NOT detected here — they are covered by [GenericExt]
     * only — to avoid false positives inflating a bucket.
     *
     * Populated only when the event's format is GENERIC.
     */
    enum class DetectedFormat(val value: String) {
        GGUF("gguf"),
        PARQUET("parquet"),
        ARROW_IPC("arrow_ipc"),
        SPIRV("spirv"),
        DEX("dex"),
        OAT("oat"),
        HDF5("hdf5"),
        SQLITE("sqlite"),
        ZIP("zip"),
        GZIP("gzip"),
        PDF("pdf"),
        PNG("png"),
        // Archive / compression container family. Added to gauge demand for
        // browsing these in Hexana (it already has an archive-members view for
        // zip/jar). All have a reliable fixed head magic except TAR, whose
        // `ustar` magic sits at offset 257.
        TAR("tar"),
        ZSTD("zstd"),
        SEVENZIP("sevenzip"),
        XZ("xz"),
        BZIP2("bzip2"),
        RAR("rar"),
        LZ4("lz4"),
        CAB("cab"),
        ;

        companion object {
            /**
             * Classifies a file by its leading magic bytes. [header] may be
             * the whole file or just its head — only a short prefix is
             * examined (through tar's `ustar` magic at offset 257, so callers
             * must pass at least 262 bytes for tar to be detectable). Returns
             * null when no known magic matched. Shared by the IDE-plugin and
             * VS Code surfaces so the magic table has a single source of truth.
             */
            fun fromMagic(header: ByteArray): DetectedFormat? = when {
                header.startsWith(0x47, 0x47, 0x55, 0x46) -> GGUF // "GGUF"
                header.startsWith(0x50, 0x41, 0x52, 0x31) -> PARQUET // "PAR1"
                header.startsWith(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31) -> ARROW_IPC // "ARROW1"
                header.startsWith(0x03, 0x02, 0x23, 0x07) -> SPIRV // SPIR-V 0x07230203 LE
                header.startsWith(0x07, 0x23, 0x02, 0x03) -> SPIRV // SPIR-V BE
                header.startsWith(0x64, 0x65, 0x78, 0x0A) -> DEX // "dex\n"
                header.startsWith(0x6F, 0x61, 0x74, 0x0A) -> OAT // "oat\n"
                header.startsWith(0x89, 0x48, 0x44, 0x46) -> HDF5 // "\x89HDF"
                header.startsWith(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65) -> SQLITE // "SQLite"
                header.startsWith(0x50, 0x4B, 0x03, 0x04) -> ZIP // "PK\x03\x04"
                header.startsWith(0x1F, 0x8B) -> GZIP
                header.startsWith(0x25, 0x50, 0x44, 0x46) -> PDF // "%PDF"
                header.startsWith(0x89, 0x50, 0x4E, 0x47) -> PNG // "\x89PNG"
                header.startsWith(0x28, 0xB5, 0x2F, 0xFD) -> ZSTD // Zstandard frame
                header.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SEVENZIP // "7z\xBC\xAF'\x1C"
                header.startsWith(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> XZ // "\xFD7zXZ\x00"
                header.startsWith(0x42, 0x5A, 0x68) -> BZIP2 // "BZh"
                header.startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> RAR // "Rar!\x1A\x07" (v4 and v5)
                header.startsWith(0x04, 0x22, 0x4D, 0x18) -> LZ4 // LZ4 frame
                header.startsWith(0x4D, 0x53, 0x43, 0x46) -> CAB // "MSCF"
                header.matchesAt(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> TAR // "ustar" at offset 257 (POSIX/GNU)
                else -> null
            }

            private fun ByteArray.startsWith(vararg expected: Int): Boolean = matchesAt(0, *expected)

            private fun ByteArray.matchesAt(offset: Int, vararg expected: Int): Boolean {
                if (offset < 0 || size < offset + expected.size) return false
                for (i in expected.indices) {
                    if (this[offset + i] != expected[i].toByte()) return false
                }
                return true
            }
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