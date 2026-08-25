package org.jetbrains.hexana.firmware.debug

import com.intellij.testFramework.UsefulTestCase
import kotlinx.serialization.json.*

/**
 * Unit tests for [GdbDapClient] DAP framing (encode/decode).
 *
 * These tests do NOT start a process. They verify the Content-Length framing used to
 * communicate with `gdb -i=dap` in both directions.
 */
class GdbDapFramingTest : UsefulTestCase() {

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    fun testEncodeFrameHasContentLengthHeader() {
        val json = buildJsonObject { put("seq", 1); put("type", "request"); put("command", "initialize") }
        val bytes = GdbDapClient.encodeFrameStatic(json)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue("encoded frame must start with Content-Length:", text.startsWith("Content-Length:"))
        assertTrue("header must be separated by CRLFCRLF", text.contains("\r\n\r\n"))
    }

    fun testEncodeFrameBodyLengthMatchesHeader() {
        val json = buildJsonObject { put("seq", 42); put("type", "request"); put("command", "attach") }
        val bytes = GdbDapClient.encodeFrameStatic(json)
        val text = bytes.toString(Charsets.UTF_8)
        val headerEnd = text.indexOf("\r\n\r\n")
        assertTrue("CRLFCRLF separator missing", headerEnd >= 0)
        val headerLine = text.substring(0, headerEnd)
        val declaredLength = headerLine.substringAfter("Content-Length:").trim().toInt()
        val body = bytes.drop(headerEnd + 4).toByteArray()
        assertEquals("declared Content-Length must match actual body bytes", declaredLength, body.size)
    }

    fun testEncodeFrameBodyIsValidJson() {
        val original = buildJsonObject { put("type", "request"); put("command", "initialize"); put("seq", 7) }
        val bytes = GdbDapClient.encodeFrameStatic(original)
        val text = bytes.toString(Charsets.UTF_8)
        val headerEnd = text.indexOf("\r\n\r\n")
        val bodyText = text.substring(headerEnd + 4)
        val decoded = Json.parseToJsonElement(bodyText).jsonObject
        assertEquals(original, decoded)
    }

    // -------------------------------------------------------------------------
    // Decoding — single complete frame
    // -------------------------------------------------------------------------

    fun testDecodeSingleFrame() {
        val json = buildJsonObject {
            put("seq", 1)
            put("type", "response")
            put("command", "initialize")
            put("success", true)
        }
        val bytes = GdbDapClient.encodeFrameStatic(json)
        val frames = GdbDapClient.decodeFrames(bytes)
        assertEquals("expected exactly one frame", 1, frames.size)
        assertEquals(json, frames[0])
    }

    // -------------------------------------------------------------------------
    // Decoding — two frames concatenated
    // -------------------------------------------------------------------------

    fun testDecodeTwoConcatenatedFrames() {
        val frame1 = buildJsonObject { put("seq", 1); put("type", "response"); put("command", "initialize") }
        val frame2 = buildJsonObject { put("seq", 1); put("type", "event"); put("event", "initialized") }
        val bytes = GdbDapClient.encodeFrameStatic(frame1) + GdbDapClient.encodeFrameStatic(frame2)
        val frames = GdbDapClient.decodeFrames(bytes)
        assertEquals("expected two frames", 2, frames.size)
        assertEquals(frame1, frames[0])
        assertEquals(frame2, frames[1])
    }

    // -------------------------------------------------------------------------
    // Decoding — partial / split read (incomplete frame returns empty)
    // -------------------------------------------------------------------------

    fun testDecodeIncompleteFrameReturnsEmpty() {
        val json = buildJsonObject { put("seq", 1); put("type", "request"); put("command", "attach") }
        val full = GdbDapClient.encodeFrameStatic(json)
        // Only send the first half of the bytes.
        val partial = full.copyOfRange(0, full.size / 2)
        val frames = GdbDapClient.decodeFrames(partial)
        assertTrue("partial frame should yield no complete frames", frames.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Round-trip: encode then decode
    // -------------------------------------------------------------------------

    fun testRoundTrip() {
        val original = buildJsonObject {
            put("seq", 99)
            put("type", "request")
            put("command", "stackTrace")
            putJsonObject("arguments") {
                put("threadId", 1)
                put("startFrame", 0)
                put("levels", 5)
            }
        }
        val encoded = GdbDapClient.encodeFrameStatic(original)
        val decoded = GdbDapClient.decodeFrames(encoded)
        assertEquals(1, decoded.size)
        assertEquals(original, decoded[0])
    }

    // -------------------------------------------------------------------------
    // Unicode / non-ASCII content
    // -------------------------------------------------------------------------

    fun testEncodeDecodeUnicode() {
        val json = buildJsonObject { put("message", "Ü äÖ テスト") }
        val encoded = GdbDapClient.encodeFrameStatic(json)
        val decoded = GdbDapClient.decodeFrames(encoded)
        assertEquals(1, decoded.size)
        assertEquals("Ü äÖ テスト", decoded[0]["message"]?.jsonPrimitive?.content)
    }
}
