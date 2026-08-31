package org.jetbrains.hexana.pcap.crypto

import org.jetbrains.hexana.pcap.Tls12KeyMaterial
import org.jetbrains.hexana.pcap.TlsCipherSuite
import org.jetbrains.hexana.pcap.TlsDecryptor
import org.jetbrains.hexana.pcap.TlsKeyDeriver

/**
 * [TlsDecryptor] backed by the pure-Kotlin AES implementation, for targets
 * with no platform crypto (the browser and VS Code webview builds). Covers
 * both the AES-GCM suites and the AES-CBC ones.
 *
 * ChaCha20-Poly1305 has no pure-Kotlin implementation yet and returns `null`;
 * `isTlsDecryptionSupported` reports that up front so the UI can say so rather
 * than leaving the user guessing at empty cells.
 *
 * One instance per decryption pass: it memoises expanded AES key schedules,
 * which is what keeps per-record cost down now that expansion is no longer
 * repeated for every packet.
 */
internal class PureKotlinTlsDecryptor : TlsDecryptor {

    private class CachedSchedule(val key: ByteArray, val schedule: AesKeySchedule)

    /**
     * Replaced as a whole rather than mutated in place, so a reader always sees
     * a consistent list. Losing a race costs one redundant key expansion; it
     * can never hand back a schedule for the wrong key.
     */
    private var cache: List<CachedSchedule> = emptyList()

    override fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray?,
        cipherSuite: TlsCipherSuite,
    ): ByteArray? {
        if (cipherSuite == TlsCipherSuite.CHACHA20_POLY1305_SHA256) return null
        return try {
            AesGcm.decrypt(ciphertext, scheduleFor(key), iv, aad, cipherSuite.tagLength)
        } catch (_: Exception) {
            null
        }
    }

    override fun decryptCbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? =
        try {
            AesCbc.decrypt(ciphertext, scheduleFor(key), iv)
        } catch (_: Exception) {
            null
        }

    private fun scheduleFor(key: ByteArray): AesKeySchedule {
        val current = cache
        for (entry in current) {
            if (entry.key.contentEquals(key)) return entry.schedule
        }
        val fresh = CachedSchedule(key.copyOf(), AesKeySchedule(key))
        cache = (listOf(fresh) + current).take(MAX_CACHED_SCHEDULES)
        return fresh.schedule
    }

    private companion object {
        /** Two directions of one stream plus headroom for switching streams. */
        const val MAX_CACHED_SCHEDULES = 4
    }
}

/**
 * [TlsKeyDeriver] using the pure-Kotlin HKDF and TLS 1.2 PRF. Stateless, so a
 * single instance serves every caller.
 *
 * Kept byte-for-byte equivalent to the JVM `TlsKeyDerivation` — a jvmTest
 * cross-checks the two against each other over random inputs.
 */
internal object PureKotlinTlsKeyDeriver : TlsKeyDeriver {

    override fun deriveTrafficKeyAndIv(
        trafficSecret: ByteArray,
        cipherSuite: TlsCipherSuite,
    ): Pair<ByteArray, ByteArray>? {
        return try {
            val mac = TlsKeySchedule.hmacFor(cipherSuite, trafficSecret)
            val key = TlsKeySchedule.hkdfExpandLabel("key", ByteArray(0), cipherSuite.keyLength, mac)
            val iv = TlsKeySchedule.hkdfExpandLabel("iv", ByteArray(0), cipherSuite.ivLength, mac)
            Pair(key, iv)
        } catch (_: Exception) {
            null
        }
    }

    override fun deriveTls12Keys(
        masterSecret: ByteArray,
        serverRandom: ByteArray,
        clientRandom: ByteArray,
        cipherSuite: TlsCipherSuite,
    ): Tls12KeyMaterial? {
        return try {
            val mac = TlsKeySchedule.hmacFor(cipherSuite, masterSecret)
            val seed = serverRandom + clientRandom
            // key_block = PRF(master_secret, "key expansion", server_random + client_random)
            val macLength = cipherSuite.macAlgorithm.macLength
            val needed = 2 * macLength + 2 * cipherSuite.keyLength + 2 * cipherSuite.ivLength
            val keyBlock = TlsKeySchedule.prf("key expansion", seed, needed, mac)

            // RFC 5246 §6.3 order: MAC keys, then write keys, then write IVs.
            var offset = 0
            val clientMacKey = keyBlock.copyOfRange(offset, offset + macLength)
            offset += macLength
            val serverMacKey = keyBlock.copyOfRange(offset, offset + macLength)
            offset += macLength
            val clientWriteKey = keyBlock.copyOfRange(offset, offset + cipherSuite.keyLength)
            offset += cipherSuite.keyLength
            val serverWriteKey = keyBlock.copyOfRange(offset, offset + cipherSuite.keyLength)
            offset += cipherSuite.keyLength
            val clientWriteIv = keyBlock.copyOfRange(offset, offset + cipherSuite.ivLength)
            offset += cipherSuite.ivLength
            val serverWriteIv = keyBlock.copyOfRange(offset, offset + cipherSuite.ivLength)

            Tls12KeyMaterial(
                clientWriteKey, clientWriteIv, serverWriteKey, serverWriteIv,
                clientMacKey, serverMacKey,
            )
        } catch (_: Exception) {
            null
        }
    }
}
