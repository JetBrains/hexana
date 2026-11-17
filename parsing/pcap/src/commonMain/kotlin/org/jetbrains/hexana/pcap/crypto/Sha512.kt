package org.jetbrains.hexana.pcap.crypto

/**
 * SHA-512 and SHA-384 (FIPS 180-4). Both share one compression function and
 * differ only in the initial hash value and the output length, so [outputBytes]
 * selects between them: 64 for SHA-512, 48 for SHA-384.
 */
internal class Sha512Digest(override val digestSize: Int = 64) : Digest {

    init {
        require(digestSize == 64 || digestSize == 48) {
            "only SHA-512 (64) and SHA-384 (48) output lengths are supported, got $digestSize"
        }
    }

    override val blockSize: Int get() = 128

    private val h = LongArray(8)
    private val block = ByteArray(128)
    private val w = LongArray(80)
    private var blockPos = 0
    private var byteCount = 0L

    init {
        reset()
    }

    override fun reset() {
        (if (digestSize == 48) INITIAL_384 else INITIAL_512).copyInto(h)
        blockPos = 0
        byteCount = 0L
    }

    override fun update(input: ByteArray, offset: Int, length: Int) {
        var off = offset
        var remaining = length
        byteCount += length.toLong()
        while (remaining > 0) {
            val take = minOf(128 - blockPos, remaining)
            input.copyInto(block, blockPos, off, off + take)
            blockPos += take
            off += take
            remaining -= take
            if (blockPos == 128) {
                compress()
                blockPos = 0
            }
        }
    }

    override fun digest(): ByteArray {
        val bitLength = byteCount * 8
        val padding = ByteArray(if (blockPos < 112) 112 - blockPos else 240 - blockPos)
        padding[0] = 0x80.toByte()
        update(padding, 0, padding.size)
        // The length field is 128 bits; the high half is always zero for the
        // input sizes this sees (key logs and TLS records, not exabytes).
        val lengthBytes = ByteArray(16)
        for (i in 0 until 8) {
            lengthBytes[8 + i] = ((bitLength shr (56 - 8 * i)) and 0xFF).toByte()
        }
        update(lengthBytes, 0, 16)

        val full = ByteArray(64)
        for (i in 0 until 8) {
            for (j in 0 until 8) {
                full[8 * i + j] = ((h[i] ushr (56 - 8 * j)) and 0xFF).toByte()
            }
        }
        reset()
        return if (digestSize == 64) full else full.copyOfRange(0, digestSize)
    }

    private fun compress() {
        for (i in 0 until 16) {
            var v = 0L
            for (j in 0 until 8) {
                v = (v shl 8) or (block[8 * i + j].toLong() and 0xFF)
            }
            w[i] = v
        }
        for (i in 16 until 80) {
            val x = w[i - 15]
            val y = w[i - 2]
            val s0 = x.rotateRight(1) xor x.rotateRight(8) xor (x ushr 7)
            val s1 = y.rotateRight(19) xor y.rotateRight(61) xor (y ushr 6)
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

        for (i in 0 until 80) {
            val bigS1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = t + bigS1 + ch + K[i] + w[i]
            val bigS0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
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
        val INITIAL_512 = longArrayOf(
            0x6a09e667f3bcc908uL.toLong(), 0xbb67ae8584caa73buL.toLong(),
            0x3c6ef372fe94f82buL.toLong(), 0xa54ff53a5f1d36f1uL.toLong(),
            0x510e527fade682d1uL.toLong(), 0x9b05688c2b3e6c1fuL.toLong(),
            0x1f83d9abfb41bd6buL.toLong(), 0x5be0cd19137e2179uL.toLong(),
        )

        val INITIAL_384 = longArrayOf(
            0xcbbb9d5dc1059ed8uL.toLong(), 0x629a292a367cd507uL.toLong(),
            0x9159015a3070dd17uL.toLong(), 0x152fecd8f70e5939uL.toLong(),
            0x67332667ffc00b31uL.toLong(), 0x8eb44a8768581511uL.toLong(),
            0xdb0c2e0d64f98fa7uL.toLong(), 0x47b5481dbefa4fa4uL.toLong(),
        )

        val K = longArrayOf(
            0x428a2f98d728ae22uL.toLong(), 0x7137449123ef65cduL.toLong(),
            0xb5c0fbcfec4d3b2fuL.toLong(), 0xe9b5dba58189dbbcuL.toLong(),
            0x3956c25bf348b538uL.toLong(), 0x59f111f1b605d019uL.toLong(),
            0x923f82a4af194f9buL.toLong(), 0xab1c5ed5da6d8118uL.toLong(),
            0xd807aa98a3030242uL.toLong(), 0x12835b0145706fbeuL.toLong(),
            0x243185be4ee4b28cuL.toLong(), 0x550c7dc3d5ffb4e2uL.toLong(),
            0x72be5d74f27b896fuL.toLong(), 0x80deb1fe3b1696b1uL.toLong(),
            0x9bdc06a725c71235uL.toLong(), 0xc19bf174cf692694uL.toLong(),
            0xe49b69c19ef14ad2uL.toLong(), 0xefbe4786384f25e3uL.toLong(),
            0x0fc19dc68b8cd5b5uL.toLong(), 0x240ca1cc77ac9c65uL.toLong(),
            0x2de92c6f592b0275uL.toLong(), 0x4a7484aa6ea6e483uL.toLong(),
            0x5cb0a9dcbd41fbd4uL.toLong(), 0x76f988da831153b5uL.toLong(),
            0x983e5152ee66dfabuL.toLong(), 0xa831c66d2db43210uL.toLong(),
            0xb00327c898fb213fuL.toLong(), 0xbf597fc7beef0ee4uL.toLong(),
            0xc6e00bf33da88fc2uL.toLong(), 0xd5a79147930aa725uL.toLong(),
            0x06ca6351e003826fuL.toLong(), 0x142929670a0e6e70uL.toLong(),
            0x27b70a8546d22ffcuL.toLong(), 0x2e1b21385c26c926uL.toLong(),
            0x4d2c6dfc5ac42aeduL.toLong(), 0x53380d139d95b3dfuL.toLong(),
            0x650a73548baf63deuL.toLong(), 0x766a0abb3c77b2a8uL.toLong(),
            0x81c2c92e47edaee6uL.toLong(), 0x92722c851482353buL.toLong(),
            0xa2bfe8a14cf10364uL.toLong(), 0xa81a664bbc423001uL.toLong(),
            0xc24b8b70d0f89791uL.toLong(), 0xc76c51a30654be30uL.toLong(),
            0xd192e819d6ef5218uL.toLong(), 0xd69906245565a910uL.toLong(),
            0xf40e35855771202auL.toLong(), 0x106aa07032bbd1b8uL.toLong(),
            0x19a4c116b8d2d0c8uL.toLong(), 0x1e376c085141ab53uL.toLong(),
            0x2748774cdf8eeb99uL.toLong(), 0x34b0bcb5e19b48a8uL.toLong(),
            0x391c0cb3c5c95a63uL.toLong(), 0x4ed8aa4ae3418acbuL.toLong(),
            0x5b9cca4f7763e373uL.toLong(), 0x682e6ff3d6b2b8a3uL.toLong(),
            0x748f82ee5defb2fcuL.toLong(), 0x78a5636f43172f60uL.toLong(),
            0x84c87814a1f0ab72uL.toLong(), 0x8cc702081a6439ecuL.toLong(),
            0x90befffa23631e28uL.toLong(), 0xa4506cebde82bde9uL.toLong(),
            0xbef9a3f7b2c67915uL.toLong(), 0xc67178f2e372532buL.toLong(),
            0xca273eceea26619cuL.toLong(), 0xd186b8c721c0c207uL.toLong(),
            0xeada7dd6cde0eb1euL.toLong(), 0xf57d4f7fee6ed178uL.toLong(),
            0x06f067aa72176fbauL.toLong(), 0x0a637dc5a2c898a6uL.toLong(),
            0x113f9804bef90daeuL.toLong(), 0x1b710b35131c471buL.toLong(),
            0x28db77f523047d84uL.toLong(), 0x32caab7b40c72493uL.toLong(),
            0x3c9ebe0a15c9bebcuL.toLong(), 0x431d67c49c100d4cuL.toLong(),
            0x4cc5d4becb3e42b6uL.toLong(), 0x597f299cfc657e2auL.toLong(),
            0x5fcb6fab3ad6faecuL.toLong(), 0x6c44198c4a475817uL.toLong(),
        )
    }
}
