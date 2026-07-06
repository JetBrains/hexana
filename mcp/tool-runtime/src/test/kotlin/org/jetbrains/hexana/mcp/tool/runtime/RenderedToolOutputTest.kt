package org.jetbrains.hexana.mcp.tool.runtime

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenderedToolOutputTest {
    @Test
    fun textOutputRequiresUtf8ContentType() {
        val output = RenderedToolOutput.Text("ok", "application/json; charset=utf-8")

        assertEquals("ok", output.text)
        assertEquals("application/json; charset=utf-8", output.contentType)
        RenderedToolOutput.Text("ok", "text/plain; charset=\"utf-8\"")
        RenderedToolOutput.Text("ok", "text/plain; charset = UTF-8; profile=plain")
        assertFailsWith<IllegalArgumentException> {
            RenderedToolOutput.Text("ok", "text/plain")
        }
    }

    @Test
    fun binaryOutputCopiesBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        val output = RenderedToolOutput.Binary(bytes, "application/octet-stream")

        bytes[0] = 9
        val firstRead = output.bytes()
        firstRead[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), output.bytes())
    }

    @Test
    fun renderedOutputRequiresMediaTypeContentType() {
        assertFailsWith<IllegalArgumentException> {
            RenderedToolOutput.Binary(byteArrayOf(1), "binary")
        }
        assertFailsWith<IllegalArgumentException> {
            RenderedToolOutput.Text("ok", " ")
        }
    }
}
