package org.jetbrains.hexana.pcap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SslKeyLogTest {

    @Test
    fun parsesClientRandomEntry() {
        val content = "CLIENT_RANDOM " +
            "aabbccdd" + "00112233" + "44556677" + "8899aabb" +
            "ccddeeff" + "00112233" + "44556677" + "8899aabb" +
            " " +
            "00112233445566778899aabbccddeeff" +
            "00112233445566778899aabbccddeeff" +
            "00112233445566778899aabbccddeeff"
        val entries = SslKeyLogParser.parse(content)
        assertEquals(1, entries.size)
        assertEquals("CLIENT_RANDOM", entries[0].label)
        assertEquals(32, entries[0].clientRandom.size)
        assertEquals(48, entries[0].secret.size)
    }

    @Test
    fun parsesMultipleEntries() {
        val cr = "aa".repeat(32)
        val secret = "bb".repeat(48)
        val content = """
            # Comment line
            CLIENT_RANDOM $cr $secret
            CLIENT_HANDSHAKE_TRAFFIC_SECRET $cr $secret
            SERVER_HANDSHAKE_TRAFFIC_SECRET $cr $secret
            CLIENT_TRAFFIC_SECRET_0 $cr $secret
            SERVER_TRAFFIC_SECRET_0 $cr $secret
        """.trimIndent()
        val entries = SslKeyLogParser.parse(content)
        assertEquals(5, entries.size)
    }

    @Test
    fun skipsBlankAndCommentLines() {
        val content = """
            # This is a comment
            
            # Another comment
        """.trimIndent()
        val entries = SslKeyLogParser.parse(content)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun skipsUnknownLabels() {
        val cr = "aa".repeat(32)
        val secret = "bb".repeat(48)
        val content = "UNKNOWN_LABEL $cr $secret"
        val entries = SslKeyLogParser.parse(content)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun skipsInvalidHex() {
        val content = "CLIENT_RANDOM ZZZZ ${"bb".repeat(48)}"
        val entries = SslKeyLogParser.parse(content)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun skipsWrongClientRandomLength() {
        val cr = "aa".repeat(16) // only 16 bytes, need 32
        val secret = "bb".repeat(48)
        val content = "CLIENT_RANDOM $cr $secret"
        val entries = SslKeyLogParser.parse(content)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun sslKeyLogLookup() {
        val cr1 = "aa".repeat(32)
        val cr2 = "bb".repeat(32)
        val secret = "cc".repeat(48)
        val content = """
            CLIENT_RANDOM $cr1 $secret
            CLIENT_TRAFFIC_SECRET_0 $cr1 $secret
            CLIENT_RANDOM $cr2 $secret
        """.trimIndent()
        val keyLog = SslKeyLog.parse(content)
        assertTrue(keyLog.isNotEmpty)
        assertEquals(2, keyLog.sessionCount)
        assertEquals(2, keyLog.lookup(cr1).size)
        assertEquals(1, keyLog.lookup(cr2).size)
        assertTrue(keyLog.lookup("ff".repeat(32)).isEmpty())
    }

    @Test
    fun emptyKeyLog() {
        assertFalse(SslKeyLog.EMPTY.isNotEmpty)
        assertEquals(0, SslKeyLog.EMPTY.sessionCount)
    }
}
