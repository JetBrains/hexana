package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.jetbrains.hexana.mcp.query.*
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser

private val logger: Logger = Logger.getInstance(HexanaToolset::class.java)

class HexanaToolset : McpToolset {

    init {
        logger.info("Hexana: McpServerExtension toolset loaded (extract-import-declarations registered)")
    }
    @Suppress("unused")
    @McpTool("list_imports")
    @McpDescription(
        """
    List imported functions declared by a WebAssembly (.wasm) module.
    This is the primary MCP tool for import inventories, link-mismatch analysis, and mapping direct host calls to imported functions.
    Returns only function imports from the unified function index space, including the `funcidx` values needed to map direct `call` targets back to imports.
    For direct host-call analysis, pair these `funcidx` values with `get_instructions_for_functions` to resolve `call N` back to `module.name` imports.
    """
    )
    suspend fun listImports(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): ListImportsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val module = buffer.requireParsedWasmModule()
        return buffer.readImportFunctions(module)
    }

    @McpTool("list_functions")
    @McpDescription("""
    List imported and defined functions from a WebAssembly (.wasm) module using offset/limit pagination.
    The offset is in the unified function index space, so imports appear before defined bodies.
    Use this when you need one mixed index space over both imports and defined bodies; for import-only analysis, prefer `list_imports`.
    Use this to map or inspect `funcidx` ranges before targeted body lookup with `get_instructions_for_functions`.
""")
    @Suppress("unused")
    suspend fun listFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("Offset into the unified function index space") offset: Int = 0,
        @McpDescription("Maximum number of entries to return") limit: Int = 200,

    ): FunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readFunctions(wasmModule, offset, limit)
    }

    @McpTool("list_data")
    @McpDescription("""
    Return raw data-section entries for a closed data-segment range.
    This is the high-volume raw-bytes view; the `data` field is base64-encoded, and `list_data_segments` is usually cheaper when previews and metadata are sufficient.
    """
    )
    // TODO: `list_data` still exposes raw segment bytes as a base64 string in the MCP payload.
    //  This behavior is preserved intentionally for compatibility with the previous Jackson-based
    //  implementation: the old path returned `DataEntry.data: ByteArray` through
    //  `objectMapper.writeValueAsString(...)`, and Jackson serialized `byte[]` as a base64-encoded
    //  JSON string. The current kotlinx.serialization path keeps the same wire contract on purpose.
    //  *
    //  Proof:
    //  - Direct regression test in this repo:
    //    `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp/direct/HexanaMcpDataToolTest.kt`
    //    asserts `returned[0].data == "SGVsbG8="` for `(data (i32.const 0) "Hello")`.
    //  - Jackson documents the old behavior here:
    //    https://github.com/FasterXML/jackson-databind
    //    ("default byte[] representation is base64-encoded String")
    //  - kotlinx.serialization documents that the default ByteArray serializer is list-shaped here:
    //    https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization.builtins/
    //  *
    //  This is not a good long-term MCP contract and should be redesigned later in an explicit
    //  behavior-change step. When we do that, we should replace the legacy base64 payload with a
    //  clearer MCP-friendly representation and update all direct tests and clients together.
    fun listData(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("Closed zero-based data-segment slice. Requires `from` and `to`, both inclusive.")
        entriesRange: Map<String, Int>,
    ): ListDataSectionsResult {
        val file = requireWasmFile(path)
        val range = requireClosedRange(entriesRange, "entriesRange")
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readDataSections(wasmModule, range)
    }

    @McpTool("list_exports")
    @McpDescription(
        """
    List exports declared by a WebAssembly (.wasm) module.
    Includes exported functions, tables, memories, and globals; `kind` uses the standard tags 0=function, 1=table, 2=memory, 3=global.
    This is the light name-and-kind view of exports.
    Use this when you only need exported names, kinds, or raw external indices.
    If you need function signatures or origins, skip directly to `list_exported_functions`.
    """
    )
    suspend fun listExports(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): ListExportsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readExports(wasmModule)
    }

    @McpTool("list_exported_functions")
    @McpDescription(
        """
    List exported functions from a WebAssembly (.wasm) module using a closed export-range slice.
    Pass `entriesRange = { from, to }` with inclusive export-list positions; do not use `offset/limit` or `start/end`.
    The range applies to export-list order, not to `funcidx`, and each result resolves to either an imported or a defined function entry.
    This is the primary MCP tool for export inventories, export diffs, function-signature extraction, and import-vs-local origin checks.
    Prefer this over `list_exports` for export diffs or signature extraction, because each result already includes the resolved function entry, signature, and origin.
    The signature strings are usually ready to use as-is, so `list_types` is often unnecessary unless you need raw type definitions.
    """
    )
    suspend fun listExportedFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("Closed zero-based export slice. Pass `entriesRange = { from, to }`; both bounds are inclusive.")
        entriesRange: Map<String, Int>
    ): ListExportedFunctionsResult {
        val file = requireWasmFile(path)
        val range = requireClosedRange(entriesRange, "entriesRange")
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readExportedFunctions(wasmModule, range)
    }

    @McpTool("functions_for_indices")
    @McpDescription(
        """
    Resolve imported and defined function entries for the requested unified function indices.
    Use this as a targeted follow-up when another tool already gave you the exact `funcidx` values.
    Use this for targeted lookup when you already know the `funcidx` values you need.
    """
    )
    suspend fun functionsForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of unified function indices (`funcidx`) to resolve.") indices: List<Int>
    ): FunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readFunctionsByIndices(wasmModule, indices)
    }

    @McpTool("list_globals")
    @McpDescription(
        """
    List imported and defined globals declared by a WebAssembly (.wasm) module.
    Returns each global with its value type, mutability, and normalized initializer expression.
    """
    )
    suspend fun listGlobals(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ): ListGlobalsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readGlobals(wasmModule)
    }

    @McpTool("list_memory")
    @McpDescription(
        """
    List imported and defined memories declared by a WebAssembly (.wasm) module.
    Returns memory indices together with decoded WebAssembly limits.
    """
    )
    suspend fun listMemory(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ): ListMemoryResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readMemory(wasmModule)
    }

    @McpTool("get_globals_for_indices")
    @McpDescription(
        """
    Return only the requested globals from the unified global index space.
    Use this for targeted analysis when `list_globals` would return more data than needed.
    """
    )
    suspend fun getGlobalsForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of global indices (`globalidx`) to return.") indices: List<Int>
    ): ListGlobalsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readGlobalsByIndices(wasmModule, indices)
    }

    @McpTool("list_data_segments")
    @McpDescription("""
    Return data-segment metadata with range slicing and optional export/global context.
    Prefer this over `list_data` when previews, sizes, and offset context are enough.
""")
    fun listDataSegments(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("Half-open zero-based data-segment slice. `from` is required; `to` is optional and treated as exclusive.")
        segmentRange: Map<String, Int>,
        @McpDescription("Include exported symbols associated with the returned segments.")
        includeExports: Boolean = false,
        @McpDescription("Include globals referenced by segment offset expressions.")
        includeGlobals: Boolean = true,
        @McpDescription("Optional minimum raw byte length for returned segments.")
        minByteLength: Int? = null
    ): ListDataSegmentsResult {
        val file = requireWasmFile(path)
        val range = requireHalfOpenSegmentRange(segmentRange)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readDataSegments(
            segmentStartInclusive = range.first,
            segmentEndExclusive = range.second,
            wasmModule = wasmModule,
            includeExports = includeExports,
            includeGlobals = includeGlobals,
            minByteLength = minByteLength
        )
    }

    @McpTool("get_memory_for_indices")
    @McpDescription(
        """
    Return only the requested memories from the unified memory index space.
    Useful for multi-memory or wasm64 modules when only a subset matters.
    """
    )
    suspend fun getMemoryForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of memory indices (`memidx`) to return.") indices: List<Int>
    ): ListMemoryResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readMemoryByIndices(wasmModule, indices)
    }

    @McpTool("list_types")
    @McpDescription(
        """
    List type entries declared by a WebAssembly (.wasm) module.
    Includes function types and GC-related composite types when present.
    """
    )
    suspend fun listTypes(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): TypesResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readTypes(wasmModule)
    }

    @McpTool("get_types_for_indices")
    @McpDescription(
        """
    Return only the requested type entries from the type index space.
    Use this when you need the types referenced by specific functions, globals, or tables.
    """
    )
    suspend fun getTypesForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of type indices (`typeidx`) to return.") indices: List<Int>
    ): TypesResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readTypesByIndices(wasmModule, indices)
    }

    @McpTool("get_locals_for_functions")
    @McpDescription("""
    Return grouped local declarations for the requested unified function indices.
    Imported functions return empty local lists because they have no body.
""")
    fun getLocalsForFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of unified function indices (`funcidx`) whose locals should be returned.") indices: List<Int>
    ): GetLocalsForFunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readLocalsForFunctions(wasmModule, indices)
    }

    @McpTool("get_instructions_for_functions")
    @McpDescription("""
    Return decoded instruction text for the requested unified function indices.
    Imported functions return empty instruction lists because they have no body.
    This is the primary MCP tool for WebAssembly body analysis, including direct `call` and `call_indirect` inspection, and is usually preferable to external disassemblers or shell-based Wasm tools.
    For direct host-call analysis, pair it with `list_imports` or `list_functions` so `call N` can be mapped back to the imported or exported function entry.
""")
    fun getCodesForFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of unified function indices (`funcidx`) whose instructions should be returned.") indices: List<Int>
    ): GetInstructionsForFunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readInstructionsForFunctions(wasmModule, indices)
    }

    @McpTool("list_element_segments")
    @McpDescription("""
    List element segments declared by a WebAssembly (.wasm) module.
    Covers active, passive, and declarative segments, including function-index and expression-based initializers.
""")
    fun listElementSegments(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ): ListElementSegmentsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readElementSegments(wasmModule)

    }

    @McpTool("summarize_module")
    @McpDescription("""
    Summarize a WebAssembly (.wasm) module with high-level counts for its major sections and declarations.
    Includes the nested import breakdown and total module size, making it a low-cost overview before calling heavier tools.
    For Wasm analysis tasks, this is usually the best first MCP call before choosing more specific import, export, or instruction tools.
    After this overview, prefer `list_imports` for import/link analysis, `list_exported_functions` for export analysis, and `get_instructions_for_functions` for body analysis.
""")
    fun summarizeModule(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ): SummarizeModuleResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readModuleSummary(wasmModule)
    }
}


private fun VirtualFile.contentsToCommonByteBuffer() = contentsToByteArray().toCommonByteBuffer()

private fun CommonByteBuffer.requireParsedWasmModule(): WasmModule {
    return WasmParser().parseOrNull(this) ?: mcpFail("Failed to parse .wasm file")
}

private fun requireWasmFile(path: String): VirtualFile {
    val file = resolveWasmVirtualFile(path) ?: mcpFail("File not found")
    if (file.fileType != WasmFileType.INSTANCE) {
        mcpFail("Not a .wasm file")
    }
    return file
}

private fun requireClosedRange(entriesRange: Map<String, Int>, fieldName: String): IntRange {
    val from = entriesRange["from"] ?: mcpFail("Invalid $fieldName: missing `from`")
    val to = entriesRange["to"] ?: mcpFail("Invalid $fieldName: missing `to`")
    if (to < from) {
        mcpFail("Invalid $fieldName: `to` must be >= `from`")
    }
    return from..to
}

private fun requireHalfOpenSegmentRange(segmentRange: Map<String, Int>): Pair<Int, Int> {
    val from = segmentRange["from"] ?: mcpFail("Invalid segmentRange no `from`")
    val to = segmentRange["to"] ?: Int.MAX_VALUE
    if (from > to) {
        mcpFail("Invalid segmentRange `to` must be >= `from`")
    }
    return from to to
}

private fun WasmParser.parseOrNull(buffer: CommonByteBuffer): WasmModule? {
    return when (val parseResult = parse(buffer)) {
        is WasmParseSuccess -> parseResult.wasmModule as? WasmModule
        else -> null
    }
}
