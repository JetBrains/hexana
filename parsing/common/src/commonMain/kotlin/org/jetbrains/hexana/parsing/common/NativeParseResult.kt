package org.jetbrains.hexana.parsing.common

sealed interface NativeParseResult<out T> {
    val diagnostics: List<NativeParserDiagnostic>
    val valueOrNull: T?
}

data class NativeParseSuccess<T>(
    val value: T,
    override val diagnostics: List<NativeParserDiagnostic> = emptyList(),
) : NativeParseResult<T> {
    override val valueOrNull: T = value
}

data class NativeParseFailure(
    override val diagnostics: List<NativeParserDiagnostic>,
) : NativeParseResult<Nothing> {
    init {
        require(diagnostics.isNotEmpty()) { "failure must contain at least one diagnostic" }
    }

    override val valueOrNull: Nothing? = null
}

fun <T> nativeParseSuccess(
    value: T,
    diagnostics: List<NativeParserDiagnostic> = emptyList(),
): NativeParseSuccess<T> =
    NativeParseSuccess(value, diagnostics)

fun nativeParseFailure(
    code: String,
    message: String,
    range: NativeByteRange? = null,
): NativeParseFailure =
    NativeParseFailure(
        listOf(
            NativeParserDiagnostic(
                code = code,
                message = message,
                range = range,
                severity = NativeParserSeverity.Error,
            ),
        ),
    )
