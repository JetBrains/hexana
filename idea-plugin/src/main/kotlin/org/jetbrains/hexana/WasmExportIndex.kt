package org.jetbrains.hexana

import com.intellij.util.indexing.*
import com.intellij.util.indexing.impl.CollectionDataExternalizer
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import java.io.DataInput
import java.io.DataOutput

val WasmExportIndexId: ID<String, Collection<WasmExportSymbolEntry>> = ID.create("WasmExportIndex")

/**
 * Indexed entry for a regular WebAssembly module export. Component-model exports
 * are tracked separately by [WasmIndex]; this index covers the byte-level export
 * section visible in the Hexana "Exports" tab.
 *
 * `externType` mirrors the WASM externidx tag: 0=function, 1=table, 2=memory,
 * 3=global, 4=tag, anything else = unsupported/unknown.
 */
data class WasmExportSymbolEntry(
    val name: String,
    val offset: Int,
    val size: Int,
    val externType: Byte,
)

object WasmExportSymbolEntryExternalizer : DataExternalizer<WasmExportSymbolEntry> {
    override fun save(out: DataOutput, value: WasmExportSymbolEntry) {
        out.writeUTF(value.name)
        out.writeInt(value.offset)
        out.writeInt(value.size)
        out.writeByte(value.externType.toInt())
    }

    override fun read(`in`: DataInput) = WasmExportSymbolEntry(
        `in`.readUTF(),
        `in`.readInt(),
        `in`.readInt(),
        `in`.readByte(),
    )
}

class WasmExportIndex : FileBasedIndexExtension<String, Collection<WasmExportSymbolEntry>>() {
    override fun getName() = WasmExportIndexId

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == WasmFileType.INSTANCE }

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer() = CollectionDataExternalizer(WasmExportSymbolEntryExternalizer)

    override fun getVersion(): Int = 1

    override fun getIndexer() = DataIndexer<String, Collection<WasmExportSymbolEntry>, FileContent> { content ->
        val buffer = content.content.toCommonByteBuffer()
        val module = (WasmParser().parse(buffer) as? WasmParseSuccess)?.wasmModule as? WasmModule
            ?: return@DataIndexer emptyMap()
        val exportSection = module.exportSection ?: return@DataIndexer emptyMap()
        val entries = with(exportSection) { buffer.scan() }
        buildMap<String, MutableCollection<WasmExportSymbolEntry>> {
            for (entry in entries) {
                getOrPut(entry.name) { mutableListOf() } += WasmExportSymbolEntry(
                    entry.name, entry.offset, entry.size, entry.externType
                )
            }
        }
    }
}
