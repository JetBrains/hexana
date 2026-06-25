package org.jetbrains.hexana.mcp

object HexanaAnalysisLimits {
    const val MAX_INLINE_WASM_BYTES: Int = 100 * 1024 * 1024
    const val MAX_FILE_WASM_BYTES: Int = 100 * 1024 * 1024
    const val MAX_INLINE_BINARY_BYTES: Int = MAX_INLINE_WASM_BYTES
    const val MAX_FILE_BINARY_BYTES: Int = MAX_FILE_WASM_BYTES
}
