package org.jetbrains.hexana.mcp

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.WasmFsUtil
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.JvmByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.sections.TypeId
import org.jetbrains.hexana.wasm.sections.WasmSectionTypesRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel


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
}

fun main() {
    val file = File("/Users/user/ws/wasm-playground/simple/simple.wasm")
    FileInputStream(file).use {
        val buffer = JvmByteBuffer(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()))
        val wasmModule = WasmParser().parse(buffer)
        val signatures = buffer.importSymbols(wasmModule)
        objectMapper.writeValueAsString(signatures).also(::println)
    }
}

private fun CommonByteBuffer.importSymbols(
    wasmModule: WasmModule?
): List<ImportSignature> {
    val records = with(wasmModule!!.importSection) {
        this ?: return@with emptyList()
        this@importSymbols.getImportRecords()
    }

    val types = with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        this@importSymbols.parseTypes()
    }
    val signatures = records.map {
        val signature = when (it.type) {
            is WasmTypeIndexExternalType -> (it.type as WasmTypeIndexExternalType).toSignature(types)

            else -> null
        }
        ImportSignature(it.prefix, it.name, signature)
    }
    return signatures
}

val objectMapper = jacksonObjectMapper().apply {
    registerModule(KotlinModule.Builder().build())
}

fun WasmTypeIndexExternalType.toSignature(types: List<WasmSectionTypesRecord>): ParameterSignature? {
    val typeIndex = index.toInt()
    val rawSignature = types.getOrNull(typeIndex)
    return rawSignature?.toSignature()
}

fun WasmSectionTypesRecord.toSignature(): ParameterSignature {
    return ParameterSignature(
        this.params.map { it.toSignature() },
        this.retTypes.map { it.toSignature() })
}

fun TypeId.toSignature(): String {
    when (this) {
        is TypeId.TYPE_F32 -> return "f32"
        is TypeId.TYPE_F64 -> return "f64"
        is TypeId.TYPE_I32 -> return "i32"
        is TypeId.TYPE_I64 -> return "i64"
    }
}

data class ImportSignature(
    @JsonProperty("module")
    val module: String,
    @JsonProperty("name")
    val name: String,
    @JsonProperty("signature")
    val signature: SerializationSignature?
)

data class ParameterSignature(
    @JsonProperty("params")
    val params: List<String>,
    @JsonProperty("return")
    val ret: List<String>
) : SerializationSignature

interface SerializationSignature

private fun resolveWasmVirtualFile(path: String): VirtualFile? {
    val lfs = LocalFileSystem.getInstance()
    // 1) Absolute path or VFS path
    lfs.refreshAndFindFileByPath(path)?.let { return it }

    val hasSeparator = path.contains('/') || path.contains('\\')
    val projects = ProjectManager.getInstance().openProjects

    // 2) Project-relative resolution against basePath
    for (project in projects) {
        val base = project.basePath
        if (!base.isNullOrBlank()) {
            lfs.refreshAndFindFileByPath("$base/$path")?.let { return it }
        }
    }

    // 3) Filename search within project if a bare filename was provided
    if (!hasSeparator) {
        for (project in projects) {
            val scope = GlobalSearchScope.projectScope(project)
            val matches = FilenameIndex.getVirtualFilesByName(project, path, scope)
            if (matches.size == 1) return matches.first()
            if (matches.isNotEmpty()) {
                // Pick the first but log ambiguity to idea.log
                val picked = matches.first()
                logger.warn("Hexana: multiple files named '$path' found; using '${picked.path}'.")
                return picked
            }
        }
    }

    return null
}
