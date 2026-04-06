package org.jetbrains.hexana.mcp

sealed interface WasmInput {
    data class FilePath(val path: String) : WasmInput
    data class Uri(val uri: String) : WasmInput
    data class InlineBytes(val base64: String) : WasmInput
}
