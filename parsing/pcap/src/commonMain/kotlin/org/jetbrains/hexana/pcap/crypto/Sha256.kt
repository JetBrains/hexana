package org.jetbrains.hexana.pcap.crypto

/** SHA-256 (FIPS 180-4). */
internal class Sha256Digest : Digest {

    override val digestSize: Int get() = 32
    override val blockSize: Int get() = 64

    private val h = IntArray(8)
    private val block = ByteArray(64)
    private val w = IntArray(64)
    private var blockPos = 0
    private var byteCount = 0L

    init {
        reset()
    }

    override fun reset() {
        INITIAL.copyInto(h)
        blockPos = 0
        byteCount = 0L
    }

    override fun update(input: ByteArray, offset: Int, length: Int) {
        var off = offset
        var remaining = length
        byteCount += length.toLong()
        while (remaining > 0) {
            val take = minOf(64 - blockPos, remaining)
            input.copyInto(block, blockPos, off, off + take)
            blockPos += take
            off += take
            remaining -= take
            if (blockPos == 64) {
                compress()
                blockPos = 0
            }
        }
    }

    override fun digest(): ByteArray {
        val bitLength = byteCount * 8
        // Pad with 0x80 then zeros so the length field lands in the last 8
        // bytes of a block; `update` flushes whole blocks as they fill.
        val padding = ByteArray(if (blockPos < 56) 56 - blockPos else 120 - blockPos)
        padding[0] = 0x80.toByte()
        update(padding, 0, padding.size)
        val lengthBytes = ByteArray(8) { ((bitLength shr (56 - 8 * it)) and 0xFF).toByte() }
        update(lengthBytes, 0, 8)

        val out = ByteArray(32)
        for (i in 0 until 8) {
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
        for (i in 16 until 64) {
            val x = w[i - 15]
            val y = w[i - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var t = h[7]

        for (i in 0 until 64) {
            val bigS1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = t + bigS1 + ch + K[i] + w[i]
            val bigS0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = bigS0 + maj
            t = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += t
    }

    private companion object {
        val INITIAL = intArrayOf(
            0x6a09e667u.toInt(), 0xbb67ae85u.toInt(), 0x3c6ef372u.toInt(), 0xa54ff53au.toInt(),
            0x510e527fu.toInt(), 0x9b05688cu.toInt(), 0x1f83d9abu.toInt(), 0x5be0cd19u.toInt(),
        )

        val K = intArrayOf(
            0x428a2f98u.toInt(), 0x71374491u.toInt(), 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
            0x3956c25bu.toInt(), 0x59f111f1u.toInt(), 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
            0xd807aa98u.toInt(), 0x12835b01u.toInt(), 0x243185beu.toInt(), 0x550c7dc3u.toInt(),
            0x72be5d74u.toInt(), 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
            0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6u.toInt(), 0x240ca1ccu.toInt(),
            0x2de92c6fu.toInt(), 0x4a7484aau.toInt(), 0x5cb0a9dcu.toInt(), 0x76f988dau.toInt(),
            0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
            0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351u.toInt(), 0x14292967u.toInt(),
            0x27b70a85u.toInt(), 0x2e1b2138u.toInt(), 0x4d2c6dfcu.toInt(), 0x53380d13u.toInt(),
            0x650a7354u.toInt(), 0x766a0abbu.toInt(), 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
            0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
            0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070u.toInt(),
            0x19a4c116u.toInt(), 0x1e376c08u.toInt(), 0x2748774cu.toInt(), 0x34b0bcb5u.toInt(),
            0x391c0cb3u.toInt(), 0x4ed8aa4au.toInt(), 0x5b9cca4fu.toInt(), 0x682e6ff3u.toInt(),
            0x748f82eeu.toInt(), 0x78a5636fu.toInt(), 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
            0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
        )
    }
}
