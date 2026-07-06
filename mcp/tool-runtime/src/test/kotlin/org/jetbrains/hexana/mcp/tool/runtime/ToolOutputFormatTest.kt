package org.jetbrains.hexana.mcp.tool.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolOutputFormatTest {
    @Test
    fun parsesStableCliNames() {
        assertEquals(ToolOutputFormat.Json, ToolOutputFormat.parse("json"))
        assertEquals(ToolOutputFormat.Pretty, ToolOutputFormat.parse("pretty"))
        assertEquals(ToolOutputFormat.Raw, ToolOutputFormat.parse("raw"))
        assertEquals(ToolOutputFormat.Json, ToolOutputFormat.parse(" JSON "))
    }

    @Test
    fun rejectsUnknownCliNames() {
        val error = assertFailsWith<IllegalArgumentException> {
            ToolOutputFormat.parse("yaml")
        }

        assertEquals("Unknown tool output format 'yaml'. Supported formats: json, pretty, raw.", error.message)
    }
}
