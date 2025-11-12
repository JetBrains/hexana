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
    suspend fun listFunctions(path: String): String {
        val file = resolveWasmVirtualFile(path) ?: return """{"error": "File not found"}"""
        if (file.fileType != WasmFileType.INSTANCE) {
            return """{"error": "Not a .wasm file"}"""
        }
        val buffer = JvmByteBuffer(ByteBuffer.wrap(file.contentsToByteArray()))
        val wasmModule = WasmParser().parse(buffer)
        return objectMapper.writeValueAsString(buffer.listFunctions(wasmModule))
    }
}
