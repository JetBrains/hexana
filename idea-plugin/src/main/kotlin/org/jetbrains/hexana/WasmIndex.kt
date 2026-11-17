package org.jetbrains.hexana

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.indexing.*
import com.intellij.util.indexing.impl.CollectionDataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParseFailure
import org.jetbrains.hexana.wasm.WasmParseResult
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.WasmComponent
import org.jetbrains.hexana.wit.WitDeclaration
import org.jetbrains.hexana.wit.WitDeclarationExternalizer
import org.jetbrains.hexana.wit.WitDeclarationType
import org.jetbrains.hexana.wit.psi.WitResolver

val WasmIndexId: ID<String, Collection<WitDeclaration>> = ID.create("WasmIndex")

class WasmIndex : FileBasedIndexExtension<String, Collection<WitDeclaration>>() {
    override fun getName() = WasmIndexId

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == WasmFileType.INSTANCE }

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer() = CollectionDataExternalizer(WitDeclarationExternalizer)

    override fun getVersion(): Int = 3

    override fun getIndexer() = DataIndexer<String, Collection<WitDeclaration>, FileContent> { content ->
        buildMap<String, MutableCollection<WitDeclaration>> {
            val buffer = content.content.toCommonByteBuffer()
            (tryParse(buffer)
                    as? WasmParseSuccess)?.let {
                it.wasmModule as? WasmComponent
            }?.exportSection?.forEach {
                val parsed = with(it) { buffer.scan() }
                parsed.forEach {
                    val (name, version) = WitResolver.splitNameAndVersion(it.exportName)
                    put(name, mutableListOf(
                        WitDeclaration(
                            name, it.offset, WitDeclarationType.EXPORT,
                            it.offset + it.size,
                            version
                        )
                    ))
                }
            }
        }
    }
}

internal fun tryParse(buffer: CommonByteBuffer): WasmParseResult = try {
    WasmParser().parse(buffer)
} catch (e: Throwable) {
    logger<WasmIndex>().error("Failed to parse Wasm file", e)
    WasmParseFailure()
}
