package org.jetbrains.hexana.mcp.tool.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRuntimeContractsTest {
    @Test
    fun renderOptionsRejectInvalidTerminalWidth() {
        assertFailsWith<IllegalArgumentException> {
            ToolRenderOptions(terminalWidth = 0)
        }
    }

    @Test
    fun runtimeContextRequiresToolName() {
        assertFailsWith<IllegalArgumentException> {
            ToolRuntimeContext(toolName = " ")
        }
    }

    @Test
    fun inputSourcesModelPathAndStdinContext() {
        val stdinBytes = byteArrayOf(4, 5, 6)
        val sources = ToolInputSources(
            stdinBytes = stdinBytes,
            pathArguments = mapOf("input" to "module.wasm"),
        )
        stdinBytes[0] = 9
        val request = ToolInputConversionRequest(
            context = ToolRuntimeContext(toolName = "inspect"),
            rawArguments = JsonObject(mapOf("format" to JsonPrimitive("wat"))),
            sources = sources,
        )

        assertEquals("module.wasm", request.sources.pathArguments.getValue("input"))
        assertContentEquals(byteArrayOf(4, 5, 6), request.sources.stdinBytesCopy())
        assertFailsWith<IllegalArgumentException> {
            ToolInputSources(stdinText = "wat", stdinBytes = byteArrayOf(0))
        }
    }

    @Test
    fun inputSourcesReturnDefensiveStdinByteCopies() {
        val sources = ToolInputSources(stdinBytes = byteArrayOf(1, 2, 3))

        val firstRead = sources.stdinBytesCopy()
        firstRead!![0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), sources.stdinBytesCopy())
        assertEquals(ToolInputSources(stdinBytes = byteArrayOf(1, 2, 3)), sources)
    }

    @Test
    fun inputSourcesToStringRedactsStdinPayloads() {
        val textSources = ToolInputSources(
            stdinText = "secret wat",
            pathArguments = mapOf("input" to "module.wasm"),
        )
        val byteSources = ToolInputSources(stdinBytes = byteArrayOf(1, 2, 3))

        assertFalse(textSources.toString().contains("secret wat"))
        assertTrue(textSources.toString().contains("stdinTextLength=10"))
        assertTrue(textSources.toString().contains("stdinBytesSize=null"))
        assertFalse(byteSources.toString().contains("[1, 2, 3]"))
        assertTrue(byteSources.toString().contains("stdinBytesSize=3"))
    }
}
