package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The detail panel is the only place decrypted plaintext can be read - those
 * bytes are nowhere in the file for the hex view to point at - so how it renders
 * them is worth pinning down.
 */
class RowDetailFormatTest {

    @Test
    fun `text mode keeps the line structure of a protocol head`() {
        val head = "GET / HTTP/1.0\r\nHost: example\r\n\r\n"
        assertEquals(head, rowDetailText(head.encodeToByteArray()))
    }

    @Test
    fun `text mode dots control bytes but not the whitespace heads are made of`() {
        val bytes = byteArrayOf(0x41, 0x00, 0x09, 0x0D, 0x0A, 0x7F, 0x42)
        assertEquals("A.\t\r\n.B", rowDetailText(bytes))
    }

    @Test
    fun `a hex dump line carries the offset, sixteen bytes and their ascii`() {
        val dump = rowDetailHexDump("GET / HTTP/1.0\r\nX".encodeToByteArray())
        val lines = dump.split("\n")
        assertEquals(2, lines.size)
        assertEquals(
            "00000000  47 45 54 20 2f 20 48 54  54 50 2f 31 2e 30 0d 0a  |GET / HTTP/1.0..|",
            lines[0],
        )
        // The short last line pads the hex columns so the ascii gutter stays put.
        assertEquals(lines[0].indexOf('|'), lines[1].indexOf('|'))
        assertTrue(lines[1].startsWith("00000010  58 "), lines[1])
        assertTrue(lines[1].endsWith("|X|"), lines[1])
    }

    @Test
    fun `an empty payload dumps to nothing rather than a stray line`() {
        assertEquals("", rowDetailHexDump(ByteArray(0)))
        assertEquals("", rowDetailText(ByteArray(0)))
    }

    /**
     * A trimmed payload must never read as the whole thing: the panel says how
     * many bytes it left out, in either mode.
     */
    @Test
    fun `beyond the limit both modes name the bytes they left out`() {
        val bytes = ByteArray(ROW_DETAIL_BYTE_LIMIT + 5) { 0x41 }
        assertContains(rowDetailText(bytes), "... 5 more bytes not shown")
        assertContains(rowDetailHexDump(bytes), "... 5 more bytes not shown")
        // Exactly at the limit there is nothing to say.
        val exact = ByteArray(ROW_DETAIL_BYTE_LIMIT) { 0x41 }
        assertFalse(rowDetailText(exact).contains("more bytes"))
        assertFalse(rowDetailHexDump(exact).contains("more bytes"))
    }

    @Test
    fun `a payload is rendered up to the limit and no further`() {
        val bytes = ByteArray(20) { 0x41 }
        assertEquals(8, rowDetailText(bytes, limit = 8).substringBefore("\n\n").length)
    }

    @Test
    fun `text mode is chosen for a protocol head and hex for binary`() {
        assertTrue(looksLikeText("HTTP/1.0 200 ok\r\n\r\n<html/>".encodeToByteArray()))
        assertFalse(looksLikeText(ByteArray(64) { (it * 7).toByte() }))
        // Nothing to show reads as nothing to read as text either.
        assertFalse(looksLikeText(ByteArray(0)))
    }

    /**
     * A short text head in front of a binary body opens in hex, where the head
     * is still readable in the ascii gutter - and Text is one click away.
     */
    @Test
    fun `a text head with a binary body reads as binary`() {
        val head = "HTTP/1.0 200 ok\r\nContent-type: image/png\r\n\r\n".encodeToByteArray()
        val body = ByteArray(4096) { 0x80.toByte() }
        assertFalse(looksLikeText(head + body))
        // The ascii gutter keeps the head legible in that mode.
        assertContains(rowDetailHexDump(head + body), "|HTTP/1.0 200 ok.")
    }
}
