package org.jetbrains.hexana.pcap.crypto

/**
 * AES key expansion and single-block encryption (FIPS-197).
 *
 * GCM only ever needs [encryptBlock] — it builds a keystream with CTR — but
 * the TLS 1.2 CBC suites need the inverse cipher, so [decryptBlock] is here
 * too and is exercised by the same FIPS-197 known-answer tests.
 *
 * Expanding a key is the expensive part, so callers should hold on to an
 * instance rather than constructing one per record.
 *
 * Not thread-safe: [encryptBlock] works in a scratch buffer owned by the
 * instance, so one schedule must not be driven from two threads at once.
 */
internal class AesKeySchedule(key: ByteArray) {

    /** Number of AES rounds: 10 for AES-128, 12 for AES-192, 14 for AES-256. */
    val rounds: Int = when (key.size) {
        16 -> 10
        24 -> 12
        32 -> 14
        else -> throw IllegalArgumentException("unsupported AES key length: ${key.size} bytes")
    }

    /** Round keys laid out end to end, 16 bytes per round key. */
    private val roundKeys: ByteArray

    private val state = ByteArray(16)

    init {
        val nk = key.size / 4
        val totalWords = 4 * (rounds + 1)
        val w = IntArray(totalWords)
        for (i in 0 until nk) {
            w[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or
                ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
                (key[4 * i + 3].toInt() and 0xFF)
        }
        for (i in nk until totalWords) {
            var temp = w[i - 1]
            if (i % nk == 0) {
                temp = subWord(temp.rotateLeft(8)) xor (RCON[i / nk] shl 24)
            } else if (nk > 6 && i % nk == 4) {
                temp = subWord(temp)
            }
            w[i] = w[i - nk] xor temp
        }
        roundKeys = ByteArray(totalWords * 4)
        for (i in 0 until totalWords) {
            roundKeys[4 * i] = (w[i] ushr 24).toByte()
            roundKeys[4 * i + 1] = (w[i] ushr 16).toByte()
            roundKeys[4 * i + 2] = (w[i] ushr 8).toByte()
            roundKeys[4 * i + 3] = w[i].toByte()
        }
    }

    /** Encrypts the 16 bytes at [inOffset] into [output] at [outOffset]. */
    fun encryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int) {
        input.copyInto(state, 0, inOffset, inOffset + 16)
        addRoundKey(0)
        for (round in 1 until rounds) {
            subBytes()
            shiftRows()
            mixColumns()
            addRoundKey(round)
        }
        subBytes()
        shiftRows()
        addRoundKey(rounds)
        state.copyInto(output, outOffset)
    }

    /** Decrypts the 16 bytes at [inOffset] into [output] at [outOffset]. */
    fun decryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int) {
        input.copyInto(state, 0, inOffset, inOffset + 16)
        addRoundKey(rounds)
        for (round in rounds - 1 downTo 1) {
            invShiftRows()
            invSubBytes()
            addRoundKey(round)
            invMixColumns()
        }
        invShiftRows()
        invSubBytes()
        addRoundKey(0)
        state.copyInto(output, outOffset)
    }

    private fun addRoundKey(round: Int) {
        val base = round * 16
        for (i in 0 until 16) {
            state[i] = (state[i].toInt() xor roundKeys[base + i].toInt()).toByte()
        }
    }

    private fun subBytes() {
        for (i in 0 until 16) {
            state[i] = SBOX[state[i].toInt() and 0xFF]
        }
    }

    /**
     * The state is column-major (`state[row + 4 * column]`), so row `r` is
     * rotated left by `r` positions with a stride of 4.
     */
    private fun shiftRows() {
        var t = state[1]
        state[1] = state[5]; state[5] = state[9]; state[9] = state[13]; state[13] = t

        t = state[2]; state[2] = state[10]; state[10] = t
        t = state[6]; state[6] = state[14]; state[14] = t

        t = state[15]
        state[15] = state[11]; state[11] = state[7]; state[7] = state[3]; state[3] = t
    }

    private fun invSubBytes() {
        for (i in 0 until 16) {
            state[i] = INV_SBOX[state[i].toInt() and 0xFF]
        }
    }

    /** [shiftRows] run backwards: row `r` rotates right by `r` positions. */
    private fun invShiftRows() {
        var t = state[13]
        state[13] = state[9]; state[9] = state[5]; state[5] = state[1]; state[1] = t

        t = state[2]; state[2] = state[10]; state[10] = t
        t = state[6]; state[6] = state[14]; state[14] = t

        t = state[3]
        state[3] = state[7]; state[7] = state[11]; state[11] = state[15]; state[15] = t
    }

    private fun invMixColumns() {
        for (c in 0 until 4) {
            val i = 4 * c
            val a0 = state[i].toInt() and 0xFF
            val a1 = state[i + 1].toInt() and 0xFF
            val a2 = state[i + 2].toInt() and 0xFF
            val a3 = state[i + 3].toInt() and 0xFF
            state[i] = (gmul(a0, 14) xor gmul(a1, 11) xor gmul(a2, 13) xor gmul(a3, 9)).toByte()
            state[i + 1] = (gmul(a0, 9) xor gmul(a1, 14) xor gmul(a2, 11) xor gmul(a3, 13)).toByte()
            state[i + 2] = (gmul(a0, 13) xor gmul(a1, 9) xor gmul(a2, 14) xor gmul(a3, 11)).toByte()
            state[i + 3] = (gmul(a0, 11) xor gmul(a1, 13) xor gmul(a2, 9) xor gmul(a3, 14)).toByte()
        }
    }

    private fun mixColumns() {
        for (c in 0 until 4) {
            val i = 4 * c
            val a0 = state[i].toInt() and 0xFF
            val a1 = state[i + 1].toInt() and 0xFF
            val a2 = state[i + 2].toInt() and 0xFF
            val a3 = state[i + 3].toInt() and 0xFF
            state[i] = (xtime(a0) xor xtime(a1) xor a1 xor a2 xor a3).toByte()
            state[i + 1] = (a0 xor xtime(a1) xor xtime(a2) xor a2 xor a3).toByte()
            state[i + 2] = (a0 xor a1 xor xtime(a2) xor xtime(a3) xor a3).toByte()
            state[i + 3] = (xtime(a0) xor a0 xor a1 xor a2 xor xtime(a3)).toByte()
        }
    }

    internal companion object {
        /** Multiplication by 2 in GF(2^8) with the AES reduction polynomial. */
        private fun xtime(x: Int): Int {
            val shifted = x shl 1
            return (if (x and 0x80 != 0) shifted xor 0x1B else shifted) and 0xFF
        }

        /** Russian-peasant multiplication in GF(2^8); [b] is a small constant. */
        private fun gmul(a: Int, b: Int): Int {
            var x = a
            var y = b
            var result = 0
            while (y != 0) {
                if (y and 1 != 0) result = result xor x
                x = xtime(x)
                y = y shr 1
            }
            return result and 0xFF
        }

        private fun subWord(w: Int): Int =
            ((SBOX[(w ushr 24) and 0xFF].toInt() and 0xFF) shl 24) or
                ((SBOX[(w ushr 16) and 0xFF].toInt() and 0xFF) shl 16) or
                ((SBOX[(w ushr 8) and 0xFF].toInt() and 0xFF) shl 8) or
                (SBOX[w and 0xFF].toInt() and 0xFF)

        private val RCON = intArrayOf(
            0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36, 0x6C, 0xD8,
        )

        /**
         * The AES S-box, derived rather than transcribed: each entry is the
         * multiplicative inverse in GF(2^8) put through the standard affine
         * transform. Computing it avoids 256 hand-copied hex values, and the
         * FIPS-197 known-answer tests confirm it came out right.
         */
        val SBOX: ByteArray = buildSbox()

        /** The S-box read backwards, so [decryptBlock] needs no second table. */
        val INV_SBOX: ByteArray = ByteArray(256).also { inverse ->
            for (i in 0 until 256) inverse[SBOX[i].toInt() and 0xFF] = i.toByte()
        }

        private fun buildSbox(): ByteArray {
            // Exp/log tables over the generator 3, used to invert.
            val exp = IntArray(256)
            val log = IntArray(256)
            var p = 1
            for (i in 0 until 255) {
                exp[i] = p
                log[p] = i
                p = (p xor xtime(p)) and 0xFF // p *= 3
            }
            val sbox = ByteArray(256)
            for (a in 0 until 256) {
                val inverse = if (a == 0) 0 else exp[(255 - log[a]) % 255]
                // b = inv ^ rotl(inv,1) ^ rotl(inv,2) ^ rotl(inv,3) ^ rotl(inv,4) ^ 0x63
                var acc = inverse
                var r = inverse
                for (k in 0 until 4) {
                    r = ((r shl 1) or (r ushr 7)) and 0xFF
                    acc = acc xor r
                }
                sbox[a] = (acc xor 0x63).toByte()
            }
            return sbox
        }
    }
}
