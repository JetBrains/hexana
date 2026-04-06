package org.jetbrains.hexana.mcp

enum class HexanaAnalysisErrorCode {
    INVALID_INPUT,
    FILE_NOT_FOUND,
    UNSUPPORTED_INPUT_KIND,
    NOT_WASM,
    WASM_PARSE_FAILED,
    OUT_OF_RANGE,
    INTERNAL_ERROR
}

class HexanaAnalysisException(
    val code: HexanaAnalysisErrorCode,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
