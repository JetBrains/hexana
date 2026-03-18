package org.jetbrains.hexana.mcp

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
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmLimits
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmOpenI32Limits
import org.jetbrains.hexana.wasm.sections.WasmOpenI64Limits
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry



internal fun CommonByteBuffer.listDataSectionInternal(wasmModule: WasmModule?): List<DataEntry> {
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
                record.offset.toLong(),
                record.data,
                expression = record.expression.wat()
            )

            is WasmDataRecordPassive -> DataEntry(1, index, record.offset.toLong(), record.data)
            is WasmDataRecordActiveWithMemoryIndex -> DataEntry(
                2,
                index,
                record.offset.toLong(),
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
