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
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActive
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActiveWithMemoryIndex
import org.jetbrains.hexana.wasm.sections.WasmDataRecordPassive
import org.jetbrains.hexana.wasm.sections.WasmSectionTypesRecord
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmNumType
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType


internal fun CommonByteBuffer.listDataSections(wasmModule: WasmModule?): List<DataEntry> {

    val data = with(wasmModule!!.dataSection!!) {
        scan()
    }
    return data.mapIndexedNotNull { index, record ->
        when(record) {
            is WasmDataRecordActive -> DataEntry(0,index, record.offset, record.data, expression = record.expression.wat())
            is WasmDataRecordPassive -> DataEntry(1,index, record.offset, record.data)
            is WasmDataRecordActiveWithMemoryIndex -> DataEntry(2, index, record.offset, record.data, record.memoryIndex, record.expression.wat())
            else -> null
        }
    }
}
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
        (types.getOrNull(typeIndex) as? WasmSectionFunctionTypeRecord)?.toSignature()?.let { signature ->
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

internal fun CommonByteBuffer.types(wasmModule: WasmModule): List<WasmTypeRecord> =
    with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        parseTypes()
    }

val objectMapper = jacksonObjectMapper().apply {
    registerModule(KotlinModule.Builder().build())
}

fun WasmTypeIndexExternalType.toSignature(types: List<WasmTypeRecord>): ParameterSignature? {
    val typeIndex = index.toInt()
    val rawSignature = types.getOrNull(typeIndex)
    return (rawSignature as? WasmSectionFunctionTypeRecord)?.toSignature()
}

fun WasmSectionFunctionTypeRecord.toSignature(): ParameterSignature {
    return ParameterSignature(
        this.params.map { it.toSignature() },
        this.retTypes.map { it.toSignature() })
}

fun WasmValueType<*>.toSignature(): String {
    return when (this) {
        is WasmF32Type -> "f32"
        is WasmF64Type -> "f64"
        is WasmI32Type -> "i32"
        is WasmI64Type -> "i64"
        is WasmPactTypeI8 -> "i8"
        is WasmPactTypeI16 -> "i16"
        else -> "unknown"
    }
}


data class DataEntry(val kind:Int, val index: Int, val offset: Long, val data: ByteArray, val memoryIndex: UInt? = null, val expression: String? = null) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataEntry

        if (kind != other.kind) return false
        if (index != other.index) return false
        if (offset != other.offset) return false
        if (!data.contentEquals(other.data)) return false
        if (memoryIndex != other.memoryIndex) return false
        if (expression != other.expression) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kind
        result = 31 * result + index
        result = 31 * result + offset.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (memoryIndex?.hashCode() ?: 0)
        result = 31 * result + (expression?.hashCode() ?: 0)
        return result
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
