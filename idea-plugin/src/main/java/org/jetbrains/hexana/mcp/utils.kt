package org.jetbrains.hexana.mcp

import com.fasterxml.jackson.annotation.JsonInclude
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
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.sections.WasmAnyRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmCodeLocalRecord
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActive
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActiveWithMemoryIndex
import org.jetbrains.hexana.wasm.sections.WasmDataRecordPassive
import org.jetbrains.hexana.wasm.sections.WasmEqRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExnRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmFieldType
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmI31RefHeapType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmIndexHeapType
import org.jetbrains.hexana.wasm.sections.WasmLimits
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmNoExnHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmNonNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNoneHeapType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNumType
import org.jetbrains.hexana.wasm.sections.WasmOpenI32Limits
import org.jetbrains.hexana.wasm.sections.WasmOpenI64Limits
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmReferenceHeapType
import org.jetbrains.hexana.wasm.sections.WasmReferenceType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType


internal fun CommonByteBuffer.listDataSections(wasmModule: WasmModule?): List<DataEntry> {
    wasmModule ?: return emptyList()
    val data = with(wasmModule.dataSection) {
        this ?: return@with emptyList()
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
    val imports = importFunctions(wasmModule)
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
    val exports = listExports(wasmModule)
    val imports = importFunctions(wasmModule).mapIndexed { index, entry ->
        GeneralFunctionEntry(
            funcidx = index,
            import = Import(
                module = entry.module,
                name = entry.name),
            codeOffset = null,
            signature = entry.signature,
            exportedAs = exportedAs(exports, index),
            definedIn = "import",
            codeSize = null,
            instructionCount = null)}
    val records = with(wasmModule.functionSection) {
        this ?: return@with emptyList()
        this@listFunctions.parseFunctions()
    }
    val codes = with(wasmModule.codeSection) {
        this ?: return@with emptyList()
        this@listFunctions.scan()
    }
    val indexBase = imports.size
    val functions = records.mapIndexedNotNull { index, typeIndex ->
        (types.getOrNull(typeIndex) as? WasmSectionFunctionTypeRecord)?.toSignature()?.let { signature ->
            val funcidx = index + indexBase
            GeneralFunctionEntry(
                funcidx = funcidx,
                import = null,
                codeOffset = codes.getOrNull(index)?.offset,
                signature = signature,
                exportedAs = exportedAs(exports, funcidx),
                definedIn = "function_section",
                codeSize = codes.getOrNull(index)?.size?.toLong(),
                instructionCount = codes.getOrNull(index)?.opCodes?.size?.toLong()
            )
        }
    }
    return imports + functions
}

private fun exportedAs(exports: List<ExportRecord>, index: Int):List<String> {
    return exports.filter { record -> record.kind.toInt() == 0 && record.externIndex.toInt() == index }
        .map { record -> record.name }
}

private fun serializeOpcodes(
    codes: List<WasmCodeRecord>,
    index: Int
): String? = codes.getOrNull(index)?.opCodes?.wat()

private fun serializeLocals(
    codes: List<WasmCodeRecord>,
    index: Int
): List<Local>? {
    val locals = codes.getOrNull(index)?.locals
    return locals.serializeLocals()
}

private fun List<WasmCodeLocalRecord>?.serializeLocals(): List<Local> = this?.map {
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
} ?: emptyList()

internal fun CommonByteBuffer.functionsByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<FunctionEntry> {
    wasmModule ?: return emptyList()
    val functions = listFunctions(wasmModule)
    return functions.filter { indices.contains(it.funcidx) }
}

fun CommonByteBuffer.localsForFunctions(indices: List<Int>, wasmModule: WasmModule?): List<LocalsByFunction> {
    wasmModule ?: return emptyList()
    wasmModule.codeSection ?: return emptyList()
    val importFunctions = importFunctions(wasmModule)
    val importBase = importFunctions.size
    val localsInImports = indices.filter { importBase < it }.map {
        LocalsByFunction(it, emptyList())
    }
    return localsInImports +
            with(wasmModule.codeSection!!) {
                scanLocalsEntries(
                    indices.map { it - importBase }.filter { it > 0 })
                    .map { LocalsByFunction(it.key, it.value.serializeLocals()) }.toList()
            }
}

data class LocalsByFunction(val funcidx: Int, val locals: List<Local>)

internal fun CommonByteBuffer.importFunctions(
    wasmModule: WasmModule?
): List<ImportFunctionEntry> {
    val records = with(wasmModule!!.importSection) {
        this ?: return@with emptyList()
        this@importFunctions.getImportRecords()
    }

    val types = types(wasmModule)
    val signatures = records.mapIndexed { index, record ->
        val signature = when (record.type) {
            is WasmTypeIndexExternalType -> (record.type as WasmTypeIndexExternalType).toSignature(types)
            else -> null
        }
        ImportFunctionEntry(index, record.prefix, record.name, signature)
    }
    return signatures
}

internal fun CommonByteBuffer.types(wasmModule: WasmModule): List<WasmTypeRecord> =
    with(wasmModule.typesSection) {
        this ?: return@with emptyList()
        parseTypes()
    }

internal fun CommonByteBuffer.listTypes(
    wasmModule: WasmModule?,
): List<McpType> {
    wasmModule ?: return emptyList()
    return types(wasmModule).mapIndexed { index, record ->
        record.toMcpType(index)
    }
}

private fun WasmTypeRecord.toMcpType(index: Int? = null): McpType = when (this) {
    is WasmArrayTypeRecord -> index?.let {
        McpArrayIndexed(index, this.fieldType.toMcpField())
    } ?: McpArray(this.fieldType.toMcpField())

    is WasmStructTypeRecord -> index?.let {
        McpStructIndexed(it, this.superTypes, this.fields.map { it.toMcpField() })
    } ?: McpStruct(this.superTypes, this.fields.map { it.toMcpField() })

    is WasmRecursiveTypeRecord -> index?.let {
        McpRecursiveIndexed(it, this.entries.map { it.toMcpType() })
    } ?: McpRecursive(this.entries.map { it.toMcpType() })

    is WasmSectionFunctionTypeRecord -> index?.let {
        McpFuncIndexed(it, this.params.map { it.toMcpType() }, this.retTypes.map { it.toMcpType() })
    } ?: McpFunc(this.params.map { it.toMcpType() }, this.retTypes.map { it.toMcpType() })
}

private fun WasmFieldType.toMcpField(): McpField = McpField(storageType.toMcpType(), isMutable)
private fun WasmValueType<*>.toMcpType(): McpType {
    return when (this) {
        is WasmF32Type -> McpVal("i32")
        is WasmF64Type -> McpVal("i64")
        is WasmI32Type -> McpVal("i32")
        is WasmI64Type -> McpVal("i64")
        is WasmVecType -> McpVal("v128")
        is WasmPactTypeI8 -> McpPacked("i8")
        is WasmPactTypeI16 -> McpPacked("i16")
        is WasmRefType -> {
            when (code) {
                is WasmNullableReferenceType,
                is WasmNonNullableReferenceType -> McpRefWithNullability(
                    code.heapType.toMcpType(),
                    code is WasmNullableReferenceType
                )

                is WasmReferenceHeapType -> McpRef(code.heapType.toMcpType())
            }
        }
    }
}

private fun WasmHeapType.toMcpType(): String = when (this) {
    is WasmExnRefHeapType -> "exn"
    is WasmArrayRefHeapType -> "array"
    is WasmFuncHeapType -> "funcref"
    is WasmStructRefHeapType -> "struct"
    is WasmI31RefHeapType -> "i31ref"
    is WasmEqRefHeapType -> "eq"
    is WasmAnyRefHeapType -> "any"
    is WasmExternRefHeapType -> "extern"
    is WasmNoneHeapType -> "none"
    is WasmNoExnHeapType -> "noexn"
    else -> error("Unknown heap type: $this")
}

sealed class McpType(val kind: String)
class McpArrayIndexed(val typeidx: Int, val elementType: McpField) : McpType("array")
class McpArray(val elementType: McpField) : McpType("array")
class McpStructIndexed(val typeidx: Int, val superTypes: List<UInt>, val fields: List<McpField>) : McpType("struct")
class McpStruct(val superTypes: List<UInt>, val fields: List<McpField>) : McpType("struct")
class McpField(val type: McpType, val mutable: Boolean)
class McpRecursiveIndexed(val typeidx: Int, val entries: List<McpType>) : McpType("rec")
class McpRecursive(val entries: List<McpType>) : McpType("rec")
class McpFuncIndexed(val typeidx: Int, val params: List<McpType>, val retTypes: List<McpType>) : McpType("func")
class McpFunc(val params: List<McpType>, val retTypes: List<McpType>) : McpType("func")
class McpVal(val type: String) : McpType("val")
class McpPacked(val packType: String) : McpType("packed")
class McpRef(val refKind: String) : McpType("ref")
class McpRefWithNullability(val refKind: String, val nullable: Boolean) : McpType("ref")

internal fun CommonByteBuffer.listTypesByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<WasmTypeRecord> {
    wasmModule ?: return emptyList()
    return types(wasmModule).filterIndexed { index, _ -> indices.contains(index) }
}

internal fun CommonByteBuffer.listGlobals(wasmModule: WasmModule?): List<GlobalRecord> {
    wasmModule ?: return emptyList()
    val importedGlobals = with(wasmModule.importSection) {
        this ?: return@with emptyList()
        getImportRecords().filter { it.type is WasmGlobalType }.mapIndexed { index, record ->
            val globalType = record.type as WasmGlobalType

            GlobalRecord(
                index,
                record.prefix,
                record.name,
                globalType.valueType.toSignature(),
                globalType.mutable,
                null,
                "import"
            )
        }
    }
    val baseIndex = importedGlobals.size
    val globals = with(wasmModule.globalSection) {
        this ?: return@with emptyList()
        scan().mapIndexed { index, record ->
            GlobalRecord(
                baseIndex + index,
                null,
                null,
                record.type.valueType.toSignature(),
                record.type.mutable,
                record.expression.wat(), "global_section"
            )
        }
    }
    return importedGlobals + globals
}

internal fun CommonByteBuffer.listGlobalsByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<GlobalRecord> {
    wasmModule ?: return emptyList()
    return listGlobals(wasmModule).filter { indices.contains(it.globalIdx) }
}

data class GlobalRecord(
    val globalIdx: Int,
    val prefix: String?,
    val name: String?,
    val type: String,
    val mutability: Boolean,
    val expression: String?,
    val definedIn: String
)

internal fun CommonByteBuffer.listMemory(wasmModule: WasmModule?): List<MemoryRecord> {
    wasmModule ?: return emptyList()
    val importMemories = with(wasmModule.importSection) {
        this ?: return@with emptyList()
        getImportRecords().filter { it.type is WasmMemType }
    }.mapIndexed { index, record ->
        MemoryRecord(index, Limits((record.type as WasmMemType).limits), "import")
    }
    val importBase = importMemories.size
    val memories = with(wasmModule.memorySection) {
        this ?: return@with emptyList()
        scan()
    }.mapIndexed { index, record ->
        MemoryRecord(importBase + index, Limits(record.limits), "memory_section")
    }
    return importMemories + memories
}

internal fun CommonByteBuffer.listMemoryByIndices(
    wasmModule: WasmModule?,
    indices: List<Int>
): List<MemoryRecord> {
    wasmModule ?: return emptyList()
    return listMemory(wasmModule).filter { indices.contains(it.memoryIdx) }
}

internal data class MemoryRecord(val memoryIdx: Int, val limit: Limits<*>, val definedIn: String)

internal class Limits<T>(private val internal: WasmLimits<T>) {
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
        get() = internal !is WasmOpenI64Limits && internal !is WasmOpenI32Limits
    val range: String
        get() = "${internal.min}..${internal.max ?: "ε"}"
    val flag: String
        get() = "0x${internal.kind.toHexString()}"
}

val objectMapper = jacksonObjectMapper().apply {
    registerModule(KotlinModule.Builder().build())
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
    setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
    setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
}

private class LocalSerializer : JsonSerializer<Local>() {

    override fun serialize(value: Local, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeStartObject()

        gen.writeNumberField("local", value.count)
        gen.writeStringField("type", stringifyLocalType(value))

        gen.writeEndObject()
    }

    private fun stringifyLocalType(value: Local): String =
        when (value) {
            is LocalNum -> "num:${byteToNumType(value.type)}"
            is LocalPackI8 -> "pack:i8"
            is LocalPackI16 -> "pack:i16"
            is LocalVec -> "vec:v128"
            is LocalEq -> "ref:eq${nullable(value)}"
            is LocalAny -> "ref:anyref${nullable(value)}"
            is LocalNone -> "ref:none${nullable(value)}"
            is LocalNoExn -> "ref:noexn${nullable(value)}"
            is LocalNoFunc -> "ref:nofunc${nullable(value)}"
            is LocalNoExtern -> "ref:noextern${nullable(value)}"
            is LocalFunc -> "ref:func${nullable(value)}"
            is LocalStruct -> "ref:struct${nullable(value)}"
            is LocalI31 -> "ref:i31${nullable(value)}"
            is LocalExn -> "ref:exn${nullable(value)}"
            is LocalExtern -> "ref:extern${nullable(value)}"
            is LocalArray -> "ref:array${nullable(value)}"
            is LocalIndex -> "ref:index:${value.type}${nullable(value)}"
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

sealed class FunctionEntry(open val funcidx: Int)
data class ImportFunctionEntry(
    @JsonProperty("funcidx")
    override val funcidx: Int,
    @JsonProperty("module")
    val module: String,
    @JsonProperty("name")
    val name: String,
    @JsonProperty("signature")
    val signature: SerializationSignature?
) : FunctionEntry(funcidx)

data class GeneralFunctionEntry(
    override val funcidx: Int,
    val signature: SerializationSignature?,
    val import: Import?,
    val exportedAs: List<String>,
    val definedIn: String,
    val codeOffset: Long?,
    val codeSize: Long?,
    val instructionCount: Long?,
) : FunctionEntry(funcidx)

data class Import (val module: String, val name: String)

@JsonSerialize(using = LocalSerializer::class)
sealed class Local(open val count: Int)
data class LocalNum(override val count: Int, val type: Byte) : Local(count)
data class LocalPackI8(override val count: Int) : Local(count)
data class LocalPackI16(override val count: Int) : Local(count)
data class LocalVec(override val count: Int) : Local(count)
data class LocalEq(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalAny(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNone(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNoExn(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNoFunc(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalNoExtern(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalIndex(override val count: Int, val type: Long, val nullable: Boolean) : Local(count)
data class LocalFunc(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalStruct(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalI31(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalExn(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalExtern(override val count: Int, val nullable: Boolean) : Local(count)
data class LocalArray(override val count: Int, val nullable: Boolean) : Local(count)

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
