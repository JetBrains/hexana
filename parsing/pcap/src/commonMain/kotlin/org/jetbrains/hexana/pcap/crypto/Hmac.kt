package org.jetbrains.hexana.pcap.crypto

/**
 * HMAC (RFC 2104) over any [Digest]. Deliberately shaped like
 * `javax.crypto.Mac` — `update` / `doFinal` / `reset` / `macLength` — so the
 * HKDF and TLS 1.2 PRF loops read the same in both implementations and can be
 * cross-checked against each other.
 *
 * [doFinal] resets the instance, so one `Hmac` can serve a whole derivation.
 */
internal class Hmac(newDigest: () -> Digest, key: ByteArray) {

    private val digest = newDigest()
    private val ipadKey: ByteArray
    private val opadKey: ByteArray
    private val single = ByteArray(1)

    /** Output length in bytes, matching `javax.crypto.Mac.getMacLength()`. */
    val macLength: Int get() = digest.digestSize

    init {
        val blockSize = digest.blockSize
        // Keys longer than a block are hashed down; shorter ones are zero-padded.
        val normalized = if (key.size > blockSize) {
            newDigest().also { it.update(key) }.digest()
        } else {
            key
        }
        ipadKey = ByteArray(blockSize) { 0x36 }
        opadKey = ByteArray(blockSize) { 0x5c }
        for (i in normalized.indices) {
            ipadKey[i] = (ipadKey[i].toInt() xor normalized[i].toInt()).toByte()
            opadKey[i] = (opadKey[i].toInt() xor normalized[i].toInt()).toByte()
        }
        reset()
    }

    fun reset() {
        digest.reset()
        digest.update(ipadKey)
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset) {
        digest.update(input, offset, length)
    }

    fun update(input: Byte) {
        single[0] = input
        digest.update(single, 0, 1)
    }

    /** Finalises the MAC and resets, ready for the next message with the same key. */
    fun doFinal(): ByteArray {
        val inner = digest.digest() // digest() also resets
        digest.update(opadKey)
        digest.update(inner)
        val mac = digest.digest()
        reset()
        return mac
    }
}
