package org.jetbrains.hexana.mlmodel.gguf

/**
 * A ggml tensor element type, as stored in a GGUF tensor info record.
 *
 * Ids, block sizes and block byte sizes follow `ggml_type` and `ggml_type_traits` in
 * ggml's `ggml.c`. Quantized types store [blockSize] elements in [typeSize] bytes; plain
 * types have a block size of 1. Ids ggml retired (4, 5, 31–33, 36–38) are absent, so a
 * tensor using one resolves to `null` and is shown by its number.
 */
enum class GgmlType(val id: Int, val label: String, val blockSize: Int, val typeSize: Int) {
    F32(0, "F32", 1, 4),
    F16(1, "F16", 1, 2),
    Q4_0(2, "Q4_0", 32, 18),
    Q4_1(3, "Q4_1", 32, 20),
    Q5_0(6, "Q5_0", 32, 22),
    Q5_1(7, "Q5_1", 32, 24),
    Q8_0(8, "Q8_0", 32, 34),
    Q8_1(9, "Q8_1", 32, 36),
    Q2_K(10, "Q2_K", 256, 84),
    Q3_K(11, "Q3_K", 256, 110),
    Q4_K(12, "Q4_K", 256, 144),
    Q5_K(13, "Q5_K", 256, 176),
    Q6_K(14, "Q6_K", 256, 210),
    Q8_K(15, "Q8_K", 256, 292),
    IQ2_XXS(16, "IQ2_XXS", 256, 66),
    IQ2_XS(17, "IQ2_XS", 256, 74),
    IQ3_XXS(18, "IQ3_XXS", 256, 98),
    IQ1_S(19, "IQ1_S", 256, 50),
    IQ4_NL(20, "IQ4_NL", 32, 18),
    IQ3_S(21, "IQ3_S", 256, 110),
    IQ2_S(22, "IQ2_S", 256, 82),
    IQ4_XS(23, "IQ4_XS", 256, 136),
    I8(24, "I8", 1, 1),
    I16(25, "I16", 1, 2),
    I32(26, "I32", 1, 4),
    I64(27, "I64", 1, 8),
    F64(28, "F64", 1, 8),
    IQ1_M(29, "IQ1_M", 256, 56),
    BF16(30, "BF16", 1, 2),
    TQ1_0(34, "TQ1_0", 256, 54),
    TQ2_0(35, "TQ2_0", 256, 66),
    MXFP4(39, "MXFP4", 32, 17),
    ;

    /**
     * Bytes [elementCount] elements of this type occupy, or `null` when the count is not a
     * whole number of blocks (ggml requires the first dimension to be a multiple of the
     * block size) or the result overflows.
     */
    fun byteSize(elementCount: Long): Long? {
        if (elementCount < 0 || elementCount % blockSize != 0L) return null
        val blocks = elementCount / blockSize
        if (blocks > Long.MAX_VALUE / typeSize) return null
        return blocks * typeSize
    }

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: Int): GgmlType? = byId[id]
    }
}

/**
 * Label for `general.file_type` (llama.cpp's `llama_ftype`): the quantization recipe the
 * whole file was produced with, e.g. `Q4_K_M`. `null` for a value this table does not know.
 */
fun ggufFileTypeLabel(fileType: Long): String? = when (fileType) {
    0L -> "ALL_F32"
    1L -> "F16"
    2L -> "Q4_0"
    3L -> "Q4_1"
    7L -> "Q8_0"
    8L -> "Q5_0"
    9L -> "Q5_1"
    10L -> "Q2_K"
    11L -> "Q3_K_S"
    12L -> "Q3_K_M"
    13L -> "Q3_K_L"
    14L -> "Q4_K_S"
    15L -> "Q4_K_M"
    16L -> "Q5_K_S"
    17L -> "Q5_K_M"
    18L -> "Q6_K"
    19L -> "IQ2_XXS"
    20L -> "IQ2_XS"
    21L -> "Q2_K_S"
    22L -> "IQ3_XS"
    23L -> "IQ3_XXS"
    24L -> "IQ1_S"
    25L -> "IQ4_NL"
    26L -> "IQ3_S"
    27L -> "IQ3_M"
    28L -> "IQ2_S"
    29L -> "IQ2_M"
    30L -> "IQ4_XS"
    31L -> "IQ1_M"
    32L -> "BF16"
    36L -> "TQ1_0"
    37L -> "TQ2_0"
    38L -> "MXFP4_MOE"
    else -> null
}
