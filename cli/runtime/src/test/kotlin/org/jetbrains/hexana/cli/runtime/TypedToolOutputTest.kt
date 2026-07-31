package org.jetbrains.hexana.cli.runtime

import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypedToolOutputTest {
    @Test
    fun planOwnsSerializerAndPassesRenderOptionsToTypedProjection() {
        var receivedOptions: ToolRenderOptions? = null
        val plan = TypedToolOutputPlan(
            serializer = String.serializer(),
            projections = listOf(
                TypedToolOutputProjection(ToolOutputFormat.PRETTY) { value, options ->
                    receivedOptions = options
                    TypedToolOutput.Text("value=$value")
                },
            ),
        )
        val options = ToolRenderOptions(ToolOutputFormat.PRETTY, TerminalColorMode.Never, terminalWidth = 17, includeDiagnostics = true)

        assertEquals(setOf(ToolOutputFormat.JSON, ToolOutputFormat.PRETTY), plan.supportedFormats)
        assertEquals(TypedToolOutput.Text("value=7"), plan.projection(ToolOutputFormat.PRETTY).project("7", options))
        assertEquals(options, receivedOptions)
        assertFailsWith<IllegalArgumentException> { plan.projection(ToolOutputFormat.RAW) }
    }

    @Test
    fun tableSnapshotIsCallerImmutableAndRejectsInvalidCells() {
        val headers = mutableListOf("Name", "Count")
        val row = mutableListOf("wasm", "2")
        val table = CliTable(headers, listOf(row))
        headers[0] = "Mutated"
        row[0] = "changed"

        assertEquals(listOf("Name", "Count"), table.headers)
        assertEquals(listOf(listOf("wasm", "2")), table.rows)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (table.headers as MutableList<String>).add("Unexpected")
        }
        assertFailsWith<IllegalArgumentException> { CliTable(listOf("Name"), listOf(listOf("line\nbreak"))) }
    }
}
