package org.jetbrains.hexana.cli.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TypedToolOutputTest {
    @Test
    fun jsonBackedPlanDecodesWithSerializerAndRendersEachTypedProjection() {
        val plan = TypedToolOutputPlan(
            serializer = String.serializer(),
            projections = listOf(
                JsonToolOutputProjection(String.serializer(), Json::encodeToString),
                PrettyToolOutputProjection { "value=$it" },
                TableToolOutputProjection { CliTable(listOf("Value"), listOf(listOf(it))) },
            ),
        )
        val renderer = JsonBackedTypedToolOutputRenderer("sample", Json::decodeFromJsonElement, plan)
        val request = ToolOutputRenderRequest(
            context = ToolRuntimeContext("sample"),
            arguments = kotlinx.serialization.json.JsonObject(emptyMap()),
            result = Json.encodeToJsonElement("7"),
        )

        assertEquals(setOf(ToolOutputFormat.JSON, ToolOutputFormat.PRETTY, ToolOutputFormat.TABLE), renderer.supportedFormats)
        assertEquals("\"7\"", assertIs<RenderedToolOutput.Text>(renderer.render(request)).text)
        assertEquals("value=7", assertIs<RenderedToolOutput.Text>(renderer.render(request.copy(options = ToolRenderOptions(ToolOutputFormat.PRETTY)))).text)
        assertEquals("Value\n-----\n7", assertIs<RenderedToolOutput.Text>(renderer.render(request.copy(options = ToolRenderOptions(ToolOutputFormat.TABLE)))).text)
        assertFailsWith<IllegalArgumentException> { renderer.render(request.copy(options = ToolRenderOptions(ToolOutputFormat.RAW))) }
    }

    @Test
    fun tableRendererAlignsPlainTextCellsAndRejectsMultilineCells() {
        assertEquals("Name | Count\n-----+------\nwasm | 2", CliTableRenderer.render(CliTable(listOf("Name", "Count"), listOf(listOf("wasm", "2")))))
        assertFailsWith<IllegalArgumentException> { CliTable(listOf("Name"), listOf(listOf("line\nbreak"))) }
    }
}
