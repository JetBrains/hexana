package org.jetbrains.hexana.pcap

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM implementation of [TlsDecryptor] using javax.crypto for AES-GCM decryption.
 */
object JvmTlsDecryptor : TlsDecryptor {

    override fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray?,
        cipherSuite: TlsCipherSuite,
    ): ByteArray? {
        return try {
            when (cipherSuite) {
                TlsCipherSuite.AES_128_GCM_SHA256,
                TlsCipherSuite.AES_256_GCM_SHA384,
                TlsCipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                TlsCipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                -> decryptAesGcm(ciphertext, key, iv, aad, cipherSuite.tagLength)

                TlsCipherSuite.CHACHA20_POLY1305_SHA256 ->
                    decryptChaCha20Poly1305(ciphertext, key, iv, aad)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptAesGcm(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray?,
        tagLengthBits: Int,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(tagLengthBits * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    private fun decryptChaCha20Poly1305(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray?,
    ): ByteArray? {
        // ChaCha20-Poly1305 requires JDK 11+ with the SunJCE provider
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            if (aad != null) {
                cipher.updateAAD(aad)
            }
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }
}
