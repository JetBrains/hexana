package org.jetbrains.hexana.wasmcache

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.PersistentHashMap
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.buildComponentTypeIndex
import org.jetbrains.hexana.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.ComponentDefType
import org.jetbrains.hexana.wasm.components.InstanceExpression
import org.jetbrains.hexana.wasm.components.WasmComponent
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import java.io.DataInput
import java.io.DataOutput
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal sealed class ParsedWasmData

internal class CoreWasmData(
    val exports: List<WasmExportRecordEntry>,
    val typeRecords: List<WasmTypeRecord>,
    val funcTypeIndices: List<Int>,
    val importedFunctions: Int,
    val imports: List<WasmImportRecord>,
) : ParsedWasmData()

internal class ComponentWasmData(
    val allTypes: List<ComponentDefType>,
    val allComponentInstances: List<InstanceExpression>,
    val exports: List<WasmComponentExport>,
) : ParsedWasmData()

private data class WasmCacheKey(val url: String, val modificationStamp: Long)

private class WasmBytesEntry(val modificationStamp: Long, val bytes: ByteArray)

private object WasmBytesExternalizer : DataExternalizer<WasmBytesEntry> {
    override fun save(out: DataOutput, value: WasmBytesEntry) {
        out.writeLong(value.modificationStamp)
        out.writeInt(value.bytes.size)
        out.write(value.bytes)
    }

    override fun read(input: DataInput): WasmBytesEntry {
        val stamp = input.readLong()
        val size = input.readInt()
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return WasmBytesEntry(stamp, bytes)
    }
}

private val LOG = logger<WasmBinaryDataCacheService>()

@Service
internal class WasmBinaryDataCacheService : Disposable {
    private val parsedDataCache = ConcurrentHashMap<WasmCacheKey, ParsedWasmData>()
    private val persistentMap: PersistentHashMap<String, WasmBytesEntry>?

    init {
        persistentMap = try {
            val cachePath = Path.of(PathManager.getSystemPath(), "hexana", "wasm-binary-cache")
            PersistentHashMap(cachePath, EnumeratorStringDescriptor.INSTANCE, WasmBytesExternalizer)
        } catch (e: Exception) {
            LOG.warn("Failed to create persistent wasm cache, falling back to in-memory only", e)
            null
        }

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        val file = event.file ?: continue
                        if (!file.name.endsWith(".wasm")) continue
                        when (event) {
                            is VFileContentChangeEvent, is VFileDeleteEvent -> {
                                parsedDataCache.keys.removeAll { it.url == file.url }
                                try {
                                    persistentMap?.remove(file.url)
                                } catch (e: Exception) {
                                    LOG.warn("Failed to remove entry from persistent wasm cache", e)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    fun getOrParseBinary(wasmFile: VirtualFile): ParsedWasmData? {
        val url = wasmFile.url
        val stamp = wasmFile.modificationStamp
        val cacheKey = WasmCacheKey(url, stamp)

        parsedDataCache[cacheKey]?.let { return it }

        val bytes = loadBytes(url, stamp, wasmFile)  ?: return null
        val buffer = bytes.toCommonByteBuffer()
        val parsed = parseBinary(buffer) ?: return null

        parsedDataCache.keys.removeAll { it.url == url && it.modificationStamp != stamp }
        parsedDataCache[cacheKey] = parsed
        return parsed
    }

    private fun loadBytes(url: String, stamp: Long, wasmFile: VirtualFile): ByteArray? {
        try {
            val cached = persistentMap?.get(url)
            if (cached != null && cached.modificationStamp == stamp) {
                return cached.bytes
            }
        } catch (e: Exception) {
            LOG.warn("Failed to read from persistent wasm cache", e)
        }

        val bytes = wasmFile.toCommonByteBuffer()?.let { buffer ->
            ByteArray(buffer.limit()).also { arr ->
                buffer.position(0)
                for (i in arr.indices) {
                    arr[i] = buffer.get()
                }
            }
        } ?: return null

        try {
            persistentMap?.put(url, WasmBytesEntry(stamp, bytes))
        } catch (e: Exception) {
            LOG.warn("Failed to write to persistent wasm cache", e)
        }

        return bytes
    }

    private fun parseBinary(buffer: CommonByteBuffer): ParsedWasmData? {
        return when (WasmBinaryProviderSelector.select(buffer)) {
            BinaryProviderType.Generic -> null
            BinaryProviderType.WasmComponent -> {
                val component = (WasmParser().parse(buffer) as? WasmParseSuccess)?.wasmModule as? WasmComponent
                    ?: return null
                ComponentWasmData(
                    allTypes = buildComponentTypeIndex(component.sections, buffer),
                    allComponentInstances = component.instanceSection.flatMap { with(it) { buffer.parseInstances() } },
                    exports = component.exportSection.flatMap { with(it) { buffer.scan() } },
                )
            }
            BinaryProviderType.Wasm -> {
                val module = (WasmParser().parse(buffer) as? WasmParseSuccess)?.wasmModule as? WasmModule
                    ?: return null
                val exportSection = module.exportSection ?: return null
                val importRecords = module.importSection?.let { sec ->
                    with(sec) { buffer.getImportRecords() }
                } ?: emptyList()
                CoreWasmData(
                    exports = with(exportSection) { buffer.scan() },
                    typeRecords = module.typesSection?.let { with(it) { buffer.parseTypes() } } ?: emptyList(),
                    funcTypeIndices = module.functionSection?.let { with(it) { buffer.parseFunctions() } } ?: emptyList(),
                    importedFunctions = importRecords.count { it.type is WasmTypeIndexExternalType },
                    imports = importRecords,
                )
            }
        }
    }

    override fun dispose() {
        parsedDataCache.clear()
        try {
            persistentMap?.close()
        } catch (e: Exception) {
            LOG.warn("Failed to close persistent wasm cache", e)
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): WasmBinaryDataCacheService = service()
    }
}
