package org.jetbrains.hexana.mlmodel.gguf

import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic

/** Value type of a GGUF metadata entry (`gguf_metadata_value_type`). */
enum class GgufValueType(val id: Int, val label: String) {
    UINT8(0, "uint8"),
    INT8(1, "int8"),
    UINT16(2, "uint16"),
    INT16(3, "int16"),
    UINT32(4, "uint32"),
    INT32(5, "int32"),
    FLOAT32(6, "float32"),
    BOOL(7, "bool"),
    STRING(8, "string"),
    ARRAY(9, "array"),
    UINT64(10, "uint64"),
    INT64(11, "int64"),
    FLOAT64(12, "float64"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: Int): GgufValueType? = byId[id]
    }
}

/**
 * A decoded GGUF metadata value. Exactly one payload field is set, chosen by [type]:
 * [integer] for the integer types, [real] for the float types, [bool], [string], or
 * [elementType] / [count] / [elements] for an array.
 *
 * One flat class rather than a sealed hierarchy so that scripts (which see this through
 * `binary.raw.parsedGguf`) and the generated `.d.ts` both get plain named fields.
 */
data class GgufValue(
    val type: GgufValueType,
    /**
     * Value of an integer type. A `uint64` above [Long.MAX_VALUE] keeps its bits here;
     * [display] reads it unsigned.
     */
    val integer: Long? = null,
    val real: Double? = null,
    val bool: Boolean? = null,
    val string: String? = null,
    /** Element type of an array. */
    val elementType: GgufValueType? = null,
    /** Length the file declares for an array. */
    val count: Long? = null,
    /**
     * Array elements; at most [GgufParser.MAX_ARRAY_ELEMENTS_KEPT] of them, so a tokenizer
     * vocabulary is not held twice over.
     */
    val elements: List<GgufValue>? = null,
) {
    /** Whether an array holds fewer elements than the file declares. */
    val truncated: Boolean get() = elements != null && elements.size.toLong() < (count ?: 0L)

    /** Human-readable rendering; arrays show their element type, length and first few elements. */
    fun display(maxArrayElements: Int = 8, maxStringChars: Int = 200): String {
        integer?.let { return if (type == GgufValueType.UINT64) it.toULong().toString() else it.toString() }
        real?.let { return it.toString() }
        bool?.let { return it.toString() }
        string?.let { return if (it.length > maxStringChars) it.take(maxStringChars) + "…" else it }
        val items = elements ?: return ""
        return buildString {
            append(elementType?.label).append('[').append(count).append("] [")
            items.take(maxArrayElements).forEachIndexed { index, element ->
                if (index > 0) append(", ")
                val text = element.display(maxArrayElements, 40)
                append(if (element.string != null) "\"$text\"" else text)
            }
            if ((count ?: 0L) > maxArrayElements) append(", …")
            append(']')
        }
    }
}

/** One key/value pair of the GGUF metadata section. [range] covers the key, type and value. */
data class GgufMetadataEntry(
    val key: String,
    val type: GgufValueType,
    val value: GgufValue,
    val range: ModelByteRange,
)

/**
 * One tensor info record.
 *
 * [dims] are in ggml order: `dims[0]` is the innermost (fastest-varying) dimension, the
 * reverse of the NumPy/PyTorch shape. [dataRange] is absolute, and `null` when the tensor
 * type is unknown (no byte size) or the tensor data section could not be located.
 */
data class GgufTensorInfo(
    val index: Int,
    val name: String,
    val dims: List<Long>,
    val typeId: Int,
    val type: GgmlType?,
    /** Offset relative to the start of the tensor data section, as stored. */
    val relativeOffset: Long,
    val elementCount: Long?,
    val dataSize: Long?,
    val dataRange: ModelByteRange?,
    val infoRange: ModelByteRange,
) {
    val typeLabel: String get() = type?.label ?: "type $typeId"
}

/** A parsed GGUF file: header, metadata and tensor infos. Tensor data is not read. */
data class GgufFile(
    val version: Int,
    val bigEndian: Boolean,
    /** Tensor count the header declares; [tensors] can be shorter if the file is truncated. */
    val declaredTensorCount: Long,
    /** Metadata entry count the header declares. */
    val declaredMetadataCount: Long,
    val metadata: List<GgufMetadataEntry>,
    val tensors: List<GgufTensorInfo>,
    val alignment: Int,
    /** Magic, version and the two counts. */
    val headerRange: ModelByteRange,
    val metadataRange: ModelByteRange?,
    val tensorInfoRange: ModelByteRange?,
    /** Absolute offset of the tensor data section, or `null` when the infos could not all be read. */
    val tensorDataOffset: Long?,
    val fileSize: Long,
    val diagnostics: List<ModelDiagnostic>,
) {
    /** Value of the first entry named [key]. */
    fun metadataValue(key: String): GgufValue? = metadata.firstOrNull { it.key == key }?.value

    fun metadataString(key: String): String? = metadataValue(key)?.string

    fun metadataInteger(key: String): Long? = metadataValue(key)?.integer

    /** `general.architecture`, e.g. `llama`, `qwen2`, `gemma3`. */
    val architecture: String? get() = metadataString("general.architecture")

    /** `general.name`, the model's display name. */
    val name: String? get() = metadataString("general.name")

    /** Label of `general.file_type`, e.g. `Q4_K_M`. */
    val fileTypeLabel: String? get() = metadataInteger("general.file_type")?.let(::ggufFileTypeLabel)

    /** Total element count over all tensors, i.e. the parameter count; `null` if any count overflows. */
    val parameterCount: Long? by lazy { sumOrNull(tensors.map { it.elementCount }) }

    /** Total tensor bytes, or `null` when any tensor's size is unknown. */
    val tensorDataSize: Long? by lazy { sumOrNull(tensors.map { it.dataSize }) }
}

internal fun sumOrNull(values: List<Long?>): Long? {
    var total = 0L
    for (value in values) {
        if (value == null || value > Long.MAX_VALUE - total) return null
        total += value
    }
    return total
}
