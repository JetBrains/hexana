package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.*


internal fun CommonByteBuffer.listDataSectionInternal(wasmModule: WasmModule?): List<DataEntry> {
    wasmModule ?: return emptyList()
    val data = with(wasmModule.dataSection) {
        this ?: return@with emptyList()
        scan()
    }
    return data.mapIndexedNotNull { index, record ->
        // record.data is a CommonByteBuffer view; materialize once here for
        // out-of-process serialization. Inside the parser we keep the view
        // to avoid duplicating multi-MB data segments.
        when (record) {
            is WasmDataRecordActive -> DataEntry(
                0,
                index,
                record.offset.toLong(),
                record.data.toByteArray(),
                expression = record.expression.wat()
            )

            is WasmDataRecordPassive -> DataEntry(1, index, record.offset.toLong(), record.data.toByteArray())
            is WasmDataRecordActiveWithMemoryIndex -> DataEntry(
                2,
                index,
                record.offset.toLong(),
                record.data.toByteArray(),
                record.memoryIndex,
                record.expression.wat()
            )

            else -> null
        }
    }
}

fun CommonByteBuffer.listExports(wasmModule: WasmModule?): List<ExportRecord> {
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

fun CommonByteBuffer.listGlobals(wasmModule: WasmModule?): List<GlobalRecord> {
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

fun CommonByteBuffer.listMemory(wasmModule: WasmModule?): List<MemoryRecord> {
    wasmModule ?: return emptyList()
    val importMemories = with(wasmModule.importSection) {
        this ?: return@with emptyList()
        getImportRecords().filter { it.type is WasmMemType }
    }.mapIndexed { index, record ->
        MemoryRecord(
            memoryIdx = index,
            limit = Limits((record.type as WasmMemType).limits),
            import = Import(record.prefix, record.name),
            definedIn = "import"
        )
    }
    val importBase = importMemories.size
    val memories = with(wasmModule.memorySection) {
        this ?: return@with emptyList()
        scan()
    }.mapIndexed { index, record ->
        MemoryRecord(
            memoryIdx = importBase + index,
            limit = Limits(record.limits),
            import = null,
            definedIn = "memory_section"
        )
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

data class MemoryRecord(val memoryIdx: Int, val limit: Limits<*>, val import: Import?, val definedIn: String)

class Limits<T>(private val internal: WasmLimits<T>) {
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

data class ExportRecord(val name: String, val externIndex: Long, val kind: Byte)
