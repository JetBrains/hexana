package org.jetbrains.hexana.parsing.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeParseResultTest {
    @Test
    fun `success exposes parsed value and diagnostics`() {
        val warning = NativeParserDiagnostic(
            code = "native.test.warning",
            message = "test warning",
            severity = NativeParserSeverity.Warning,
        )

        val result = nativeParseSuccess("value", listOf(warning))

        assertEquals("value", result.valueOrNull)
        assertEquals(listOf(warning), result.diagnostics)
    }

    @Test
    fun `failure requires diagnostics`() {
        val failure = nativeParseFailure("native.test.error", "test error")

        assertNull(failure.valueOrNull)
        assertEquals("native.test.error", failure.diagnostics.single().code)
        assertFailsWith<IllegalArgumentException> {
            NativeParseFailure(emptyList())
        }
    }
}
