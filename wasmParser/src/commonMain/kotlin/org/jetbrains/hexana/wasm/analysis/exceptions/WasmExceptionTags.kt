package org.jetbrains.hexana.wasm.analysis.exceptions

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.analysis.dispatch.DispatchSignature
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTagType

/**
 * Resolves a module's exception tags to display signatures. Tag index space is
 * imported tags (import section, external kind tag) first, then the tags
 * declared in the Tag section. Each tag references a `typeidx` whose params are
 * the exception payload (results are always empty for exception tags), so the
 * label reuses [DispatchSignature] rendering.
 */
class WasmExceptionTags internal constructor(
    private val tagTypeIndices: List<Int>,
    private val typeSignatures: Map<Int, DispatchSignature>,
) {

    /** Number of tags in the whole module (imported + defined). */
    val count: Int get() = tagTypeIndices.size

    /** `(i32)` payload rendering for [globalTagIdx], or null when unknown. */
    fun payloadText(globalTagIdx: Int): String? {
        val typeIdx = tagTypeIndices.getOrNull(globalTagIdx) ?: return null
        val sig = typeSignatures[typeIdx] ?: return null
        return "(" + sig.params.joinToString(", ") { it.toString().substringAfterLast('.').removeSuffix("Type").lowercase() } + ")"
    }
}

object WasmExceptionTagsBuilder {
    fun build(buffer: CommonByteBuffer, module: WasmModule): WasmExceptionTags {
        // typeidx -> function signature, rec groups expanded (each subtype gets
        // its own consecutive index — positional indexing is wrong for GC).
        val typeRecords = module.typesSection?.let { with(it) { buffer.parseTypes() } } ?: emptyList()
        val typeSignatures = HashMap<Int, DispatchSignature>(typeRecords.size * 2)
        var typeIdx = 0
        for (record in typeRecords) {
            when (record) {
                is WasmRecursiveTypeRecord -> for (entry in record.entries) {
                    if (entry is WasmSectionFunctionTypeRecord) typeSignatures[typeIdx] = DispatchSignature(entry.params, entry.retTypes)
                    typeIdx++
                }
                is WasmSectionFunctionTypeRecord -> {
                    typeSignatures[typeIdx] = DispatchSignature(record.params, record.retTypes)
                    typeIdx++
                }
                else -> typeIdx++
            }
        }

        val tagTypeIndices = ArrayList<Int>()
        module.importSection?.let { section ->
            for (record in with(section) { buffer.getImportRecords() }) {
                val type = record.type
                if (type is WasmTagType) tagTypeIndices.add(type.index.index.toInt())
            }
        }
        module.exceptionSection?.let { section ->
            for (tag in with(section) { buffer.scanTags() }) tagTypeIndices.add(tag.typeIdx)
        }
        return WasmExceptionTags(tagTypeIndices, typeSignatures)
    }
}
