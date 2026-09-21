package org.jetbrains.hexana.cli.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolOutputFormatTest {
    @Test
    fun parsesStableCliNames() {
        assertEquals(ToolOutputFormat.JSON, ToolOutputFormat.parse("json"))
        assertEquals(ToolOutputFormat.PRETTY, ToolOutputFormat.parse("pretty"))
        assertEquals(ToolOutputFormat.TABLE, ToolOutputFormat.parse("table"))
        assertEquals(ToolOutputFormat.RAW, ToolOutputFormat.parse("raw"))
        assertEquals(ToolOutputFormat.SARIF, ToolOutputFormat.parse("sarif"))
        assertEquals(ToolOutputFormat.JSON, ToolOutputFormat.parse(" JSON "))
    }

    @Test
    fun rejectsUnknownCliNames() {
        val error = assertFailsWith<IllegalArgumentException> {
            ToolOutputFormat.parse("yaml")
        }

        assertEquals("Unknown tool output format 'yaml'. Supported formats: json, pretty, raw, sarif, table.", error.message)
    }
}
