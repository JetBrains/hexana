package org.jetbrains.hexana.parsing.common

enum class NativeParserSeverity {
    Info,
    Warning,
    Error,
}

data class NativeParserDiagnostic(
    val code: String,
    val message: String,
    val range: NativeByteRange? = null,
    val severity: NativeParserSeverity = NativeParserSeverity.Error,
)
