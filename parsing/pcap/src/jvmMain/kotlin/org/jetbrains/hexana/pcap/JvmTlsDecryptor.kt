package org.jetbrains.hexana.pcap

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM implementation of [TlsDecryptor] using javax.crypto: AES-GCM and
 * ChaCha20-Poly1305 for the AEAD suites, AES-CBC for the older ones.
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
            if (cipherSuite.isChaCha20Poly1305) {
                decryptChaCha20Poly1305(ciphertext, key, iv, aad)
            } else {
                decryptAesGcm(ciphertext, key, iv, aad, cipherSuite.tagLength)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun decryptCbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
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
