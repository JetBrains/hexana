package org.jetbrains.hexana.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
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
import org.jetbrains.hexana.mcp.query.readDataSegments
import org.jetbrains.hexana.mcp.query.readDataSections
import org.jetbrains.hexana.mcp.query.readElementSegments
import org.jetbrains.hexana.mcp.query.readExportedFunctions
import org.jetbrains.hexana.mcp.query.readExports
import org.jetbrains.hexana.mcp.query.readFunctionsByIndices
import org.jetbrains.hexana.mcp.query.readInstructionsForFunctions
import org.jetbrains.hexana.mcp.query.readLocalsForFunctions
import org.jetbrains.hexana.mcp.query.readGlobalsByIndices
import org.jetbrains.hexana.mcp.query.readGlobals
import org.jetbrains.hexana.mcp.query.readImportFunctions
import org.jetbrains.hexana.mcp.query.readMemoryByIndices
import org.jetbrains.hexana.mcp.query.readMemory
import org.jetbrains.hexana.mcp.query.readModuleSummary
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
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
    ): ListImportsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val module = buffer.requireParsedWasmModule()
        return buffer.readImportFunctions(module)
    }

    @McpTool("list_functions")
    @McpDescription("""
    Returns a paginated list of function metadata from a WebAssembly module.
    This endpoint is designed for large modules where a full list of all functions
    would be too large for LLM processing. The tool supports offset/limit pagination
    and multiple detail modes to control the size of the response.

    ---------------------------------------
    INPUT PARAMETERS
    ---------------------------------------
      • offset (optional, default = 0)
          Starting index (funcidx) in the unified function index space.
      
      • limit (optional, default = 200)
          Maximum number of function entries to return.
          Recommended range: 50–500 depending on LLM capacity.
      
    ---------------------------------------
    RETURN FORMAT
    ---------------------------------------

    {
      "functions": [ ... entries ... ],
      "offset": <number>,
      "limit": <number>,
      "returned": <number>,
      "total": <total number of functions>,
      "nextOffset": <number or null>,
      "mode": "summary" | "detailed" | "full",
      "truncated": boolean
    }

    ---------------------------------------
    FIELDS IN FUNCTION METADATA
    ---------------------------------------

    All modes include:
      • funcidx — unified function index
      • definedIn — "import" or "function_section"
      • signature — { params: [...], return: [...] }
      • exportedAs — names under which the function is exported

    Additional fields in "detailed":
      • localsCount — number of local variables
      • codeSize — size of function body in bytes
      • instructionCount — decoded instruction count (if available)
      • preview — first 1–3 instructions (LLM-friendly)
    
    Additional fields in "full":
      • locals — flat list of local types
      • may include additional metadata provided by Hexana
      • response may be truncated for safety

    ---------------------------------------
    EXAMPLE REQUEST
    ---------------------------------------

    {
      "offset": 0,
      "limit": 100,
      "mode": "summary"
    }

    ---------------------------------------
    EXAMPLE RESPONSE
    ---------------------------------------

    {
      "functions": [
        {
          "funcidx": 0,
          "definedIn": "import",
          "signature": { "params": ["i32","i32"], "return": [] },
          "exportedAs": []
        },
        {
          "funcidx": 1,
          "definedIn": "function_section",
          "signature": { "params": ["i32"], "return": ["i32"] },
          "exportedAs": ["increment"]
        }
      ],
      "offset": 0,
      "limit": 100,
      "returned": 2,
      "total": 42,
      "nextOffset": 100,
      "mode": "summary",
      "truncated": false
    }

    ---------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS
    ---------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
      • List function imports:
          wasm-objdump -x module.wasm | grep Import
      • Count total functions:
          wasm2wat module.wasm | grep "(func" -n
      • Show signature list:
          wasm2wat module.wasm

    Using wasm-tools (Bytecode Alliance):
      • Print module structure:
          wasm-tools print module.wasm
      • Dump sections:
          wasm-tools dump module.wasm
      • Count functions:
          wasm-tools print module.wasm | grep "(func" -n

    These approaches require manually correlating imports, definitions, signatures,
    and export indices. The paginated list_functions_paginated MCP tool provides a 
    safe, LLM-optimized, chunked directory of all functions with adjustable detail level.
""")
    @Suppress("unused")
    suspend fun listFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("Offset into the unified function index space") offset: Int = 0,
        @McpDescription("Maximum number of entries to return") limit: Int = 200,

    ): String {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return objectMapper.writeValueAsString(buffer.listFunctions(wasmModule, offset, limit))
    }

    @McpTool("list_data")
    @McpDescription("""
    Extracts all raw data segments from a WebAssembly module and returns them as a JSON array 
    of DataEntry objects. This tool exposes the Data Section in its most direct binary form and 
    is useful when exact byte payloads are needed for debugging, binary inspection, resource 
    extraction, or round-tripping tests.

    ⚠ IMPORTANT PERFORMANCE NOTE
    -----------------------------
    Raw data segments can be extremely large in some modules (Skiko, Emscripten, wasm-bindgen, 
    WASI binaries). Dumping every segment at once may produce extremely large JSON responses.  
    It is recommended to:

      1. Run `summarize_module` first to see how many data segments exist and how large they are.
      2. Use range slicing (`from` + `to`) to fetch only a required slice of the data section.
      3. Prefer `list_data_segments` if you need metadata (kinds, offset expressions, previews)
         rather than the raw byte payload.

    • entriesRange
            Filter by entries index range:
              { "from": N, "to": M }
            Only segments with dataidx in this range will be returned.

      If both are omitted, all segments are returned (not recommended for large modules).

    Range Metadata in Response:
      • from          – starting dataidx of this slice
      • to            – ending dataidx of this slice
      • returned      – number of segments included
      • totalSegments – total number of data segments in the module
      • truncated     – true if this slice does not include all segments

    --------------------------------------------------------------------
    DESCRIPTION OF RETURNED ENTRIES (DataEntry)
    --------------------------------------------------------------------

    Supports all WebAssembly data segment kinds:
      • kind 0 – active segment for memory 0 with an offset expression
      • kind 1 – passive segment (no memory index, no offset)
      • kind 2 – active segment for memory X with an offset expression

    Each DataEntry contains:
      • index: zero-based segment index (dataidx)
      • kind: the numeric segment kind (0, 1, or 2)
      • offset: evaluated constant offset for active segments; 0 for passive
      • data: base64-encoded raw bytes of the segment’s payload
      • memoryIndex: memory index for active segments (0 or X), null for passive
      • expression: textual representation of the offset expression, null for passive

    This follows the WebAssembly binary grammar:
      data ::=
          0:u32 e:expr b*:list(byte)             → active segment on memory 0
        | 1:u32 b*:list(byte)                    → passive segment
        | 2:u32 x:memidx e:expr b*:list(byte)    → active segment on memory x

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

    -------------------------------
    EXAMPLES OF RANGE-BASED USAGE
    -------------------------------

    Request:
      { "from": 0, "to": 2 }

    Response (simplified):
      {
        "entries": [ ... three DataEntry objects ... ],
        "from": 0,
        "to": 2,
        "returned": 3,
        "totalSegments": 57,
        "truncated": true
      }

    Request next slice:
      { "from": 3, "to": 5 }

    ----------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (reference)
    ----------------------------------------------------

    Using WABT:
      • Convert to text (shows data segments):
            wasm2wat module.wasm
      • Annotated text with extra offsets:
            wasm2wat --enable-annotations module.wasm
      • Raw dump of data payloads:
            wasm-objdump -x module.wasm
            wasm-objdump -s module.wasm    (shows data section only)

    Using wasm-tools:
      • IR view:
            wasm-tools print module.wasm
      • Raw binary dump:
            wasm-tools dump module.wasm
      • Convert between WAT and binary:
            wasm-tools parse   foo.wat -o foo.wasm
            wasm-tools print   foo.wasm

    This MCP tool provides a high-level, IDE-friendly, index-sliced JSON representation of data 
    segments—combining kind, memory index, offset expressions, and base64-encoded raw payload bytes—
    suitable for analysis, automated testing, and LLM reasoning.
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
        @McpDescription("""
            Filter by segment index range:
              { "from": N, "to": M }
            Only segments with dataidx in this range will be returned.
        """)
        entriesRange: Map<String, Int>,
    ): ListDataSectionsResult {
        val file = requireWasmFile(path)
        val range = requireClosedSegmentRange(entriesRange)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readDataSections(wasmModule, range)
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
    ): ListExportsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readExports(wasmModule)
    }

    @McpTool("list_exported_functions")
    @McpDescription( """
    Returns all exported functions from a WebAssembly module as a JSON array of 
    WasmExportFunctionRecord objects. Each entry includes the export name and the 
    resolved function signature (either an imported signature or a locally defined 
    function signature with its type information).
    
    This tool makes it easy to introspect the module’s public API surface and to 
    understand what callable functions are exposed to the embedding environment.
    
    Because some WASM binaries (Skiko, Emscripten, wasm-bindgen) may expose 
    hundreds or thousands of functions, this tool supports index-space pagination.
    
    ---
    
    ## Pagination (Range-Based Slicing)
    
    Exports are indexed sequentially:
    
      exportidx 0 → first exported function  
      exportidx 1 → second exported function  
      exportidx 2 → third exported function  
      ...
    
    Use the following optional parameters to retrieve only a slice:
    
      • from (optional, default: 0)
            Starting export index (inclusive).
    
      • to (optional, default: last export)
            Ending export index (inclusive).
    
    These indices refer *strictly* to the export list order and are not related to
    funcidx numbering inside the function index space.
    
    Examples:
        { "from": 0, "to": 9 }      → first 10 exported functions
        { "from": 100, "to": 199 }  → exports 100–199
        { "from": 20 }              → exports 20–end
        { "to": 50 }                → exports 0–50
    
    The response may include pagination metadata:
        • from
        • to
        • returned
        • totalExports
        • truncated
    
    ---
    
    ## Returned Structure (WasmExportFunctionRecord)
    
    Each record includes:
    
      • name  
            The exported function name.
    
      • signature  
            Either:
              ImportSignature:
                {
                  "module": string,
                  "name": string,
                  "signature": {
                      "params": [string],
                      "return": [string]
                  }
                }
    
              or
    
              FunctionSignature:
                {
                  "index": number,
                  "offset": number | null,
                  "signature": {
                      "params": [string],
                      "return": [string]
                  },
                  "locals": [Local] | null,
                  "code": string | null
                }
    
    This unified representation lets LLMs handle both imported and local functions
    without needing to inspect the Import Section manually.
    
    ---
    
    ## Examples
    
    ### Example 1 — Exporting a local function
    
    WAT:
        (func ${'$'}double (param i32) (result i32)
            local.get 0
            i32.const 2
            i32.mul)
        (export "double" (func ${'$'}double))
    
    JSON:
    {
      "name": "double",
      "signature": {
        "index": 3,
        "offset": 128,
        "signature": { "params": ["i32"], "return": ["i32"] },
        "locals": [],
        "code": "local.get 0; i32.const 2; i32.mul"
      }
    }
    
    ---
    
    ### Example 2 — Exporting an imported function
    
    WAT:
        (import "env" "log" (func (param i32)))
        (export "log" (func 0))
    
    JSON:
    {
      "name": "log",
      "signature": {
        "module": "env",
        "name": "log",
        "signature": { "params": ["i32"], "return": [] }
      }
    }
    
    ---
    
    ## Example Pagination Usage
    
    Request:
        { "from": 0, "to": 2 }
    
    Response (simplified):
    {
      "entries": [
        { "name": "log", ... },
        { "name": "double", ... },
        { "name": "add", ... }
      ],
      "from": 0,
      "to": 2,
      "returned": 3,
      "totalExports": 417,
      "truncated": true
    }
    
    ---
    
    ## Equivalent CLI Tools (Reference)
    
    Using WABT:
        wasm-objdump -x module.wasm     → shows Export Section
        wasm2wat module.wasm            → shows (export ...) declarations
    
    Using wasm-tools:
        wasm-tools print module.wasm    → structured export list
        wasm-tools dump module.wasm     → raw section dumps
    
    This MCP tool provides a normalized, LLM-friendly, paginated JSON view over the 
    module’s exported functions, resolving both imported and locally defined function 
    signatures in a unified representation.
    """
    )
    suspend fun listExportedFunctions(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("""
            Filter by segment index range:
              { "from": N, "to": M }
            Only segments with dataidx in this range will be returned.
        """)
        entriesRange: Map<String, Int>
    ): ListExportedFunctionsResult {
        val file = requireWasmFile(path)
        val range = requireClosedSegmentRange(entriesRange)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readExportedFunctions(wasmModule, range)
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
    ): FunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readFunctionsByIndices(wasmModule, indices)
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
    ): ListGlobalsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readGlobals(wasmModule)
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
    ): ListMemoryResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readMemory(wasmModule)
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
    ): ListGlobalsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readGlobalsByIndices(wasmModule, indices)
    }

    @McpTool("list_data_segments")
    @McpDescription("""
    Extracts data segments from a WebAssembly module and returns them in a JSON object
    containing DataSegmentEntry objects and related metadata. Supports pagination, selective inclusion of related
    metadata, and filtering to keep responses lightweight and LLM-friendly.
    
    ------------------------------------------------ 
    TIP FOR LLM CLIENTS:
    ------------------------------------------------
    When analyzing an unfamiliar module, always start with `summarize_module`.
    If `summary.totalSegments` is large (e.g., >200), do NOT call list_data_segments 
    without filters. Instead, narrow the request:
      • segmentRange = { "from": N, "to": M }
      • includeExports = false
      • includeGlobals = false
      • minByteLength = 32 (or higher)
    This pattern avoids generating large JSON blobs that can exceed the client’s 
    context window or UI rendering limits.

    ------------------------------------------------
    INPUT PARAMETERS 
    ------------------------------------------------
      • segmentRange
            Filter by segment index range:
              { "from": N, "to": M }
            Only segments with dataidx in this range will be returned.

      • includeExports (default: false)
            Whether to include the list of exported symbols referencing memory ranges.
            Export lists can be extremely large (e.g., Skiko/WebAssembly runtimes),
            so they are disabled by default.

      • includeGlobals (default: true)
            Whether to include the global variables used in offset expressions.
            Useful for understanding dynamic offsets (global.get), but optional.


      • minByteLength (optional)
            Return only segments whose raw byte length is >= this value.
            Useful for locating large binary blobs or string pools while skipping
            tiny alignment/padding segments.

    ------------------------------------------------
    DATA SEGMENT FORMAT (DataSegmentEntry)
    ------------------------------------------------

    Each entry contains:
      • dataidx: sequential index of the segment
      • kind: numeric segment kind (0, 1, or 2)
      • mode: "active" or "passive"
      • memoryIndex: target memory index for active segments; null for passive
      • offsetExpr: stringified offset expression for active segments; null otherwise
      • byteLength: length of the payload in bytes
      • hexPreview: first N bytes in hex (short preview only)
      • utf8Preview: short printable UTF-8 preview if valid; null otherwise
      • globals: included only if includeGlobals = true
      • exportedSymbols: included only if includeExports = true

    ------------------------------------------------
    EXAMPLES OF RETURN VALUES
    ------------------------------------------------
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
        "limits": { "min": 2, "max": 4 }
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
    "totalBytes": 5,
    "truncated": false
  },
  "memory": [
    {
      "memidx": 0,
      "definedIn": "import",
      "module": "env",
      "name": "memory",
      "limits": { "min": 2, "max": 4 }
    }
  ],
  "globals": [],
  "exported_symbols": []
}
    ```

    Example with pagination:
      Request:
        { "segmentRange": { "from" : 2, "to" : 4 } }

      Response:
        {
          "segments": [ ... 2 entries ... ],
          "summary": {
            "truncated": true,
            "totalSegments": 14,
          }
        }

    ------------------------------------------------
    ALTERNATIVE VIA WASM COMMAND-LINE TOOLS (REFERENCE)
    ------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
        wasm2wat module.wasm
        wasm-objdump -s module.wasm   ; shows data segments
        wasm-objdump -x module.wasm   ; section sizes

    Using wasm-tools (Bytecode Alliance):
        wasm-tools print module.wasm
        wasm-tools dump module.wasm
        wasm-tools parse foo.wat -o foo.wasm

    These tools expose raw data segments but require manual interpretation of:
      • segment kinds (0/1/2),
      • offset expressions,
      • table/memory resolution,
      • filtering and pagination.

    The list_data_segments MCP tool provides a high-level, paginated, LLM-friendly
    JSON representation tailored for safe analysis, with strict controls on output
    size and optional metadata sections.
""")
    fun listDataSegments(
        @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION) path: String,
        @McpDescription("""
            Filter by segment index range:
              { "from": N, "to": M }
            Only segments with dataidx in this range will be returned.
        """)
        segmentRange: Map<String, Int>,
        @McpDescription("""
            Whether to include the list of exported symbols referencing memory ranges.
            Export lists can be extremely large (e.g., Skiko/WebAssembly runtimes),
            so they are disabled by default.
        """)
        includeExports: Boolean = false,
        @McpDescription("""
            Whether to include the global variables used in offset expressions.
            Useful for understanding dynamic offsets (global.get), but optional.
        """)
        includeGlobals: Boolean = true,
        @McpDescription("""
            Return only segments whose raw byte length is >= this value.
            Useful for locating large binary blobs or string pools while skipping
            tiny alignment/padding segments.
        """)
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
    ): ListMemoryResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readMemoryByIndices(wasmModule, indices)
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
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
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
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return objectMapper.writeValueAsString(buffer.listTypesByIndices(wasmModule, indices))
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
    ): GetLocalsForFunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readLocalsForFunctions(wasmModule, indices)
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
    ): GetInstructionsForFunctionsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readInstructionsForFunctions(wasmModule, indices)
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
    ): ListElementSegmentsResult {
        val file = requireWasmFile(path)
        val buffer = file.contentsToCommonByteBuffer()
        val wasmModule = buffer.requireParsedWasmModule()
        return buffer.readElementSegments(wasmModule)

    }

    @McpTool("summarize_module")
    @McpDescription("""
    Returns a compact numeric summary of the WebAssembly module.  
    The output is a single JSON object containing high-level counts of the
    module’s major components (functions, imports, exports, memories, tables, types, etc.).
    
    This tool is designed to give LLMs and developers a fast overview of the
    module’s structure without inspecting individual sections.

    ------------------------------------------------
    FIELDS INCLUDED IN THE SUMMARY (all numeric)
    ------------------------------------------------

      • functions — total number of functions (imported + defined)
      • exports — number of exported entities
      • imports — object containing counts of imported:
            - functions
            - memories
            - globals
            - tables
      • memories — number of memory objects defined or imported
      • tables — number of table objects defined or imported
      • types — number of type signatures in the Type Section
      • module_size — size of the entire module in bytes

    The summary reflects the unified module structure after merging imports,
    definitions, and section representations.

    ----------------------
    EXAMPLE RETURN VALUE
    ----------------------

    {
      "functions": 42,
      "exports": 6,
      "imports": {
        "functions": 3,
        "memories": 1,
        "globals": 2,
        "tables": 1
      },
      "memories": 1,
      "tables": 1,
      "types": 17,
      "module_size": 123456
    }

    ----------------------------------------------------------
    ALTERNATIVE VIA COMMAND-LINE TOOLS (manual reconstruction)
    ----------------------------------------------------------

    Using WABT (https://github.com/WebAssembly/wabt):
      • Count functions manually:
          wasm-objdump -x module.wasm   (shows imports, tables, memories, globals)
      • Convert to WAT and count type entries:
          wasm2wat module.wasm

    Using wasm-tools (Bytecode Alliance):
      • Count imports/exports/functions:
          wasm-tools dump module.wasm
      • Count types:
          wasm-tools print module.wasm | grep "(type" -n
          
    Unix utils:
        du -b module.wasm
        ls -al module.wasm | grep module.wasm

    These steps require manually scanning several sections.
    The summarize_module MCP tool provides a single JSON object
    with precomputed counts, optimized for LLM reasoning and quick diagnostics.
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

private fun requireClosedSegmentRange(entriesRange: Map<String, Int>): IntRange {
    val from = entriesRange["from"] ?: mcpFail("Invalid segmentRange no `from`")
    val to = entriesRange["to"] ?: mcpFail("Invalid segmentRange no `to`")
    if (to < from) {
        mcpFail("Invalid segmentRange `to` must be >= `from`")
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
