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
    @McpDescription(
        """
    Extracts all **imported functions** from a WebAssembly (.wasm) module and returns
    them as JSON-serialized ImportSignature objects implementing WasmSignature.

   
    🧠 Semantics

    Each imported function entry is represented as:
      • funcidx   — the function's **funcidx**, i.e. its assigned function index
                   in the module’s unified function index space.
      • module  — the imported module name ("env", "wasi_snapshot_preview1", etc.)
      • name    — the field name of the imported function
      • signature — parsed parameter/result types, when available.

    The tool corresponds to the WebAssembly Import Section where:
      (import "module" "name" (func (param ...) (result ...)))

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "imports": [
            {
              "funcidx": 0,
              "module": "env",
              "name": "log",
              "signature": {
                "params": ["i32"],
                "return": []
              }
            },
            {
              "funcidx": 1,
              "module": "wasi_snapshot_preview1",
              "name": "fd_write",
              "signature": {
                "params": ["i32", "i32", "i32", "i32"],
                "return": ["i32"]
              }
            }
          ]
        }

      ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --imports

      wasm-objdump:
        wasm-objdump -x module.wasm | grep IMPORT

      wasm2wat:
        wasm2wat module.wasm | grep "(import"

    Example CLI output equivalent:

        (import "env" "log" (func (param i32)))
        (import "wasi_snapshot_preview1" "fd_write" (func (param i32 i32 i32 i32) (result i32)))

    ───────────────────────────────────────────────────────────────
    📌 Notes
    • This tool returns only **function imports** (not tables, memories, or globals).
    • The index field refers to the **function index in the expanded index space**,
      following the WebAssembly spec.
    • The result format is intentionally structured for LLM reasoning and IDE tooling.
    """
    )
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
    them as JSON-serialized objects:

        {
          "name": string,
          "entry": {
            "funcidx": number,
            "module": string,
            "name": string,
            signature: {
              "params": List<String>,
              "ret": List<String>
            }
          }
        }
        for re-imported functions, or 
        {
          "name": string,
          "entry": {
            "funcidx": number,
            "offset": number,
            signature: {
              "params": [string],
              "ret": [string]
            },
            "locals":[String],
            "code": string
          }
        
      • module   — the module path of the import ("env", "wasi_snapshot_preview1", etc.)
      • name     — the imported function's name
      • entry — description of the function entry point (imported or general)
      • funcidx     — function index in the module
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
              "entry": {
                "index": 1,
                "offset": 280,
                "signature": { "params": ["i32", "i32"], "results": ["i32"] },
                "locals": [],
                "code": "(local.get 0) (local.get 1) (i32.add)"
              }
            },
            {
              "name": "log",
              "entry": {
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

    @McpTool("functions_for_indices")
    @McpDescription(
        """
    Returns detailed function records for a given list of WebAssembly
    function indices (funcidx). The output format resolves whether each
    index corresponds to:

      1) a re-exported **imported function**, or
      2) a **locally defined function** within the module.

    Each returned object contains:
        • name   — the exported name (if applicable)
        • entry  — a full structured representation of the function body
                   or imported signature

    ───────────────────────────────────────────────────────────────
    🧩 Output format for re-imported (imported) functions

        {
          "name": string,
          "entry": {
            "funcidx": number,
            "module": string,
            "name": string,
            "signature": {
              "params": [string],
              "ret": [string]
            }
          }
        }

      • funcidx  — function index (non-local definition)
      • module   — imported module name ("env", "wasi_snapshot_preview1", etc.)
      • name     — field name of the imported function
      • signature — parameter + result types from the import description

    ───────────────────────────────────────────────────────────────
    🧩 Output format for locally defined functions

        {
          "name": string,
          "entry": {
            "funcidx": number,
            "offset": number,
            "signature": {
              "params": [string],
              "ret": [string]
            },
            "locals": [string],
            "code": string
          }
        }

      • funcidx  — function index inside the function index space
      • offset   — code-section byte offset for the function body
      • signature — parameter + result types
      • locals   — list of local declarations as strings
      • code     — textual / disassembled form of the function body

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "functions": [
            {
              "name": "log",
              "entry": {
                "funcidx": 0,
                "module": "env",
                "name": "log",
                "signature": {
                  "params": ["i32"],
                  "ret": []
                }
              }
            },
            {
              "name": "add",
              "entry": {
                "funcidx": 1,
                "offset": 320,
                "signature": {
                  "params": ["i32", "i32"],
                  "ret": ["i32"]
                },
                "locals": ["i32"],
                "code": "(local.get 0) (local.get 1) (i32.add)"
              }
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details

      wasm-objdump:
        wasm-objdump -x module.wasm | grep FUNC

      wasm2wat:
        wasm2wat module.wasm | sed -n '/func /,/)/p'

    ───────────────────────────────────────────────────────────────
    📌 Notes
    • This tool resolves BOTH imported and internally-defined
      function entries.
    • It operates strictly on the **function index space**
      (imported functions come first, followed by defined functions).
    • Returned structures are tailored for LLM reasoning and IDE tooling,
      with explicit signatures, locals, offsets, and optional bytecode dump.
    • The 'name' field refers to the export name if the function is exported,
      otherwise it may be derived from debug names or remain synthetic.
    """
    )
    suspend fun functionsForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of function indices to retrieve details for") indices: List<Int>
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.functionsByIndices(wasmModule, indices))
    }

    @McpTool("list_globals")
    @McpDescription(
        """
    Extracts all global variables from a WebAssembly (.wasm) module and
    returns them as structured JSON records. Each returned global entry
    describes the global's index, its value type, mutability, and the
    initialization expression represented as a stringified opcode sequence.

    ───────────────────────────────────────────────────────────────
    🧩 Output JSON format

        {
          "globalidx": number,
          "type": string,
          "mutability": boolean,
          "expression": string
        }

    • globalidx    — the zero-based index of the global in the module's
                      global index space.
    • type         — a human-readable, stringified Wasm value type such as:
                      "i32", "i64", "f32", "f64", "v128", etc.
    • mutability   — true if the global is mutable (var), false if immutable (const).
    • expression   — the stringified initializer expression containing the
                      Wasm opcode sequence used to establish the global’s
                      initial value (e.g. "i32.const 42", "global.get 0").

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "globals": [
            {
              "globalidx": 0,
              "type": "i32",
              "mutability": false,
              "expression": "i32.const 42"
            },
            {
              "globalidx": 1,
              "type": "i64",
              "mutability": true,
              "expression": "global.get 0"
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --globals

      wasm-objdump:
        wasm-objdump -x module.wasm | grep GLOBAL

      wasm2wat:
        wasm2wat module.wasm | grep "(global"

    Example CLI output (wasm-tools):
        (global (mut i32) (i32.const 42))
        (global i64 (global.get 0))

    ───────────────────────────────────────────────────────────────
    📌 Notes

    • This tool returns only global declarations (not memories, tables,
      functions or element segments).
    • The initializer expression is normalized into a readable textual
      sequence of Wasm opcodes to aid LLM reasoning.
    • The returned type string follows the standard Wasm type syntax.
    """
    )
    suspend fun listGlobals(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ):String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listGlobals(wasmModule))
    }

    @McpTool("list_memory")
    @McpDescription(
        """
    Returns the list of all linear memories declared in a WebAssembly (.wasm)
    module. Each memory entry includes its memory index (memidx) and its 
    WebAssembly limits represented in an AI-friendly, self-describing format.

    ───────────────────────────────────────────────────────────────
    🧩 Output JSON format

        {
          "memories": [
            {
              "memidx": number,
              "limits": {
                "addressType": "i32" | "i64",
                "min": number,
                "max": number | null,
                "hasMax": boolean,
                "range": "n..m" | "n..ε",
                "flag": "0x00" | "0x01" | "0x04" | "0x05"
              }
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🧠 Meaning of the fields

      • memidx       — linear memory index in the module (0-based)
      • addressType  — "i32" for 32-bit memory, "i64" for 64-bit memory
      • min          — minimum number of pages (or elements for table-like limits)
      • max          — maximum number of pages (null if unbounded)
      • hasMax       — explicit boolean so AI does not infer max incorrectly
      • range        — human-friendly format:
                          "min..m"  (bounded)
                          "min..ε"  (unbounded)
      • flag         — original binary prefix from the Wasm encoding:
                          0x00 — (i32, min only)
                          0x01 — (i32, min..max)
                          0x04 — (i64, min only)
                          0x05 — (i64, min..max)

    These encodings correspond directly to WebAssembly spec section 5.3.9.

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "memories": [
            {
              "memidx": 0,
              "limits": {
                "addressType": "i32",
                "min": 1,
                "max": null,
                "hasMax": false,
                "range": "1..ε",
                "flag": "0x00"
              }
            },
            {
              "memidx": 1,
              "limits": {
                "addressType": "i32",
                "min": 2,
                "max": 8,
                "hasMax": true,
                "range": "2..8",
                "flag": "0x01"
              }
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --memory

      wasm-objdump:
        wasm-objdump -x module.wasm | grep "Memory" -A 2

      wasm2wat:
        wasm2wat module.wasm | grep "(memory"

    Example Wasm textual form:
        (memory (export "mem") 2 8)
        (memory 1)

    ───────────────────────────────────────────────────────────────
    📌 Notes

    • This tool returns only memories, not tables or globals.
    • The limits representation is designed for LLM correctness:
         - explicit max presence (hasMax)
         - stringified range (n..m)
         - explicit addressType
         - preserved binary flag for tooling
    • Linear memory units are in WebAssembly pages (64 KiB each).
    """
    )
    suspend fun listMemory(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listMemory(wasmModule))
    }
}
