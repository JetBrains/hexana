package org.jetbrains.hexana.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiblingKeyLogCandidateTest {

    @Test
    fun `well-known name is picked out of unrelated siblings`() {
        val children = listOf("notes.md", "sslkeylog.txt", "capture.pcap")
        assertEquals("sslkeylog.txt", siblingKeyLogCandidate(children, "capture"))
    }

    @Test
    fun `well-known names win in declared order`() {
        // premaster.txt sorts first alphabetically but is last in the list.
        val children = listOf("premaster.txt", "keylog.txt", "sslkeylog.txt")
        assertEquals("sslkeylog.txt", siblingKeyLogCandidate(children, "capture"))
        assertEquals("keylog.txt", siblingKeyLogCandidate(children - "sslkeylog.txt", "capture"))
        assertEquals("premaster.txt", siblingKeyLogCandidate(listOf("premaster.txt"), "capture"))
    }

    @Test
    fun `every well-known name is recognised`() {
        for (name in SSL_KEY_LOG_SIBLING_NAMES) {
            assertEquals(name, siblingKeyLogCandidate(listOf("capture.pcap", name), "capture"))
        }
    }

    @Test
    fun `base-name keylog and sslkeylog match`() {
        assertEquals("capture.keylog", siblingKeyLogCandidate(listOf("capture.keylog"), "capture"))
        assertEquals("capture.sslkeylog", siblingKeyLogCandidate(listOf("capture.sslkeylog"), "capture"))
    }

    @Test
    fun `base-name match is case-insensitive both ways`() {
        assertEquals("Capture.KeyLog", siblingKeyLogCandidate(listOf("Capture.KeyLog"), "capture"))
        assertEquals("capture.keylog", siblingKeyLogCandidate(listOf("capture.keylog"), "CAPTURE"))
        assertEquals("SSLKeyLog.TXT", siblingKeyLogCandidate(listOf("SSLKeyLog.TXT"), "capture"))
    }

    @Test
    fun `well-known name beats a base-name match`() {
        val children = listOf("capture.keylog", "keylog.txt")
        assertEquals("keylog.txt", siblingKeyLogCandidate(children, "capture"))
    }

    @Test
    fun `a keylog belonging to another capture is not picked up`() {
        val children = listOf("capture.pcap", "other.keylog", "other.sslkeylog")
        assertNull(siblingKeyLogCandidate(children, "capture"))
    }

    @Test
    fun `unrelated files do not match`() {
        val children = listOf("capture.pcap", "readme.txt", "server.key", "keylog", "capture.log")
        assertNull(siblingKeyLogCandidate(children, "capture"))
    }

    @Test
    fun `empty directory yields nothing`() {
        assertNull(siblingKeyLogCandidate(emptyList(), "capture"))
    }

    @Test
    fun `private key content is told apart from a key log`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIC\n-----END RSA PRIVATE KEY-----\n"
        assertEquals(true, looksLikePrivateKey(pem))
        assertEquals(true, looksLikePrivateKey("\n  $pem"))
        assertEquals(true, looksLikePrivateKey("-----BEGIN PRIVATE KEY-----\nMIIC\n"))
        assertEquals(false, looksLikePrivateKey("CLIENT_RANDOM abcd 0123\n"))
        assertEquals(false, looksLikePrivateKey("-----BEGIN CERTIFICATE-----\nMIIC\n"))
        assertEquals(false, looksLikePrivateKey(""))
    }
}
