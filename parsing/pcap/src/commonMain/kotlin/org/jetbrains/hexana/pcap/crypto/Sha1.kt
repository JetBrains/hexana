package org.jetbrains.hexana.pcap.crypto

/**
 * SHA-1 (FIPS 180-4), needed only because the TLS 1.2 CBC suites still MAC
 * with it. Nothing here signs or verifies anything new — it reads a MAC that
 * was computed years ago by whoever produced the capture — so SHA-1's
 * collision weakness is not in play.
 *
 * Same streaming shape as [Sha256Digest]: [update] buffers to a 64-byte block
 * and [digest] pads, appends the 64-bit bit length and resets.
 */
internal class Sha1Digest : Digest {

    override val digestSize: Int get() = 20
    override val blockSize: Int get() = 64

    private val h = IntArray(5)
    private val block = ByteArray(64)
    private val w = IntArray(80)
    private var blockLength = 0
    private var totalBytes = 0L

    init {
        reset()
    }

    override fun reset() {
        h[0] = 0x67452301
        h[1] = 0xEFCDAB89.toInt()
        h[2] = 0x98BADCFE.toInt()
        h[3] = 0x10325476
        h[4] = 0xC3D2E1F0.toInt()
        blockLength = 0
        totalBytes = 0
    }

    override fun update(input: ByteArray, offset: Int, length: Int) {
        var position = offset
        var remaining = length
        totalBytes += length
        while (remaining > 0) {
            val take = minOf(remaining, 64 - blockLength)
            input.copyInto(block, blockLength, position, position + take)
            blockLength += take
            position += take
            remaining -= take
            if (blockLength == 64) {
                compress()
                blockLength = 0
            }
        }
    }

    override fun digest(): ByteArray {
        val bitLength = totalBytes * 8
        // Pad with 0x80 then zeros so the length field lands in the last 8
        // bytes of a block; `update` flushes whole blocks as they fill.
        val padding = ByteArray(if (blockLength < 56) 56 - blockLength else 120 - blockLength)
        padding[0] = 0x80.toByte()
        update(padding, 0, padding.size)
        val lengthBytes = ByteArray(8) { ((bitLength shr (56 - 8 * it)) and 0xFF).toByte() }
        update(lengthBytes, 0, 8)

        val out = ByteArray(20)
        for (i in 0 until 5) {
            out[4 * i] = (h[i] ushr 24).toByte()
            out[4 * i + 1] = (h[i] ushr 16).toByte()
            out[4 * i + 2] = (h[i] ushr 8).toByte()
            out[4 * i + 3] = h[i].toByte()
        }
        reset()
        return out
    }

    private fun compress() {
        for (i in 0 until 16) {
            w[i] = ((block[4 * i].toInt() and 0xFF) shl 24) or
                ((block[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((block[4 * i + 2].toInt() and 0xFF) shl 8) or
                (block[4 * i + 3].toInt() and 0xFF)
        }
        for (i in 16 until 80) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        for (i in 0 until 80) {
            val f: Int
            val k: Int
            when {
                i < 20 -> {
                    f = (b and c) or (b.inv() and d); k = 0x5A827999
                }

                i < 40 -> {
                    f = b xor c xor d; k = 0x6ED9EBA1
                }

                i < 60 -> {
                    f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt()
                }

                else -> {
                    f = b xor c xor d; k = 0xCA62C1D6.toInt()
                }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
    }
}
