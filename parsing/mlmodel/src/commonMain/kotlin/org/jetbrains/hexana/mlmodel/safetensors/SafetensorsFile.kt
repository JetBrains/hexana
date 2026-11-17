package org.jetbrains.hexana.mlmodel.safetensors

import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic

/**
 * One tensor entry of a safetensors header.
 *
 * [shape] is in the usual row-major order (outermost first). [begin]/[end] are the
 * `data_offsets` as stored, relative to the start of the byte buffer; [dataRange] is the
 * absolute range, `null` when the offsets are invalid or run past the end of the file.
 */
data class SafetensorsTensor(
    val index: Int,
    val name: String,
    val dtype: String,
    val shape: List<Long>,
    val begin: Long,
    val end: Long,
    val elementCount: Long?,
    val dataRange: ModelByteRange?,
) {
    val dataSize: Long get() = (end - begin).coerceAtLeast(0)

    /** Bits per element for [dtype], or `null` for a dtype this parser does not size. */
    val elementBits: Int? get() = safetensorsDtypeBits(dtype)
}

/** One `__metadata__` entry. The format requires string values; any other JSON is kept as its text. */
data class SafetensorsMetadataEntry(val key: String, val value: String)

/** A parsed safetensors file: the JSON header. Tensor data is not read. */
data class SafetensorsFile(
    /** The `u64` header length at offset 0. */
    val headerSize: Long,
    /** The 8-byte length prefix. */
    val lengthRange: ModelByteRange,
    /** The JSON header, padding included. */
    val headerRange: ModelByteRange,
    /** `__metadata__`, in file order. */
    val metadata: List<SafetensorsMetadataEntry>,
    /** Tensors ordered by [SafetensorsTensor.begin], then name. */
    val tensors: List<SafetensorsTensor>,
    /** Absolute offset of the byte buffer (`8 + headerSize`). */
    val dataOffset: Long,
    val fileSize: Long,
    val diagnostics: List<ModelDiagnostic>,
) {
    val parameterCount: Long? by lazy {
        var total = 0L
        for (tensor in tensors) {
            val count = tensor.elementCount ?: return@lazy null
            if (count > Long.MAX_VALUE - total) return@lazy null
            total += count
        }
        total
    }

    val tensorDataSize: Long by lazy { tensors.sumOf { it.dataSize } }

    /** `format` from `__metadata__` (`pt`, `tf`, `flax`, `mlx`, …), if recorded. */
    val formatTag: String? get() = metadata.firstOrNull { it.key == "format" }?.value
}

/**
 * Bits per element of a safetensors dtype (the `Dtype` enum of the `safetensors` crate),
 * or `null` for one this table does not know.
 */
fun safetensorsDtypeBits(dtype: String): Int? = when (dtype) {
    "BOOL", "U8", "I8", "F8_E5M2", "F8_E4M3", "F8_E8M0" -> 8
    "F4" -> 4
    "F6_E2M3", "F6_E3M2" -> 6
    "I16", "U16", "F16", "BF16" -> 16
    "I32", "U32", "F32" -> 32
    "I64", "U64", "F64", "C64" -> 64
    else -> null
}
