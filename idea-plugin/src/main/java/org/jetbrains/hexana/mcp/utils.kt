package org.jetbrains.hexana.mcp

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmAnyRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActive
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActiveWithMemoryIndex
import org.jetbrains.hexana.wasm.sections.WasmDataRecordPassive
import org.jetbrains.hexana.wasm.sections.WasmEqRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExnRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalRecord
import org.jetbrains.hexana.wasm.sections.WasmI31RefHeapType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmIndexHeapType
import org.jetbrains.hexana.wasm.sections.WasmLimits
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmNoExnHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoneHeapType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNumType
import org.jetbrains.hexana.wasm.sections.WasmOpenI32Limits
import org.jetbrains.hexana.wasm.sections.WasmOpenI64Limits
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmReferenceType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType


internal fun CommonByteBuffer.listDataSections(wasmModule: WasmModule?): List<DataEntry> {

    val data = with(wasmModule!!.dataSection!!) {
        scan()
    }
    return data.mapIndexedNotNull { index, record ->
        when (record) {
            is WasmDataRecordActive -> DataEntry(
                0,
                index,
                record.offset,
                record.data,
                expression = record.expression.wat()
            )

            is WasmDataRecordPassive -> DataEntry(1, index, record.offset, record.data)
            is WasmDataRecordActiveWithMemoryIndex -> DataEntry(
                2,
                index,
                record.offset,
                record.data,
                record.memoryIndex,
                record.expression.wat()
            )

            else -> null
        }
    }
}

internal fun CommonByteBuffer.listExports(wasmModule: WasmModule?): List<ExportRecord> {
    wasmModule ?: return emptyList()
    val records = with(wasmModule.exportSection) {
        this ?: return@with emptyList()
        this@listExports.scan()
    }
    return records.mapNotNull {
        when (it) {
            is WasmTagExportRecordEntry -> ExportRecord(it.name, it.index.toLong(), it.externType)
            is WasmFunctionExportRecordEntry -> ExportRecord(it.name, it.index.toLong(), it.externType)
            is WasmGlobalExportRecordEntry -> ExportRecord(it.name, it.index.toLong(), it.externType)
            is WasmMemoryExportRecordEntry -> ExportRecord(it.name, it.index.toLong(), it.externType)
            is WasmTableExportRecordEntry -> ExportRecord(it.name, it.index.toLong(), it.externType)
            else -> null
        }
    }
}

internal fun CommonByteBuffer.listExportedFunctions(wasmModule: WasmModule?): List<WasmExportFunctionRecord> {
    wasmModule ?: return emptyList()
    val records = with(wasmModule.exportSection) {
        this ?: return@with emptyList()
        this@listExportedFunctions.scan()
    }
    val imports = importSymbols(wasmModule)
    val functions = listFunctions(wasmModule)
    return records
        .filter { it is WasmFunctionExportRecordEntry }
        .map { it as WasmFunctionExportRecordEntry }.mapNotNull {
            val index = it.index.toInt()
            if (index < imports.size) {
                val function = imports.getOrNull(index) ?: return@mapNotNull null
                WasmExportFunctionRecord(it.name, function)
            } else {
                val function = functions.getOrNull(index - imports.size) ?: return@mapNotNull null
                WasmExportFunctionRecord(it.name, function)
            }
        }
}

internal fun CommonByteBuffer.listFunctions(wasmModule: WasmModule?): List<GeneralFunctionEntry> {
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
    val imports = importSymbols(wasmModule)
    val indexBase = imports.size
    val signatures = records.mapIndexedNotNull { index, typeIndex ->
        (types.getOrNull(typeIndex) as? WasmSectionFunctionTypeRecord)?.toSignature()?.let { signature ->
            GeneralFunctionEntry(
                index + indexBase,
                codes.getOrNull(index)?.offset,
                signature,
                codes.getOrNull(index)?.locals?.map {
                    when (it.type) {
                        is WasmNumType -> LocalNum(it.local, it.type.code as Byte)
                        is WasmVecType -> LocalVec(it.local)
                        is WasmPactTypeI8 -> LocalPackI8(it.local)
                        is WasmPactTypeI16 -> LocalPackI16(it.local)
                        is WasmRefType -> {
                            val referenceType = it.type.code as WasmReferenceType
                            val heapType = referenceType.heapType
                            when (heapType) {
                                is WasmExnRefHeapType -> LocalExn(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmArrayRefHeapType -> LocalArray(
                                    it.local,
                                    it.type.code is WasmNullableReferenceType
                                )

                                is WasmFuncHeapType -> LocalFunc(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmStructRefHeapType -> LocalStruct(
                                    it.local,
                                    it.type.code is WasmNullableReferenceType
                                )

                                is WasmI31RefHeapType -> LocalI31(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmEqRefHeapType -> LocalEq(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmAnyRefHeapType -> LocalAny(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmExternRefHeapType -> LocalExtern(
                                    it.local,
                                    it.type.code is WasmNullableReferenceType
                                )

                                is WasmNoneHeapType -> LocalNone(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmNoExnHeapType -> LocalNoExn(it.local, it.type.code is WasmNullableReferenceType)
                                is WasmNoFuncHeapType -> LocalNoFunc(
                                    it.local,
                                    it.type.code is WasmNullableReferenceType
                                )

                                is WasmNoExternRefHeapType -> LocalNoExtern(
                                    it.local,
                                    it.type.code is WasmNullableReferenceType
                                )

                                is WasmIndexHeapType -> LocalIndex(
                                    it.local,
                                    heapType.type,
                                    it.type.code is WasmNullableReferenceType
                                )
                            }
                        }
                    }
                },
                codes.getOrNull(index)?.opCodes?.wat()
            )
        }
    }
    return signatures
}

internal fun CommonByteBuffer.functionsByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<FunctionEntry> {
    wasmModule ?: return emptyList()
    val imports = importSymbols(wasmModule)
    val functions = listFunctions(wasmModule)
    return  imports.filter { indices.contains(it.index) } + functions.filter { indices.contains(it.index) }
}

internal fun CommonByteBuffer.importSymbols(
    wasmModule: WasmModule?
): List<ImportFunctionEntry> {
    val records = with(wasmModule!!.importSection) {
        this ?: return@with emptyList()
        this@importSymbols.getImportRecords()
    }

    val types = types(wasmModule)
    val signatures = records.mapIndexed { index, record ->
        val signature = when (record.type) {
            is WasmTypeIndexExternalType -> (record.type as WasmTypeIndexExternalType).toSignature(types)
            else -> null
        }
        ImportFunctionEntry(index,record.prefix, record.name, signature)
    }
    return signatures
}

internal fun CommonByteBuffer.types(wasmModule: WasmModule): List<WasmTypeRecord> =
    with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        parseTypes()
    }

internal fun CommonByteBuffer.listGlobals(wasmModule: WasmModule?): List<GlobalRecord> {
    wasmModule ?: return emptyList()
    val globals = with(wasmModule.globalSection) {
        this ?: return@with emptyList()
        scan()
    }
    return globals.map { GlobalRecord(it.globalidx, it.type.valueType.toSignature(), it.type.mutable, it.expression.wat()) }
}

internal fun CommonByteBuffer.listGlobalsByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<GlobalRecord> {
    wasmModule ?: return emptyList()
    return listGlobals(wasmModule).filter { indices.contains(it.globalIdx) }
}

data class GlobalRecord(val globalIdx: Int, val type: String, val mutability: Boolean, val expression: String?)

internal fun CommonByteBuffer.listMemory(wasmModule: WasmModule?): List<MemoryRecord> {
    wasmModule ?: return emptyList()
    val memories = with(wasmModule.memorySection) {
        this ?: return@with emptyList()
        scan()
    }

    return memories.map { MemoryRecord(it.memidx, Limits(it.limits)) }
}

internal fun CommonByteBuffer.listMemoryByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<MemoryRecord> {
    wasmModule ?: return emptyList()
    return listMemory(wasmModule).filter { indices.contains(it.memoryIdx) }
}

internal data class MemoryRecord(val memoryIdx: Int, val limit: Limits<*>)

internal class Limits<T>(private val internal: WasmLimits<T>){
    val addressType: String
        get() {
            when (internal.min) {
                is Int -> return "i32"
                is Long -> return "i64"
                else -> throw IllegalStateException("Unknown address type")
            }
        }
    val min: T
        get() = internal.min
    val max: T?
        get() = internal.max
    val hasMax: Boolean
        get() = internal is WasmOpenI64Limits || internal is WasmOpenI32Limits
    val range: String
        get() = "${internal.min}..${internal.max ?: "ε"}"
    val flag: String
        get() = "0x${internal.kind.toHexString()}"
}

val objectMapper = jacksonObjectMapper().apply {
    registerModule(KotlinModule.Builder().build())
}

private class LocalSerializer : JsonSerializer<Local>() {

    override fun serialize(value: Local, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeStartObject()

        gen.writeNumberField("local", value.local)
        gen.writeStringField("type", stringifyLocalType(value))

        gen.writeEndObject()
    }

    private fun stringifyLocalType(value: Local): String =
        when (value) {
            is LocalNum       -> "num:${byteToNumType(value.type)}"
            is LocalPackI8    -> "pack:i8"
            is LocalPackI16   -> "pack:i16"
            is LocalVec       -> "vec:v128"
            is LocalEq        -> "ref:eq${nullable(value)}"
            is LocalAny       -> "ref:anyref${nullable(value)}"
            is LocalNone      -> "ref:none${nullable(value)}"
            is LocalNoExn     -> "ref:noexn${nullable(value)}"
            is LocalNoFunc    -> "ref:nofunc${nullable(value)}"
            is LocalNoExtern  -> "ref:noextern${nullable(value)}"
            is LocalFunc      -> "ref:func${nullable(value)}"
            is LocalStruct    -> "ref:struct${nullable(value)}"
            is LocalI31       -> "ref:i31${nullable(value)}"
            is LocalExn       -> "ref:exn${nullable(value)}"
            is LocalExtern    -> "ref:extern${nullable(value)}"
            is LocalArray     -> "ref:array${nullable(value)}"
            is LocalIndex     -> "ref:index:${value.type}${nullable(value)}"
        }

    private fun nullable(v: Local): String =
        when (v) {
            is LocalEq, is LocalAny, is LocalNone, is LocalNoExn, is LocalNoFunc,
            is LocalNoExtern, is LocalFunc, is LocalStruct, is LocalI31,
            is LocalExn, is LocalExtern, is LocalArray, is LocalIndex ->
                if ((v as? Any)?.let { (it as? LocalEq)?.nullable } == true) "?" else ""
            else -> ""
        }

    private fun byteToNumType(type: Byte) =
        when (type.toInt()) {
            0x7F -> "i32"
            0x7E -> "i64"
            0x7D -> "f32"
            0x7C -> "f64"
            else -> "unknown"
        }
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


data class DataEntry(
    val kind: Int,
    val index: Int,
    val offset: Long,
    val data: ByteArray,
    val memoryIndex: UInt? = null,
    val expression: String? = null
) {
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

data class WasmExportFunctionRecord(val name: String, val entry: FunctionEntry)

sealed class FunctionEntry(open val index: Int)
data class ImportFunctionEntry(
    @JsonProperty("funcidx")
    override val index: Int,
    @JsonProperty("module")
    val module: String,
    @JsonProperty("name")
    val name: String,
    @JsonProperty("signature")
    val signature: SerializationSignature?
): FunctionEntry(index)

data class GeneralFunctionEntry(
    @JsonProperty("funcidx")
    override val index: Int,
    @JsonProperty("offset")
    val offset: Long?,
    @JsonProperty("signature")
    val signature: SerializationSignature?,
    @JsonProperty("locals")
    val locals: List<Local>?,
    @JsonProperty("code")
    val code: String? = null
) : FunctionEntry(index)

@JsonSerialize(using = LocalSerializer::class)
sealed class Local(open val local: Int)
data class LocalNum(override val local: Int, val type: Byte) : Local(local)
data class LocalPackI8(override val local: Int) : Local(local)
data class LocalPackI16(override val local: Int) : Local(local)
data class LocalVec(override val local: Int) : Local(local)
data class LocalEq(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalAny(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalNone(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalNoExn(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalNoFunc(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalNoExtern(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalIndex(override val local: Int, val type: Long, val nullable: Boolean) : Local(local)
data class LocalFunc(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalStruct(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalI31(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalExn(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalExtern(override val local: Int, val nullable: Boolean) : Local(local)
data class LocalArray(override val local: Int, val nullable: Boolean) : Local(local)

data class ParameterSignature(
    @JsonProperty("params")
    val params: List<String>,
    @JsonProperty("return")
    val ret: List<String>
) : SerializationSignature

data class ExportRecord(val name: String, val externIndex: Long, val kind: Byte)

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
