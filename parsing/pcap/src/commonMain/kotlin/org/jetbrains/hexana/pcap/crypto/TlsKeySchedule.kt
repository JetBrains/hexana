package org.jetbrains.hexana.pcap.crypto

import org.jetbrains.hexana.pcap.TlsCipherSuite

/**
 * TLS 1.3 HKDF key derivation (RFC 8446 §7.1) and the TLS 1.2 PRF
 * (RFC 5246 §5), in pure Kotlin.
 *
 * HKDF-Extract is deliberately absent: an SSLKEYLOG file already stores the
 * extracted secret, so it *is* the PRK and only Expand is ever needed.
 */
internal object TlsKeySchedule {

    /** A fresh HMAC over the hash the cipher suite's key schedule uses. */
    fun hmacFor(cipherSuite: TlsCipherSuite, key: ByteArray): Hmac =
        if (cipherSuite.usesSha384) Hmac({ Sha512Digest(48) }, key) else Hmac({ Sha256Digest() }, key)

    /**
     * HKDF-Expand-Label (RFC 8446 §7.1):
     * ```
     * struct {
     *     uint16 length = Length;
     *     opaque label<7..255> = "tls13 " + Label;
     *     opaque context<0..255> = Context;
     * } HkdfLabel;
     * ```
     */
    fun hkdfExpandLabel(
        label: String,
        context: ByteArray,
        length: Int,
        mac: Hmac,
    ): ByteArray {
        val fullLabel = "tls13 $label".encodeToByteArray()
        val hkdfLabel = ByteArray(2 + 1 + fullLabel.size + 1 + context.size)
        hkdfLabel[0] = (length shr 8).toByte()
        hkdfLabel[1] = (length and 0xFF).toByte()
        hkdfLabel[2] = fullLabel.size.toByte()
        fullLabel.copyInto(hkdfLabel, 3)
        hkdfLabel[3 + fullLabel.size] = context.size.toByte()
        context.copyInto(hkdfLabel, 4 + fullLabel.size)
        return hkdfExpand(hkdfLabel, length, mac)
    }

    /** HKDF-Expand (RFC 5869 §2.3). [mac] must already be keyed with the PRK. */
    fun hkdfExpand(info: ByteArray, length: Int, mac: Hmac): ByteArray {
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

    /** TLS 1.2 PRF (RFC 5246 §5). [mac] must already be keyed with the secret. */
    fun prf(label: String, seed: ByteArray, length: Int, mac: Hmac): ByteArray =
        pHash(label.encodeToByteArray() + seed, length, mac)

    private fun pHash(seed: ByteArray, length: Int, mac: Hmac): ByteArray {
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
}
