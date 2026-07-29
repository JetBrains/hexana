package org.jetbrains.hexana.wasm.analysis.exceptions

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.analysis.dispatch.DispatchSignature
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTagType

/** One decoded exception tag: its global index, origin, attribute, and payload. */
data class TagInfo(
    val globalIndex: Int,
    val imported: Boolean,
    val attribute: Int,
    val typeIdx: Int,
    val payloadText: String,
)

/**
 * Resolves a module's exception tags to display signatures. Tag index space is
 * imported tags (import section, external kind tag) first, then the tags
 * declared in the Tag section. Each tag references a `typeidx` whose params are
 * the exception payload (results are always empty for exception tags), so the
 * label reuses [DispatchSignature] rendering.
 */
class WasmExceptionTags internal constructor(
    val entries: List<TagInfo>,
) {

    /** Number of tags in the whole module (imported + defined). */
    val count: Int get() = entries.size

    /** `(i32)` payload rendering for [globalTagIdx], or null when unknown. */
    fun payloadText(globalTagIdx: Int): String? = entries.getOrNull(globalTagIdx)?.payloadText
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
                    if (entry is WasmSectionFunctionTypeRecord) {
                        typeSignatures[typeIdx] = DispatchSignature(entry.params, entry.retTypes)
                    }
                    typeIdx++
                }
                is WasmSectionFunctionTypeRecord -> {
                    typeSignatures[typeIdx] = DispatchSignature(record.params, record.retTypes)
                    typeIdx++
                }
                else -> typeIdx++
            }
        }

        fun payload(ti: Int): String {
            val sig = typeSignatures[ti] ?: return "(?)"
            return "(" + sig.params.joinToString(", ") { valueTypeName(it) } + ")"
        }

        val entries = ArrayList<TagInfo>()
        var idx = 0
        module.importSection?.let { section ->
            for (record in with(section) { buffer.getImportRecords() }) {
                val type = record.type
                if (type is WasmTagType) {
                    val ti = type.index.index.toInt()
                    entries.add(TagInfo(idx++, imported = true, attribute = 0, typeIdx = ti, payloadText = payload(ti)))
                }
            }
        }
        module.exceptionSection?.let { section ->
            for (tag in with(section) { buffer.scanTags() }) {
                entries.add(
                    TagInfo(
                        globalIndex = idx++,
                        imported = false,
                        attribute = tag.attribute,
                        typeIdx = tag.typeIdx,
                        payloadText = payload(tag.typeIdx),
                    ),
                )
            }
        }
        return WasmExceptionTags(entries)
    }
}

private fun valueTypeName(type: Any): String =
    type.toString().substringAfterLast('.').removeSuffix("Type").lowercase()
