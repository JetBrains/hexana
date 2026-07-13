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
    fun executorCarriesSemanticArgumentsAndResultWithoutRuntimeDependencies() {
        val context = ToolRuntimeContext(toolName = "inspect")
        val arguments = JsonObject(mapOf("path" to JsonPrimitive("module.wasm")))
        val sources = ToolInputSources(
            sourceSelections = listOf(ToolInputSourceSelection.Path("module.wasm", "input"))
        )
        val semanticResult = JsonObject(mapOf("format" to JsonPrimitive("wasm")))
        var received: ToolExecutionRequest? = null
        val executor = ToolExecutor { request ->
            received = request
            ToolExecutionResult(semanticResult)
        }

        val result = executor.execute(ToolExecutionRequest(context, arguments, sources))

        assertEquals(context, received?.context)
        assertEquals(arguments, received?.arguments)
        assertEquals(sources, received?.sources)
        assertEquals(semanticResult, result.semanticResult)
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

    @Test
    fun pathSourceStoresPathStringWithoutResolvingFiles() {
        val source = ToolInputSourceSelection.Path(
            path = "../missing/module.wasm",
            slotName = "input",
        )

        assertEquals("../missing/module.wasm", source.path)
        assertEquals("input", source.slotName)
        assertEquals(ToolInputSourceMcpPayloadField.Path, source.mcpPayloadField)
        assertEquals("path", source.mcpPayloadFieldName)
    }

    @Test
    fun stdinByteSourceCopiesBytesAndRedactsPayload() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = ToolInputSourceSelection.StdinBytes(
            bytes = bytes,
            slotName = "input",
        )
        bytes[0] = 9

        val firstRead = source.bytes()
        firstRead[1] = 8

        assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
        assertEquals(ToolInputSourceMcpPayloadField.Bytes, source.mcpPayloadField)
        assertEquals("bytes", source.mcpPayloadFieldName)
        assertFalse(source.toString().contains("[1, 2, 3]"))
        assertTrue(source.toString().contains("byteSize=3"))
    }

    @Test
    fun base64SourceStoresStringWithoutDecodingAndRedactsPayload() {
        val payload = "not valid base64!"
        val source = ToolInputSourceSelection.Base64(
            bytesBase64 = payload,
            slotName = "input",
        )

        assertEquals(payload, source.bytesBase64)
        assertEquals(ToolInputSourceMcpPayloadField.BytesBase64, source.mcpPayloadField)
        assertEquals("bytesBase64", source.mcpPayloadFieldName)
        assertFalse(source.toString().contains(payload))
        assertTrue(source.toString().contains("bytesBase64Length=${payload.length}"))
    }

    @Test
    fun byteListSourceStoresMcpBytesAndRedactsPayload() {
        val bytes = mutableListOf(1, 2, 3)
        val source = ToolInputSourceSelection.ByteList(
            bytes = bytes,
            slotName = "input",
        )
        bytes[0] = 9

        assertEquals(listOf(1, 2, 3), source.bytes)
        assertEquals(ToolInputSourceMcpPayloadField.Bytes, source.mcpPayloadField)
        assertEquals("bytes", source.mcpPayloadFieldName)
        assertFalse(source.toString().contains("[1, 2, 3]"))
        assertTrue(source.toString().contains("byteCount=3"))
        assertFailsWith<IllegalArgumentException> {
            ToolInputSourceSelection.ByteList(bytes = listOf(0, 256), slotName = "input")
        }
    }

    @Test
    fun inputSourcesRepresentMultipleNamedSourceSelections() {
        val selections = mutableListOf<ToolInputSourceSelection>(
            ToolInputSourceSelection.Path(path = "current.wasm", slotName = "current"),
            ToolInputSourceSelection.Base64(bytesBase64 = "AA==", slotName = "baseline"),
        )
        val sources = ToolInputSources(sourceSelections = selections)
        selections += ToolInputSourceSelection.Path(path = "ignored.wasm", slotName = "ignored")

        assertEquals(listOf("current", "baseline"), sources.sourceSelections.map { it.slotName })
        assertEquals(listOf("path", "bytesBase64"), sources.sourceSelections.map { it.mcpPayloadFieldName })
    }

    @Test
    fun inputSourcesRejectDuplicatePathAndSelectionNames() {
        val error = assertFailsWith<IllegalArgumentException> {
            ToolInputSources(
                pathArguments = mapOf("input" to "module.wasm"),
                sourceSelections = listOf(
                    ToolInputSourceSelection.Base64(bytesBase64 = "AA==", slotName = "input"),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("input"))
    }

    @Test
    fun sourceNamesMustNotBeBlank() {
        assertFailsWith<IllegalArgumentException> {
            ToolInputSourceSelection.Path(path = "module.wasm", slotName = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            ToolInputSources(pathArguments = mapOf(" " to "module.wasm"))
        }
    }

    @Test
    fun sourcePayloadFactoryRejectsConflictingForms() {
        assertFailsWith<IllegalArgumentException> {
            ToolInputSourceSelection.fromPayload(
                slotName = "input",
                path = "module.wasm",
                bytesBase64 = "AA==",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ToolInputSourceSelection.fromPayload(slotName = "input")
        }
    }
}
