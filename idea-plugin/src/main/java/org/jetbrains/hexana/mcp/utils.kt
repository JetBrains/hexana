package org.jetbrains.hexana.mcp

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.TypeId
import org.jetbrains.hexana.wasm.sections.WasmSectionTypesRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

internal fun CommonByteBuffer.listFunctions(wasmModule: WasmModule?): List<FunctionSignature> {
    wasmModule ?: return emptyList()
    val types = types(wasmModule)
    val records = with(wasmModule.functionSection) {
        this ?: return@with emptyList()
        this@listFunctions.parseFunctions()
    }
    val codes = with(wasmModule.codeSection) {
        this ?: return@with emptyList()
        this@listFunctions.scan()
    }
    val signatures = records.mapIndexedNotNull { index, typeIndex  ->
        types.getOrNull(typeIndex)?.toSignature()?.let { signature ->
            FunctionSignature(
                index,
                codes.getOrNull(index)?.offset,
                signature,
                // TODO: types are encoded more complexly
                codes.getOrNull(index)?.locals?.map { Local(it.local, it.type.toInt()) },
                codes.getOrNull(index)?.opCodes?.wat())
        }
    }
    return signatures
}

internal fun CommonByteBuffer.importSymbols(
    wasmModule: WasmModule?
): List<ImportSignature> {
    val records = with(wasmModule!!.importSection) {
        this ?: return@with emptyList()
        this@importSymbols.getImportRecords()
    }

    val types = types(wasmModule)
    val signatures = records.map {
        val signature = when (it.type) {
            is WasmTypeIndexExternalType -> (it.type as WasmTypeIndexExternalType).toSignature(types)
            else -> null
        }
        ImportSignature(it.prefix, it.name, signature)
    }
    return signatures
}

internal fun CommonByteBuffer.types(wasmModule: WasmModule): List<WasmSectionTypesRecord> =
    with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        parseTypes()
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

data class FunctionSignature(
    @JsonProperty("index")
    val index: Int,
    @JsonProperty("offset")
    val offset: Long?,
    @JsonProperty("signature")
    val signature: SerializationSignature?,
    @JsonProperty("locals")
    val locals: List<Local>?,
    @JsonProperty("code")
    val code: String? = null
)

data class Local(val local: Int, val type: Int)

data class ParameterSignature(
    @JsonProperty("params")
    val params: List<String>,
    @JsonProperty("return")
    val ret: List<String>
) : SerializationSignature

interface SerializationSignature

internal fun resolveWasmVirtualFile(path: String): VirtualFile? {
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
                return picked
            }
        }
    }

    return null
}
