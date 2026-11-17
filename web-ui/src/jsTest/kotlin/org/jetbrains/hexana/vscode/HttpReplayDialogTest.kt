package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.pcap.HttpHeader
import org.jetbrains.hexana.pcap.HttpReplay
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the VS Code replay dialog turns its fields into. The body rule is the
 * one that matters: a textarea hands back `\n` where the capture had `\r\n`,
 * and replaying an untouched body must still send the captured bytes.
 */
class HttpReplayDialogTest {

    private val captured = HttpReplay.parseRequest(
        "POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 20\r\n\r\nline one\r\nline two".encodeToByteArray(),
        "http", "10.0.0.2", 80,
    )!!

    @Test
    fun untouchedBodyIsSentAsCaptured() {
        val text = HttpReplay.bodyAsText(captured.body)!!
        val request = editedReplayRequest(captured, text, "post", " http://h/b ", "Accept: */*", text.replace("\r\n", "\n"))
        assertEquals("POST", request.method)
        assertEquals("http://h/b", request.url)
        assertEquals(listOf(HttpHeader("Accept", "*/*")), request.headers)
        assertContentEquals(captured.body, request.body)
        assertFalse(request.bodyComplete)
    }

    @Test
    fun editedBodyReplacesTheCapture() {
        val text = HttpReplay.bodyAsText(captured.body)!!
        val request = editedReplayRequest(captured, text, "PUT", "https://h/", "", "new")
        assertEquals("new", request.body.decodeToString())
        assertTrue(request.bodyComplete)
    }

    @Test
    fun rejectsWhatCannotBeSent() {
        assertFailsWith<IllegalArgumentException> { editedReplayRequest(captured, null, "", "http://h/", "", "") }
        assertFailsWith<IllegalArgumentException> { editedReplayRequest(captured, null, "GET", "ftp://h/", "", "") }
        assertFailsWith<IllegalArgumentException> { editedReplayRequest(captured, null, "GET", "http://h/", "nope", "") }
    }

    @Test
    fun formatsResponse() {
        val shown = formatReplayResponse(
            HttpReplayResponse(200, "OK", listOf(HttpHeader("content-type", "text/plain")), "hi".encodeToByteArray(), 3),
        )
        assertEquals("content-type: text/plain\n\nhi", shown)
        assertEquals(
            "\n(binary body, 2 bytes)",
            formatReplayResponse(HttpReplayResponse(200, "", emptyList(), byteArrayOf(0, 1), 0)),
        )
    }
}
