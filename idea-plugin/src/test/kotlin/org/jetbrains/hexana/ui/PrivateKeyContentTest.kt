package org.jetbrains.hexana.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A picked key log that is really a private key gets its own hint dialog
 * rather than a "no keys found" warning, so the telling-apart is worth
 * pinning: certificates and key logs must not trip it.
 */
class PrivateKeyContentTest {

    @Test
    fun `private key content is told apart from a key log`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----\n"
        assertEquals(true, looksLikePrivateKey(pem))
        assertEquals(true, looksLikePrivateKey("\n  $pem"))
        assertEquals(true, looksLikePrivateKey("-----BEGIN PRIVATE KEY-----\nMIIC\n"))
        assertEquals(false, looksLikePrivateKey("CLIENT_RANDOM abcd 0123\n"))
        assertEquals(false, looksLikePrivateKey("-----BEGIN CERTIFICATE-----\nMIIC\n"))
        assertEquals(false, looksLikePrivateKey(""))
    }
}
