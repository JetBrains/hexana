package org.jetbrains.hexana.pcap

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TLS 1.3 HKDF-based key derivation (RFC 8446 §7.1) and TLS 1.2 PRF
 * key derivation (RFC 5246 §6.3). Used to derive traffic encryption
 * keys and IVs from the secrets stored in SSLKEYLOG files.
 */
object TlsKeyDerivation : TlsKeyDeriver {

    /**
     * Derives TLS 1.3 traffic keys and IV from a traffic secret.
     *
     * @param trafficSecret the traffic secret (e.g. CLIENT_TRAFFIC_SECRET_0)
     * @param cipherSuite the negotiated cipher suite
     * @return a pair of (key, iv) or null on failure
     */
    override fun deriveTrafficKeyAndIv(
        trafficSecret: ByteArray,
        cipherSuite: TlsCipherSuite,
    ): Pair<ByteArray, ByteArray>? {
        return try {
            val hashAlg = hashAlgorithm(cipherSuite)
            val key = hkdfExpandLabel(trafficSecret, "key", ByteArray(0), cipherSuite.keyLength, hashAlg)
            val iv = hkdfExpandLabel(trafficSecret, "iv", ByteArray(0), cipherSuite.ivLength, hashAlg)
            Pair(key, iv)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Derives TLS 1.2 traffic keys from the master secret using the PRF.
     *
     * @param masterSecret the 48-byte master secret from CLIENT_RANDOM
     * @param serverRandom the 32-byte server random
     * @param clientRandom the 32-byte client random
     * @param cipherSuite the negotiated cipher suite
     * @return client and server (key, iv) pairs, or null on failure
     */
    override fun deriveTls12Keys(
        masterSecret: ByteArray,
        serverRandom: ByteArray,
        clientRandom: ByteArray,
        cipherSuite: TlsCipherSuite,
    ): Tls12KeyMaterial? {
        return try {
            val hashAlg = hashAlgorithm(cipherSuite)
            val seed = serverRandom + clientRandom
            // key_block = PRF(master_secret, "key expansion", server_random + client_random)
            val needed = 2 * cipherSuite.keyLength + 2 * cipherSuite.ivLength
            val keyBlock = prf(masterSecret, "key expansion", seed, needed, hashAlg)

            var offset = 0
            val clientWriteKey = keyBlock.copyOfRange(offset, offset + cipherSuite.keyLength)
            offset += cipherSuite.keyLength
            val serverWriteKey = keyBlock.copyOfRange(offset, offset + cipherSuite.keyLength)
            offset += cipherSuite.keyLength
            val clientWriteIv = keyBlock.copyOfRange(offset, offset + cipherSuite.ivLength)
            offset += cipherSuite.ivLength
            val serverWriteIv = keyBlock.copyOfRange(offset, offset + cipherSuite.ivLength)

            Tls12KeyMaterial(clientWriteKey, clientWriteIv, serverWriteKey, serverWriteIv)
        } catch (_: Exception) {
            null
        }
    }

    // ---- HKDF (RFC 5869) ----

    /**
     * HKDF-Expand-Label as defined in RFC 8446 §7.1:
     * ```
     * HKDF-Expand-Label(Secret, Label, Context, Length) =
     *     HKDF-Expand(Secret, HkdfLabel, Length)
     * ```
     * where HkdfLabel is:
     * ```
     * struct {
     *     uint16 length = Length;
     *     opaque label<7..255> = "tls13 " + Label;
     *     opaque context<0..255> = Context;
     * } HkdfLabel;
     * ```
     */
    private fun hkdfExpandLabel(
        secret: ByteArray,
        label: String,
        context: ByteArray,
        length: Int,
        hashAlg: String,
    ): ByteArray {
        val fullLabel = "tls13 $label".encodeToByteArray()
        val hkdfLabel = ByteArray(2 + 1 + fullLabel.size + 1 + context.size)
        hkdfLabel[0] = (length shr 8).toByte()
        hkdfLabel[1] = (length and 0xFF).toByte()
        hkdfLabel[2] = fullLabel.size.toByte()
        fullLabel.copyInto(hkdfLabel, 3)
        hkdfLabel[3 + fullLabel.size] = context.size.toByte()
        context.copyInto(hkdfLabel, 4 + fullLabel.size)
        return hkdfExpand(secret, hkdfLabel, length, hashAlg)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int, hashAlg: String): ByteArray {
        val mac = Mac.getInstance(hashAlg)
        mac.init(SecretKeySpec(prk, hashAlg))
        val hashLen = mac.macLength
        val n = (length + hashLen - 1) / hashLen
        val result = ByteArray(n * hashLen)
        var t = ByteArray(0)
        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            t.copyInto(result, (i - 1) * hashLen)
        }
        return result.copyOf(length)
    }

    // ---- TLS 1.2 PRF (RFC 5246 §5) ----

    private fun prf(
        secret: ByteArray,
        label: String,
        seed: ByteArray,
        length: Int,
        hashAlg: String,
    ): ByteArray {
        val labelBytes = label.encodeToByteArray()
        val fullSeed = labelBytes + seed
        return pHash(secret, fullSeed, length, hashAlg)
    }

    private fun pHash(
        secret: ByteArray,
        seed: ByteArray,
        length: Int,
        hashAlg: String,
    ): ByteArray {
        val mac = Mac.getInstance(hashAlg)
        mac.init(SecretKeySpec(secret, hashAlg))
        val result = ByteArray(length)
        var a = seed // A(0) = seed
        var offset = 0
        while (offset < length) {
            mac.reset()
            mac.update(a)
            a = mac.doFinal() // A(i) = HMAC(secret, A(i-1))
            mac.reset()
            mac.update(a)
            mac.update(seed)
            val block = mac.doFinal() // P_hash = HMAC(secret, A(i) + seed)
            val toCopy = minOf(block.size, length - offset)
            block.copyInto(result, offset, 0, toCopy)
            offset += toCopy
        }
        return result
    }

    private fun hashAlgorithm(cipherSuite: TlsCipherSuite): String = when (cipherSuite) {
        TlsCipherSuite.AES_256_GCM_SHA384,
        TlsCipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
        TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        -> "HmacSHA384"
        else -> "HmacSHA256"
    }
}
