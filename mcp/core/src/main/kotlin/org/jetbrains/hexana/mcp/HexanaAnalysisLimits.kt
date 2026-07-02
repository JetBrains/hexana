package org.jetbrains.hexana.mcp

object HexanaAnalysisLimits {
    const val MAX_INLINE_WASM_BYTES: Int = 100 * 1024 * 1024
    const val MAX_FILE_WASM_BYTES: Int = 100 * 1024 * 1024
    const val MAX_INLINE_BINARY_BYTES: Int = MAX_INLINE_WASM_BYTES
    const val MAX_FILE_BINARY_BYTES: Int = MAX_FILE_WASM_BYTES
    const val MAX_TEXT_FILE_BYTES: Int = 1_000_000
    const val DEFAULT_QUERY_LIMIT: Int = 25
    const val MAX_QUERY_LIMIT: Int = 100
    const val DEFAULT_QUERY_OUTPUT_BYTES: Int = 24_000
    const val MAX_OUTPUT_BYTES: Int = 60_000
    const val MAX_CANDIDATE_COUNT: Int = 500
    const val MAX_SCAN_ROOTS: Int = 8
    const val MAX_SCAN_DEPTH: Int = 16
}

data class HexanaResourceLimits(
    val maxInlineWasmBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    val maxFileWasmBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    val maxInlineBinaryBytes: Int = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
    val maxFileBinaryBytes: Int = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
    val maxTextFileBytes: Int = HexanaAnalysisLimits.MAX_TEXT_FILE_BYTES,
    val maxOutputBytes: Int = HexanaAnalysisLimits.MAX_OUTPUT_BYTES,
    val maxCandidateCount: Int = HexanaAnalysisLimits.MAX_CANDIDATE_COUNT,
    val maxScanRoots: Int = HexanaAnalysisLimits.MAX_SCAN_ROOTS,
    val maxScanDepth: Int = HexanaAnalysisLimits.MAX_SCAN_DEPTH,
) {
    init {
        requirePositive(maxInlineWasmBytes, "maxInlineWasmBytes")
        requirePositive(maxFileWasmBytes, "maxFileWasmBytes")
        requirePositive(maxInlineBinaryBytes, "maxInlineBinaryBytes")
        requirePositive(maxFileBinaryBytes, "maxFileBinaryBytes")
        requirePositive(maxTextFileBytes, "maxTextFileBytes")
        requirePositive(maxOutputBytes, "maxOutputBytes")
        requirePositive(maxCandidateCount, "maxCandidateCount")
        requirePositive(maxScanRoots, "maxScanRoots")
        requirePositive(maxScanDepth, "maxScanDepth")
    }
}

private fun requirePositive(value: Int, name: String) {
    require(value > 0) { "$name must be greater than 0" }
}
