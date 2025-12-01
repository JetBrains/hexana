package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.io.toCommonByteBuffer
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
        val buffer = file.contentsToCommonByteBuffer()
        val module = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.importFunctions(module))
    }

    @McpTool("list_functions")
    @McpDescription("""
    Extracts all functions from a WebAssembly module and returns them as a JSON array of FunctionEntry objects.

    Supports both imported and internally defined functions:
      • imported functions — declared in the Import Section, no code body
      • internal functions — declared in the Function + Code Sections with locals and body
      • exported functions — functions referenced by Export Section
      • functions referenced by element/table segments (via funcidx)

    Each FunctionEntry contains:
      • funcidx: unified function index (imports first, then internal functions)
      • definedIn: either "import" or "function_section"
      • import: { module, name } if imported; null otherwise
      • signature: { params: [...], return: [...] } using canonical WebAssembly type strings
      • exportedAs: list of export names (possibly empty)
      • codeOffset: byte offset of the function body in the Code Section (null for imports)
      • codeSize: size of the function body in bytes (null for imports)
      • instructionCount: number of decoded instructions (null for imports)

    The 'funcidx' space corresponds to the Wasm binary grammar:
        funcidx ::= import-funcidx*  followed by function-section-funcidx*

    Import functions precede all internal functions.

    ------------------------------------
    EXAMPLES OF RETURNED FUNCTION ENTRIES
    ------------------------------------

    Example 1 — Imported function (no body):
      (import "env" "abort" (func (param i32 i32 i32 i32)))

    JSON:
      {
        "funcidx": 0,
        "definedIn": "import",
        "import": { "module": "env", "name": "abort" },
        "signature": {
          "params": ["i32","i32","i32","i32"],
          "return": []
        },
        "locals": [],
        "exportedAs": [],
        "codeOffset": null,
        "codeSize": null,
        "instructionCount": null
      }

    Example 2 — Internal function with locals and small body:
      (func (export "increment") (param i32) (result i32)
        (local i32)
        local.get 0
        i32.const 1
        i32.add)

    JSON:
      {
        "funcidx": 1,
        "definedIn": "function_section",
        "import": null,
        "signature": {
          "params": ["i32"],
          "return": ["i32"]
        },
        "locals": ["i32"],
        "exportedAs": ["increment"],
        "codeOffset": 340,
        "codeSize": 24,
        "instructionCount": 7
      }

    Example 3 — Internal function referenced by table/elem:
      (table 5 funcref)
      (elem (i32.const 0) 2 3 4)

    JSON entry for funcidx = 3:
      {
        "funcidx": 3,
        "definedIn": "function_section",
        "import": null,
        "signature": { "params": [], "return": [] },
        "locals": [],
        "exportedAs": [],
        "codeOffset": 512,
        "codeSize": 18,
        "instructionCount": 2
      }

    -------------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (for reference)
    -------------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
      • Convert to text (shows func bodies):
          wasm2wat module.wasm

      • Show function imports/exports:
          wasm-objdump -x module.wasm

      • Show disassembly:
          wasm-objdump -d module.wasm

    Using wasm-tools (Bytecode Alliance):
      • Inspect structured IR:
          wasm-tools print module.wasm

      • Dump raw sections including code and functions:
          wasm-tools dump module.wasm

      • Convert binary ↔ wat:
          wasm-tools parse  foo.wat   -o foo.wasm
          wasm-tools print  foo.wasm

    This MCP tool provides a higher-level, IDE-friendly JSON representation that
    unifies imported + internal functions, expands locals, links exports,
    and adds a short decoded instruction preview suitable for analysis,
    navigation, and LLM reasoning.
""")
    @Suppress("unused")
    suspend fun listFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
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
        val buffer = file.contentsToCommonByteBuffer()
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
        val buffer = file.contentsToCommonByteBuffer()
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
        val buffer = file.contentsToCommonByteBuffer()
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
        val buffer = file.contentsToCommonByteBuffer()
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
        val buffer = file.contentsToCommonByteBuffer()
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
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listMemory(wasmModule))
    }

    @McpTool("get_globals_for_indices")
    @McpDescription(
        """
    Returns a subset of WebAssembly global variables selected by their
    global indices (globalidx). This tool behaves like list_globals,
    but filters the output to only the requested indices, which is often
    more efficient and more relevant for AI workflows, dependency tracing,
    and targeted analysis.

    ───────────────────────────────────────────────────────────────
    🧩 Input format

        {
          "file": "/absolute/path/to/module.wasm",
          "globalidx": [0, 3, 7, 12]
        }

    The globalidx list specifies exactly which globals should be returned.
    Indices follow the module's global index space.

    ───────────────────────────────────────────────────────────────
    🧩 Output JSON format

    For each requested global index, a JSON record is returned:

        {
          "globalidx": number,
          "type": string,          // e.g. "i32", "i64", "f32", "f64", "v128"
          "mutability": boolean,   // true = mutable, false = immutable
          "expression": string     // stringified Wasm opcode sequence
        }

    • type         — stringified WebAssembly value type  
    • mutability   — "var" = true, "const" = false  
    • expression   — initializer expression, normalized into a readable
                      sequence of Wasm opcodes (e.g. "i32.const 42",
                      "global.get 1", "i64.const 0", etc.)

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
              "globalidx": 7,
              "type": "i64",
              "mutability": true,
              "expression": "global.get 0"
            }
          ]
        }

    Only globals listed in the input appear in the response.

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --globals

      wasm-objdump:
        wasm-objdump -x module.wasm | grep GLOBAL -A 2

      wasm2wat:
        wasm2wat module.wasm | grep "(global"

    Example textual form:
        (global (mut i32) (i32.const 42))
        (global i64 (global.get 0))

    ───────────────────────────────────────────────────────────────
    📌 Notes

    • This tool returns only the globals explicitly requested, which is
      ideal for selective data extraction in LLM-driven workflows.
    • The initializer expression is normalized for readability, not raw
      binary opcode bytes.
    • globalidx refers to the unified global index space defined by the
      WebAssembly spec.
    """
    )
    suspend fun getGlobalsForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of function indices (globalidx) to retrieve details for") indices: List<Int>
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listGlobalsByIndices(wasmModule, indices))
    }

    @McpTool("get_memory_for_indices")
    @McpDescription(
        """
    Returns a filtered subset of linear memory declarations from a WebAssembly
    (.wasm) module, selected by their memory indices (memidx). This tool is the
    selective version of list_memory and is useful when AI or analysis workflows
    require only specific memories, especially in multi-memory or wasm64 modules.

    ───────────────────────────────────────────────────────────────
    🧩 Input format

        {
          "file": "/absolute/path/to/module.wasm",
          "memidx": [0, 2, 5]
        }

    The memidx list specifies the exact memory indices to return. Indices follow
    the module's memory index space.

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

    Meaning of fields:
      • memidx       — linear memory index in the module (0-based)
      • addressType  — "i32" for 32-bit memory, "i64" for 64-bit memory
      • min          — minimum number of pages required by the module
      • max          — maximum number of pages (null = unbounded)
      • hasMax       — explicit boolean to avoid inference errors
      • range        — human-readable representation ("1..ε", "2..8")
      • flag         — original Wasm encoding prefix:
                         0x00: (i32, min only)
                         0x01: (i32, min..max)
                         0x04: (i64, min only)
                         0x05: (i64, min..max)

    These correspond to the WebAssembly limits grammar in spec §5.3.9.

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
              "memidx": 2,
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

    Example Wasm textual output:
        (memory (export "mem") 2 8)
        (memory 1)

    ───────────────────────────────────────────────────────────────
    📌 Notes

    • This tool returns only the memories requested by their indices.
    • Especially useful for wasm64, multi-memory modules, or when only
      memory used by a specific function needs to be analyzed.
    • The limit representation is optimized for LLM correctness:
        - explicit maximum presence (hasMax)
        - human-readable range
        - preserved encoding flag
        - explicit addressType
    """
    )
    suspend fun getMemoryForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of memory indices (memidx) to retrieve details for") indices: List<Int>
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listMemoryByIndices(wasmModule, indices))
    }

    @McpTool("list_types")
    @McpDescription(
        """
    Returns all type definitions declared in a WebAssembly (.wasm) module.
    This includes all function types, reference types, and GC composite
    types (struct, array), as well as recursive type groups when present.
    The result is encoded using an AI-friendly, self-describing representation
    that avoids Wasm binary ambiguities and is optimal for LLM reasoning.

    ───────────────────────────────────────────────────────────────
    🧩 Output JSON format

        {
          "types": [
            {
              "typeidx": number,
              "kind": "func",
              "params": [string],
              "results": [string],
              "repr": "(func (param ...) (result ...))"
            },
            {
              "typeidx": number,
              "kind": "struct",
              "fields": [
                {
                  "type": { "kind": "val" | "ref" | "packed", ... },
                  "mutable": boolean
                }
              ],
              "repr": "(struct (field ...) ...)"
            },
            {
              "typeidx": number,
              "kind": "array",
              "element": {
                "type":  { "kind": "val" | "ref" | "packed", ... },
                "mutable": boolean
              },
              "repr": "(array ...)"
            },
            {
              "typeidx": number,
              "kind": "rec",
              "types": [ ... recursive element type entries ... ],
              "repr": "(rec (type ...) ...)"
            }
          ]
        }

    Each type entry includes:
      • typeidx — the index in the module’s type index space  
      • kind    — "func", "struct", "array", "rec", or future composite kinds  
      • params  — list of parameter value types (function types only)  
      • results — list of result types (function types only)  
      • fields  — array of field descriptors (struct types)  
      • element — element descriptor (array types)  
      • repr    — readable s-expression form for LLM interpretation  
      • types   — nested types for recursive groups  

    Value types use:
        { "kind": "val", "type": "i32" }

    Reference types use:
        { "kind": "ref", "refKind": "eq" | "func" | "extern" | "any" | "i31" | "struct" | "array", "nullable": true/false }

    Packed types use:
        { "kind": "packed", "packType": "i8" | "i16" }

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "types": [
            {
              "typeidx": 0,
              "kind": "func",
              "params": ["i32"],
              "results": [],
              "repr": "(func (param i32))"
            },
            {
              "typeidx": 1,
              "kind": "func",
              "params": ["i32", "i32"],
              "results": ["i32"],
              "repr": "(func (param i32 i32) (result i32))"
            },
            {
              "typeidx": 2,
              "kind": "struct",
              "fields": [
                { "type": { "kind": "val", "type": "i32" }, "mutable": false },
                { "type": { "kind": "ref", "refKind": "eq", "nullable": true }, "mutable": true }
              ],
              "repr": "(struct (field i32) (field (ref null eq)))"
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --types

      wasm-objdump:
        wasm-objdump -x module.wasm | grep Type -A 5

      wasm2wat:
        wasm2wat module.wasm | grep "(type"

    Example textual output:
        (type (;0;) (func (param i32)))
        (type (;1;) (func (param i32 i32) (result i32)))
        (type (;2;) (struct (field i32) (field (ref null any))))

    ───────────────────────────────────────────────────────────────
    📌 Notes

    • All type forms defined in the module are returned, including GC types
      and recursive type groups when present.
    • The AI-friendly representation avoids raw bytes or binary encodings.
    • The `repr` field helps LLMs reason about type shapes and signature
      structures with minimal hallucination risk.
    • This tool is complementary to get_types_for_indices, which provides
      selective retrieval for typeidx-dependent analysis.
    """
    )
    suspend fun listTypes(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listTypes(wasmModule))
    }

    @McpTool("get_types_for_indices")
    @McpDescription(
        """
    Returns a subset of WebAssembly type definitions selected by their
    type indices (typeidx). This tool is the selective version of a
    full type listing and is used when only specific type signatures
    are relevant for analysis, signature resolution, or LLM-driven
    reasoning about function interfaces.

    ───────────────────────────────────────────────────────────────
    🧩 Input format

        {
          "file": "/absolute/path/to/module.wasm",
          "typeidx": [0, 3, 7]
        }

    The typeidx list specifies exactly which types should be returned.
    Indices follow the module's type index space.

    ───────────────────────────────────────────────────────────────
    🧩 Output JSON format

    Each returned entry has the form:

        {
          "typeidx": number,
          "kind": "func",
          "params": [string],
          "results": [string],
          "repr": "(param i32 i64) (result i32)"
        }

    Where:
      • typeidx  — index of the type in the Wasm type space
      • kind     — "func" (currently the only core Wasm type constructor)
      • params   — list of parameter types (e.g. "i32", "f64")
      • results  — list of result types (0 or 1 for MVP; multiple for 
                    multi-value Wasm)
      • repr     — a readable textual representation matching WebAssembly’s
                    S-expression syntax, useful for LLM reasoning

    Composite or reference types (future proposals) may also appear via:
        {
          "kind": "struct" | "array" | "subtype" | "rec",
          ...
        }
    but only if present in the module (e.g. GC types).

    ───────────────────────────────────────────────────────────────
    📘 Example response

        {
          "types": [
            {
              "typeidx": 0,
              "kind": "func",
              "params": ["i32"],
              "results": [],
              "repr": "(func (param i32))"
            },
            {
              "typeidx": 3,
              "kind": "func",
              "params": ["i32", "i32"],
              "results": ["i32"],
              "repr": "(func (param i32 i32) (result i32))"
            }
          ]
        }

    ───────────────────────────────────────────────────────────────
    🔧 Command-line equivalents for validation

      wasm-tools:
        wasm-tools print module.wasm --details --types

      wasm-objdump:
        wasm-objdump -x module.wasm | grep Type -A 4

      wasm2wat:
        wasm2wat module.wasm | grep "(type"

    Example textual equivalent:
        (type ${'$'}t0 (func (param i32)))
        (type ${'$'}t3 (func (param i32 i32) (result i32)))

    ───────────────────────────────────────────────────────────────
    📌 Notes

    • Only requested types appear in the output.
    • Output format is optimized for LLM correctness:
        - explicit kind
        - explicit param / result arrays
        - readable repr for reasoning
    • Multi-value returns and GC types are included if present.
    • This tool integrates naturally with function-index based tools
      that refer to type signatures by index.

    """
    )
    suspend fun getTypesForIndices(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of type indices (typeidx) to retrieve details for") indices: List<Int>
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listTypesByIndices(wasmModule, indices))
    }

    @McpTool("list_data_segments")
    @McpDescription("""
        Returns a detailed JSON report describing all data segments defined in a WebAssembly module. 
        
        Data segments represent raw byte arrays that the module declares in its Data Section. They are 
        used to initialize linear memory (active mode) or stored for later use via bulk-memory operations 
        (passive mode). Without a tool like this, users typically inspect WAT output manually or parse 
        semi-structured textual dumps from CLI tools. This MCP tool provides a normalized, consistent, 
        LLM-friendly structure that exposes segment modes, memory targeting, previews, offset expressions, 
        and related context (memories, globals, exported symbols), making it suitable for static analysis, 
        reverse engineering, and AI-assisted reasoning.
        
        This tool is especially useful for:
        - discovering embedded strings, panic messages, lookup tables, vtables
        - inspecting WASI FS objects or configuration blobs
        - reconstructing memory initialization and startup behavior
        - understanding how static data interacts with imported or declared memories
        - correlating memory offsets with instruction-level analysis
        - enabling LLMs to reason about memory structure without unsafe guessing
        
        ---
        
        ## Top-level JSON Structure (`McpDataSegmentsReport`)
        
        The report typically contains:
        
        - `segments`: a list of structured data segment records.
        - `summary`: aggregated metadata about the segments.
        - `memory`: a list of memory descriptors for the module.
        - `globals`: a list of global descriptors for the module.
        - `exported_symbols`: a list of exported symbols (for quick cross-reference).
        
        Exact field names and shapes follow the `McpDataSegmentsReport` Kotlin model, but this description 
        explains the semantics used by the tool.
        
        ---
        
        ## segments[]
        
        Each entry in `segments` describes a single data segment:
        
        - `dataidx`  
          Zero-based index of the segment in the Data Section.
        
        - `mode`  
          One of:
          - `"active"` — segment initializes a memory during instantiation.
          - `"passive"` — segment is not applied automatically and must be used via `memory.init`.
        
        - `memory` (null for passive segments)  
          Describes the target memory for active segments:
          - `memidx`: resolved memory index across both imported and locally defined memories  
            (imports come first, then memories from the Memory Section).
          - `definedIn`: `"import"` or `"memory_section"`.
          - `module` / `name`: present only for imported memories.
          - `limits`: minimum and optional maximum in pages, e.g. `{ "min": 2, "max": 4 }`.
        
        - `offsetExpr`  
          Stringified initializer expression for the memory offset. Examples:
          - `"i32.const 0"`
          - `"i32.add (i32.const 10) (global.get 0)"`
          Null for passive segments.
        
        - `byteLength`  
          Total number of bytes in the data segment.
        
        - `hexPreview`  
          Short hexadecimal preview (typically the first 32 bytes), for example:
          `"48 65 6C 6C 6F"`.
        
        - `utf8Preview`  
          Short UTF-8 preview if:
          - the first bytes decode strictly as UTF-8,
          - at least ~80% of characters are printable,
          - no null bytes are present.
          Otherwise this field is `null`.
        
        - `rawAvailable`  
          Indicates whether the full payload can be retrieved via the `get_data_segment` MCP tool.
        
        ---
        
        ## summary
        
        The `summary` object provides a compact high-level view over all segments and their usage. 
        Exact field names depend on `McpDataSegmentsReport`, but typical values include:
        
        - `totalSegments`  
          Number of data segments in the module.
        
        - `activeSegments` / `passiveSegments`  
          Counts of active and passive segments.
        
        - `memories`  
          Number of distinct memories referenced by active segments.
        
        - `totalBytes`  
          Sum of `byteLength` across all segments.
        
        - `segmentsByMemory`  
          Optional breakdown by `memidx` (how many segments / bytes per memory).
        
        - `globalsUsedForOffsets`  
          Optional list of global indices referenced in offset expressions, possibly annotated with their 
          import/module/name if known (e.g. globals like `__heap_base`).
        
        This allows an LLM or a human to quickly understand whether a module has many small segments, 
        a few large ones, multiple memories, and whether initialization depends on imported globals.
        
        ---
        
        ## memory
        
        The `memory` field (if present in the report) describes the module’s memories as seen in the 
        unified memory index space (imported memories first, then memories from the Memory Section).  
        Each entry typically includes:
        
        - `memidx`  
          Memory index.
        
        - `definedIn`  
          Where this memory comes from:
          - `"import"` — defined in the Import Section.
          - `"memory_section"` — defined in the Memory Section.
        
        - `module`, `name`  
          Only for imported memories, e.g. `"env"` / `"memory"`.
        
        - `limits`  
          Minimum and optional maximum (in pages), e.g. `{ "min": 1, "max": 2 }`.
        
        - `exportedAs` (optional)  
          Names under which this memory is exported, if any.
        
        This context makes it possible to interpret each segment’s `memory` reference and understand 
        how many distinct memories the module uses.
        
        ---
        
        ## globals
        
        The `globals` field (if present) lists all globals in the module in global-index order 
        (imported globals first, then locally defined ones). Each entry typically includes:
        
        - `globalidx`  
          Global index.
        
        - `definedIn`  
          `"import"` for imported globals or `"global_section"` for globals defined in the Global Section.
        
        - `module`, `name`  
          For imported globals: their import module/field pair, e.g. `"env"` / `"__heap_base"`.
        
        - `type`  
          Value type of the global (e.g. `"i32"`, `"i64"`, `"f32"`, `"f64"`, `"externref"`, `"funcref"`).
        
        - `mutability`  
          Boolean indicating whether the global is mutable.
        
        - `initExpr`  
          For locally defined globals, a stringified initializer expression 
          (e.g. `"i32.const 1024"`, `"global.get 0"`). For imported globals this is typically `null`.
        
        - `exportedAs` (optional)  
          Names under which the global is exported, if any.
        
        This allows the tool (and LLMs) to correlate offset expressions that use `global.get` with actual 
        globals (such as `__data_end` or `__heap_base`) and better understand how data segments are placed.
        
        ---
        
        ## exported_symbols
        
        The `exported_symbols` field (if present) provides a convenient view of the module’s exported API.  
        Depending on the `McpDataSegmentsReport` schema, each entry may include:
        
        - the exported name,  
        - the kind of the export (`"func"`, `"global"`, `"memory"`, `"table"`),  
        - the underlying index (e.g. `funcidx`, `globalidx`, `memidx`, `tableidx`).
        
        This field is useful to:
        - quickly see which functions/globals/memories are part of the public API,
        - cross-reference exported globals/memories with segments that depend on them,
        - let LLMs connect static data to exported entrypoints.
        
        ---
        
        ## Example: Active Segment Targeting Imported Memory
        
        ```json
        {
          "segments": [
            {
              "dataidx": 0,
              "mode": "active",
              "memory": {
                "memidx": 0,
                "definedIn": "import",
                "module": "env",
                "name": "memory",
                "limits": { "min": 2, "max": 4, addressType: "i32", hasMax: true, range: "2..4", flag: "0x00" }
              },
              "offsetExpr": "i32.const 1024",
              "byteLength": 5,
              "hexPreview": "48 65 6C 6C 6F",
              "utf8Preview": "Hello",
              "rawAvailable": true
            }
          ],
          "summary": {
            "totalSegments": 1,
            "activeSegments": 1,
            "passiveSegments": 0,
            "memories": 1,
          },
          "memory": [
            {
              "memidx": 0,
              "definedIn": "import",
              "module": "env",
              "name": "memory",
              "limits": { "min": 2, "max": 4, addressType: "i32", hasMax: true, range: "2..4", flag: "0x00" }
            }
          ],
          "globals": [],
          "exported_symbols": []
        }
        ───────────────────────────────────────────────────────────────
        🔧 Command-line equivalents for validation
        Workarounds with CLI Tools (wasm-tools / WABT)

        If this MCP tool is not available, similar information can be approximated via CLI,
        but only in a manual, unstructured way:

        Using wasm-tools
        1.	Convert the module to WAT and search for data segments:
            wasm-tools print module.wasm > module.wat
        2.	For a more low-level view:
            wasm-tools dump module.wasm --details | grep Data
        This prints raw data segment info but does not:
        •	resolve memory indices,
        •	show UTF-8 previews,
        •	or provide a summary or JSON structure.

        Using WABT (wasm2wat)
        wasm2wat module.wasm -o module.wat
        Again, the user must manually parse (data ...) and relate them to memories, globals, and exports.

        The list_data_segments MCP tool replaces these manual workflows with a structured JSON report
        that IDEs and LLMs can consume directly.
        
        ───────────────────────────────────────────────────────────────
        
        Typical Use Cases
            •	Inspect embedded assets (JSON configs, text resources, WASI filesystem entries).
            •	Identify panic/error strings in Rust, C/C++, AssemblyScript, and other toolchains.
            •	Reconstruct initialization sequences (__data_end, __heap_base, etc.).
            •	Assist reverse engineering and static analysis workflows.
            •	Analyze lookup tables, hash tables, jump tables, and binary struct regions.
            •	Provide contextual information to disassembly tools by correlating memory accesses
        with known static data segments.
    """)
    fun listDataSegments(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listDataSegments(wasmModule))
    }

    @McpTool("get_locals_for_functions")
    @McpDescription("""
    Extracts local variable declarations for the selected functions in a WebAssembly module.
    Accepts a list of function indices (funcidx) and returns a JSON array of LocalEntry objects,
    each describing the locals of one function.

    Supports both:
      • internally defined functions — locals come from the Code Section
      • imported functions — always return empty locals (no code body exists)

    Each LocalEntry contains:
      • funcidx: unified function index (imports first, then internal functions)
      • locals: optional grouped declaration form for debugging:
            [ { "count": N, "type": "i32" }, ... ]

    This tool is useful for:
      • reconstructing stack frames
      • understanding variable layouts
      • guiding disassembly and decompilation
      • LLM reasoning about control-flow and register allocation
      • correlating locals with get_local/set_local operations in code previews

    -----------------------------------
    EXAMPLES OF RETURNED LOCAL ENTRIES
    -----------------------------------

    Example 1 — Imported function (no locals):

      (import "env" "abort" (func (param i32 i32 i32)))

    JSON:
      {
        "funcidx": 0,
        "locals": [],
      }

    Example 2 — Function with a single local:

      (func (export "inc") (param i32) (result i32)
        (local i32)
        local.get 0
        i32.const 1
        i32.add)

    JSON:
      {
        "funcidx": 1,
        "locals": [
            { "count": 1, "type" :"i32" }
         ],
      }

    Example 3 — Function with grouped locals:

      (func (param f32) (result f32)
        (local f32 f32 i32)
        local.get 0
        local.get 1
        f32.add)

    JSON:
      {
        "funcidx": 2,
        "locals": [
          { "count": 2, "type": "f32" },
          { "count": 1, "type": "i32" }
        ],
      }

    -----------------------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (for reference / fallback)
    -----------------------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):

      • Convert to WAT (locals shown explicitly):
          wasm2wat module.wasm

      Example WAT:
          (func ${"$"}add (param i32 i32) (result i32)
            (local i32 i32)
            ...)

      • Annotated text showing locals with offsets/metadata:
          wasm2wat --enable-annotations module.wasm

      • Raw dump of the code section (locals appear after the body header):
          wasm-objdump -d module.wasm

    Using wasm-tools (Bytecode Alliance):

      • Structured, printable WAT:
          wasm-tools print module.wasm

      • Dump section headers, including local decl groups:
          wasm-tools dump module.wasm

      • Convert between wat <-> wasm:
          wasm-tools parse   foo.wat  -o foo.wasm
          wasm-tools print   foo.wasm

    Command-line tools require manual reading of local groups and matching 
    them to function indices. This MCP tool provides a higher-level,
    IDE-friendly JSON representation of locals directly indexed by funcidx,
    making it easy for analysis, navigation, and LLM reasoning.
""")
    fun getLocalsForFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of memory indices (memidx) to retrieve details for") indices: List<Int>
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.localsForFunctions(indices, wasmModule))
    }

    @McpTool("get_instructions_for_functions")
    @McpDescription("""
    Decodes and returns the full instruction stream for each function specified by funcidx.
    Accepts a list of function indices and returns a JSON array of InstructionEntry objects,
    each containing the fully decoded Wasm instructions for that function.

    Supports:
      • imported functions — always return empty instruction lists (no body exists)
      • internal functions — instructions decoded from the Code Section
      • all block structures (block, loop, if, else, end)
      • control flow, arithmetic, memory ops, locals/global ops, call/call_indirect, etc.

    Each InstructionEntry contains:
      • funcidx: unified function index (imports first, then internal)
      • instructions: full list of decoded instructions in canonical text form
            e.g. ["local.get 0", "i32.const 1", "i32.add", "return"]

    This tool is useful for:
      • decompilation and reverse engineering
      • control-flow reconstruction
      • identifying branching, loops, traps, and call sites
      • supporting LLM-assisted code understanding
      • correlating instructions with memory offsets and globals
      • mapping indirect calls to table entries

    ---------------------------------------
    EXAMPLES OF RETURNED INSTRUCTION ENTRIES
    ---------------------------------------

    Example 1 — Imported function (no instructions):

      (import "env" "abort" (func (param i32 i32)))

    JSON:
      {
        "funcidx": 0,
        "instructions": [],
      }

    Example 2 — Simple function with locals and arithmetic:

      (func (export "add1") (param i32) (result i32)
        (local i32)
        local.get 0
        i32.const 1
        i32.add)

    JSON:
      {
        "funcidx": 1,
        "instructions": [
          "local.get 0",
          "i32.const 1",
          "i32.add",
          "return"
        ],
      }

    Example 3 — Function with control flow:

      (func (param i32) (result i32)
        local.get 0
        i32.const 0
        i32.eq
        if (result i32)
          i32.const 1
        else
          local.get 0
        end)

    JSON:
      {
        "funcidx": 2,
        "instructions": [
          "local.get 0",
          "i32.const 0",
          "i32.eq",
          "if (result i32)",
          "i32.const 1",
          "else",
          "local.get 0",
          "end",
          "return"
        ],
      }

    ---------------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (for reference)
    ---------------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
      • Disassemble with instruction listing:
          wasm-objdump -d module.wasm

      • Convert to WAT (shows instructions in text syntax):
          wasm2wat module.wasm

      • Annotated text with offsets:
          wasm2wat --enable-annotations module.wasm

      • Raw dump of code section:
          wasm-objdump -x module.wasm

    Using wasm-tools (Bytecode Alliance):
      • Structured text format with instructions:
          wasm-tools print module.wasm

      • Dump raw sections including encoded instructions:
          wasm-tools dump module.wasm

      • Encode/decode binary <-> wat:
          wasm-tools parse  foo.wat  -o foo.wasm
          wasm-tools print  foo.wasm

    Command-line tools require manual inspection, and do not provide:
      • unified funcidx ordering
      • export linkage
      • simple arrays of decoded instructions
      • consistent formatting across all instructions
      • metadata like codeOffset/codeSize in JSON

    This MCP tool provides an IDE-friendly, analysis-ready JSON representation
    tailored for LLM reasoning and structural understanding of function bodies.
""")
    fun getCodesForFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("List of function indices (funcidx) to retrieve details for") indices: List<Int>
    ): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.codeForFunctions(indices, wasmModule))
    }

    @McpTool("list_element_segments")
    @McpDescription("""
    Extracts all element segments from a WebAssembly module and returns them as a JSON
    array of ElementSegmentEntry objects.

    Element segments are used to initialize tables with function references, or to store
    reference expressions for later use via bulk-memory instructions. This tool supports
    all WebAssembly element segment kinds (0–7), including both legacy function-index
    initializers and modern expression-based initializers.

    -----------------------------------
    SUPPORTED ELEMENT SEGMENT KINDS
    -----------------------------------

      • kind 0 – active segment for table 0 with offset expression (legacy raw funcidx form)
      • kind 1 – passive segment (no table index, no offset expression)
      • kind 2 – active segment for table X with offset expression
      • kind 3 – declarative segment (never applied)
      • kind 4 – passive, element expressions (always funcref)
      • kind 5 – active for table 0, element expressions
      • kind 6 – active for table X, element expressions
      • kind 7 – declarative, element expressions

    The returned ElementSegmentEntry contains:
      • elemidx: sequential index of the element segment
      • kind: numeric segment kind (0–7)
      • mode: "active", "passive", or "declarative"
      • tableIndex: target table index for active segments; null for passive/declarative
      • offsetExpr: stringified offset expression for active segments; null otherwise
      • elementType: reference type of elements (always "funcref" for kinds 4–7)
      • elements: normalized list of element initializers:
            Examples:
              "func 3"
              "ref.func 8"
              "ref.null funcref"
              "expr: <expression>"
      • rawAvailable: whether full payload bytes can be fetched via a raw-data MCP tool
      • exportedTables: list of export names of tables referenced by the segment (if any)

    This tool resolves all table indices, element kinds, offset expressions, and function
    references into a consistent LLM-friendly structure suitable for analyzing indirect
    calls, vtables, dispatch logic, GC metadata, and table-driven code generation.

    -------------------------------------------
    EXAMPLES OF RETURNED ELEMENT SEGMENT ENTRIES
    -------------------------------------------

    Example 1 — Active (kind 0), table 0, raw function indices:
      (table 5 funcref)
      (elem (i32.const 0) 1 2 3)

    JSON:
      {
        "elemidx": 0,
        "kind": 0,
        "mode": "active",
        "tableIndex": 0,
        "offsetExpr": "i32.const 0",
        "elementType": "funcref",
        "elements": ["func 1", "func 2", "func 3"]
      }

    Example 2 — Passive (kind 1), raw function indices:
      (elem passive func 5 func 6)

    JSON:
      {
        "elemidx": 1,
        "kind": 1,
        "mode": "passive",
        "tableIndex": null,
        "offsetExpr": null,
        "elementType": "funcref",
        "elements": ["func 5", "func 6"]
      }

    Example 3 — Active (kind 2), table 1, raw function indices:
      (table 2 funcref)
      (table 3 funcref)
      (elem (table 1) (i32.const 32) func 8 func 9)

    JSON:
      {
        "elemidx": 2,
        "kind": 2,
        "mode": "active",
        "tableIndex": 1,
        "offsetExpr": "i32.const 32",
        "elementType": "funcref",
        "elements": ["func 8", "func 9"]
      }

    Example 4 — Declarative (kind 3):
      (elem declare func 0 func 1)

    JSON:
      {
        "elemidx": 3,
        "kind": 3,
        "mode": "declarative",
        "tableIndex": null,
        "offsetExpr": null,
        "elementType": "funcref",
        "elements": ["func 0", "func 1"]
      }

    Example 5 — Passive with expressions (kind 4):
      (elem passive (ref.func 3) (ref.null funcref))

    JSON:
      {
        "elemidx": 4,
        "kind": 4,
        "mode": "passive",
        "tableIndex": null,
        "offsetExpr": null,
        "elementType": "funcref",
        "elements": ["ref.func 3", "ref.null funcref"]
      }

    Example 6 — Active with expressions, table 0 (kind 5):
      (elem (i32.const 8) (ref.func 2) (ref.null funcref))

    JSON:
      {
        "elemidx": 5,
        "kind": 5,
        "mode": "active",
        "tableIndex": 0,
        "offsetExpr": "i32.const 8",
        "elementType": "funcref",
        "elements": ["ref.func 2", "ref.null funcref"]
      }

    Example 7 — Active with expressions, table 1 (kind 6):
      (elem (table 1) (i32.const 12) (ref.func 7))

    JSON:
      {
        "elemidx": 6,
        "kind": 6,
        "mode": "active",
        "tableIndex": 1,
        "offsetExpr": "i32.const 12",
        "elementType": "funcref",
        "elements": ["ref.func 7"]
      }

    Example 8 — Declarative with expressions (kind 7):
      (elem declare (ref.func 4) (ref.null funcref))

    JSON:
      {
        "elemidx": 7,
        "kind": 7,
        "mode": "declarative",
        "tableIndex": null,
        "offsetExpr": null,
        "elementType": "funcref",
        "elements": ["ref.func 4", "ref.null funcref"]
      }

    ----------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (reference)
    ----------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
      • Convert to text (shows all element segments):
          wasm2wat module.wasm

      • Show raw section contents, including element section:
          wasm-objdump -x module.wasm
          wasm-objdump -s module.wasm

    Using wasm-tools (Bytecode Alliance):
      • Structured text form:
          wasm-tools print module.wasm

      • Dump raw binary representation of segments:
          wasm-tools dump module.wasm

      • Convert binary <-> wat:
          wasm-tools parse foo.wat -o foo.wasm
          wasm-tools print foo.wasm

    These tools require manual reading of kind, table index, expressions, and raw function
    references. The MCP tool provides a high-level, IDE-ready JSON representation with
    normalized element initializers, decoded offset expressions, and consistent formatting
    tailored for LLM-driven analysis.
""")
    fun listElementSegments(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
    ):String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listElementSegments(wasmModule))

    }
}


private fun VirtualFile.contentsToCommonByteBuffer() = contentsToByteArray().toCommonByteBuffer()