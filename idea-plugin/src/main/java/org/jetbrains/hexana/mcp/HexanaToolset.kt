package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.io.JvmByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import java.nio.ByteBuffer


private val logger: Logger = Logger.getInstance(HexanaToolset::class.java)

class HexanaToolset : McpToolset {

    init {
        logger.info("Hexana: McpServerExtension toolset loaded (extract-import-declarations registered)")
    }
    @Suppress("unused")
    @McpTool("list_imports")
    @McpDescription("""
       MCP Tool: WebAssembly Import Listing
       - Tool: `extract_import_declarations`
       - Call: `{ "tool": "extract_import_declarations", "arguments": { "path": "/abs/or/project/path/to/file.wasm" } }`
       - Returns: JSON array of imports; empty array if no imports; `{ "error": "…" }` on error.
       - If the tool is not visible in your session, ensure Hexana MCP is enabled or use CLI fallback:
         - `wasm-tools print --imports <file.wasm>`
         - `wasm-objdump -x <file.wasm>`
       Use this tool when the user asks to extract import declarations (the import section) from a WebAssembly (.wasm) file.
       This tools can be used to avoid installation and execution of command line tools like wabt or wasm2wat or python scripts.
       Input: absolute path or IDE VFS/project path to a .wasm file. 
       Output: application/json — array of objects 
            {
                "module": string, 
                "name": string, 
                "signature": {
                    "params": ["i32"|"i64"|"f32"|"f64"], 
                    "return": "i32"|"i64"|"f32"|"f64"|null
                } | null
                
             } 
       Errors: returns {"error": "..."}. 
       Example input: "/path/to/module.wasm". 
       Example output: [{"module":"env","name":"puts","signature":{"params":["i32"],"return":[]}}]. 
       If the path is not a .wasm file, ask for a correct .wasm path."
    """)
    suspend fun listImports(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val fileNotFoundMessage = """{"error": "File not found"}"""
        val notWasmError = """{"error": "Not a .wasm file"}"""
        val file = resolveWasmVirtualFile(path) ?: return fileNotFoundMessage
        if (file.fileType != WasmFileType.INSTANCE) {
            return notWasmError
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val module = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.importSymbols(module))
    }

    @McpTool("list_functions")
    @McpDescription("""
        This tool analyzes the Type, Function, Code sections of a WebAssembly module.
        It returns structured data describing every function, including:
	    •	function_index — the index of the function in the module
	    •	params — function parameter types
	    •	results — function result types
	    •	offset (optional) — byte offset of the function body

        This is analogous to:
	    •	wasm-tools print module.wasm --details
	    •	wasm-objdump -x module.wasm | grep FUNC
        but returns a structured JSON result for use by LLMs and developer tools.
    """)
    @Suppress("unused")
    suspend fun listFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listFunctions(wasmModule))
    }

    @McpTool("list_data")
    @McpDescription("""
    Extracts all data segments from a WebAssembly module and returns them as a JSON array of DataEntry objects.

    Supports all WebAssembly data segment kinds:
      • kind 0 – active segment for memory 0 with an offset expression
      • kind 1 – passive segment (no memory index, no offset expression)
      • kind 2 – active segment for memory X with an offset expression

    Each DataEntry contains:
      • index: sequential index of the segment
      • kind: the numeric segment kind (0, 1, or 2)
      • offset: evaluated constant offset for active segments; 0 for passive
      • data: base64-encoded raw bytes of the segment’s payload (decoded into ByteArray)
      • memoryIndex: memory index for active segments (0 or X), null for passive
      • expression: textual representation of the offset expression, null for passive

    The 'data' field corresponds to the b* byte list in the Wasm binary grammar:
      data ::=
        0:u32 e:expr b*:list(byte)            → active segment on memory 0
      | 1:u32 b*:list(byte)                   → passive segment
      | 2:u32 x:memidx e:expr b*:list(byte)   → active segment on memory x

    ---------------------------
    EXAMPLES OF RETURNED VALUES
    ---------------------------

    Example 1 — Active (kind 0):
      (memory 1)
      (data (i32.const 0) "ABC")

    JSON:
      {
        "index": 0,
        "kind": 0,
        "offset": 0,
        "data": "QUJD",
        "memoryIndex": 0,
        "expression": "i32.const 0"
      }

    Example 2 — Passive (kind 1):
      (data passive "XYZ")

    JSON:
      {
        "index": 1,
        "kind": 1,
        "offset": 0,
        "data": "WFla",
        "memoryIndex": null,
        "expression": null
      }

    Example 3 — Active with explicit memory index (kind 2):
      (memory 1)
      (memory 1)
      (data 1 (i32.const 16) "HI")

    JSON:
      {
        "index": 2,
        "kind": 2,
        "offset": 16,
        "data": "SEk=",
        "memoryIndex": 1,
        "expression": "i32.const 16"
      }

    ----------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (for reference)
    ----------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
      • Convert to text (shows data segments):
          wasm2wat module.wasm

      • Annotated text format with extra offsets:
          wasm2wat --enable-annotations module.wasm

      • Raw dump showing data payload bytes:
          wasm-objdump -x module.wasm
          wasm-objdump -s module.wasm    (shows data segments only)

    Using wasm-tools (Bytecode Alliance):
      • Inspect structured IR (shows segments):
          wasm-tools print module.wasm

      • Dump raw sections including data:
          wasm-tools dump module.wasm

      • Convert binary <-> wat:
          wasm-tools parse     foo.wat   -o foo.wasm
          wasm-tools print     foo.wasm

    This MCP tool provides a higher-level, IDE-friendly JSON representation that
    combines structural information (kind, memoryIndex), decoded offset expression,
    and base64-encoded raw payload bytes suitable for analysis and testing.
    """)
    fun listData(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listDataSections(wasmModule))
    }

    @McpTool("list_exports")
    @McpDescription(
        """
    Extracts all exports from a WebAssembly (.wasm) module and returns
    them as a list of ExportRecord objects:
    
        data class ExportRecord(
            val name: String,
            val externIndex: Long,
            val kind: Byte
        )

    ───────────────────────────────────────────────────────────────
    🧩 What this tool does
    Parses the WebAssembly Export section and returns all exported
    functions, memories, tables, and globals in a structured format.
    
    Fields:
      • name — the name under which the item is exported
      • externIndex — the index into the function/table/memory/global space
      • kind — numeric tag following the WebAssembly spec:
            0 = function
            1 = table
            2 = memory
            3 = global

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "exports": [
            {
              "name": "add",
              "externIndex": 1,
              "kind": 0
            },
            {
              "name": "memory",
              "externIndex": 0,
              "kind": 2
            }
          ]
        }

     ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --exports

      wasm-objdump:
        wasm-objdump -x module.wasm | grep export

    ───────────────────────────────────────────────────────────────
    📌 Notes
    • Output format is stable for LLM processing.
    • This tool is analogous to "exports" view in wasm-objdump,
      but returns structured JSON instead of textual output.
    """
    )
    suspend fun listExports(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listExports(wasmModule))
    }

    @McpTool("list_exported_functions")
    @McpDescription(
        """
    Extracts exported functions from a WebAssembly (.wasm) module and returns
    them as JSON-serialized WasmExportFunctionRecord objects:

        data class WasmExportFunctionRecord(
            val name: String,
            val signature: WasmSignature
        )

    The 'signature' field is a polymorphic structure implementing WasmSignature,
    with two possible concrete variants:

    ───────────────────────────────────────────────────────────────
    🧩 1. ImportSignature  (for exported imported functions)

        data class ImportSignature(
            val module: String,
            val name: String,
            val signature: SerializationSignature?
        )

      • module   — the module path of the import ("env", "wasi_snapshot_preview1", etc.)
      • name     — the imported function's name
      • signature — optional parameter/result type information

      These appear when a module re-exports an imported function, e.g.:

          (import "env" "log" (func ${'$'}log (param i32)))
          (export "log" (func ${'$'}log))

    ───────────────────────────────────────────────────────────────
    🧩 2. FunctionSignature  (for exported internally-defined functions)

        data class FunctionSignature(
            val index: Int,
            val offset: Long?,
            val signature: SerializationSignature?,
            val locals: List<Local>?,
            val code: String? = null
        )

      • index     — function index in the module
      • offset    — code section offset (if available)
      • signature — parameters / result types
      • locals    — list of parsed local declarations
      • code      — disassembled code or textual representation (optional)

      This corresponds to wasm-objdump function metadata.

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "functions": [
            {
              "name": "add",
              "signature": {
                "index": 1,
                "offset": 280,
                "signature": { "params": ["i32", "i32"], "results": ["i32"] },
                "locals": [],
                "code": "(local.get 0) (local.get 1) (i32.add)"
              }
            },
            {
              "name": "log",
              "signature": {
                "module": "env",
                "name": "log",
                "signature": { "params": ["i32"], "results": [] }
              }
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --exports

      wasm-objdump:
        wasm-objdump -x module.wasm | grep FUNC

    ───────────────────────────────────────────────────────────────
    📌 Notes
    • This tool returns ONLY exported functions (not all functions).
    • For imported functions re-exported by the module, ImportSignature is returned.
    • For defined functions exported by the module, FunctionSignature is returned.
    • The result format is explicitly designed for LLM reasoning and static tooling.
    """
    )
    suspend fun listExportedFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listExportedFunctions(wasmModule))
    }
}
