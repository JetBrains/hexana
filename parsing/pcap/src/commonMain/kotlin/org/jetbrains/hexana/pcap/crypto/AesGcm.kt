package org.jetbrains.hexana.pcap.crypto

/**
 * AES-GCM (NIST SP 800-38D) in pure Kotlin.
 *
 * Only 12-byte nonces are supported, which is all TLS 1.2 and TLS 1.3 ever
 * use; other lengths are rejected rather than silently mishandled.
 *
 * Ciphertext and tag are passed and returned concatenated (`ciphertext || tag`),
 * matching the JCE convention the JVM decryptor already follows so both
 * implementations are interchangeable and directly comparable in tests.
 */
internal object AesGcm {

    private const val BLOCK_SIZE = 16
    private const val NONCE_SIZE = 12

    /** The GF(2^128) reduction polynomial's high word: 0xe1 followed by zeros. */
    private val R_HIGH = 0xe100000000000000uL.toLong()

    /**
     * Decrypts `ciphertext || tag` and verifies the tag.
     * Returns `null` if the tag does not match or the inputs are malformed —
     * the same "nothing to show" signal the rest of the TLS path uses.
     */
    fun decrypt(
        ciphertextWithTag: ByteArray,
        schedule: AesKeySchedule,
        nonce: ByteArray,
        aad: ByteArray?,
        tagLength: Int,
    ): ByteArray? {
        if (nonce.size != NONCE_SIZE) return null
        if (tagLength !in 1..BLOCK_SIZE) return null
        val ciphertextLength = ciphertextWithTag.size - tagLength
        if (ciphertextLength < 0) return null

        val h = ByteArray(BLOCK_SIZE)
        schedule.encryptBlock(ByteArray(BLOCK_SIZE), 0, h, 0)
        val expectedTag = computeTag(
            schedule, h, nonce, aad, ciphertextWithTag, 0, ciphertextLength,
        )

        // Constant-time comparison: a timing-dependent early exit would leak
        // how much of a forged tag was correct.
        var difference = 0
        for (i in 0 until tagLength) {
            difference = difference or
                (expectedTag[i].toInt() xor ciphertextWithTag[ciphertextLength + i].toInt())
        }
        if (difference != 0) return null

        val plaintext = ByteArray(ciphertextLength)
        ctrXor(schedule, nonce, ciphertextWithTag, 0, ciphertextLength, plaintext)
        return plaintext
    }

    /**
     * Encrypts [plaintext] and returns `ciphertext || tag`.
     *
     * Production only ever decrypts; this exists so tests can build a genuine
     * encrypted TLS record to decrypt back, and so the pure-Kotlin output can
     * be compared byte for byte against javax.crypto.
     */
    fun encrypt(
        plaintext: ByteArray,
        schedule: AesKeySchedule,
        nonce: ByteArray,
        aad: ByteArray?,
        tagLength: Int,
    ): ByteArray {
        require(nonce.size == NONCE_SIZE) { "GCM nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }
        require(tagLength in 1..BLOCK_SIZE) { "GCM tag length must be 1..$BLOCK_SIZE, got $tagLength" }

        val ciphertext = ByteArray(plaintext.size)
        ctrXor(schedule, nonce, plaintext, 0, plaintext.size, ciphertext)

        val h = ByteArray(BLOCK_SIZE)
        schedule.encryptBlock(ByteArray(BLOCK_SIZE), 0, h, 0)
        val tag = computeTag(schedule, h, nonce, aad, ciphertext, 0, ciphertext.size)

        return ciphertext + tag.copyOf(tagLength)
    }

    /** GHASH over the AAD and ciphertext, masked with E_K(J0). */
    private fun computeTag(
        schedule: AesKeySchedule,
        h: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        ciphertextLength: Int,
    ): ByteArray {
        val hHigh = readLong(h, 0)
        val hLow = readLong(h, 8)
        var yHigh = 0L
        var yLow = 0L

        if (aad != null) {
            var offset = 0
            while (offset < aad.size) {
                val length = minOf(BLOCK_SIZE, aad.size - offset)
                yHigh = yHigh xor readLongPadded(aad, offset, length, 0)
                yLow = yLow xor readLongPadded(aad, offset, length, 8)
                val product = multiply(yHigh, yLow, hHigh, hLow)
                yHigh = product[0]
                yLow = product[1]
                offset += length
            }
        }

        var offset = 0
        while (offset < ciphertextLength) {
            val length = minOf(BLOCK_SIZE, ciphertextLength - offset)
            yHigh = yHigh xor readLongPadded(ciphertext, ciphertextOffset + offset, length, 0)
            yLow = yLow xor readLongPadded(ciphertext, ciphertextOffset + offset, length, 8)
            val product = multiply(yHigh, yLow, hHigh, hLow)
            yHigh = product[0]
            yLow = product[1]
            offset += length
        }

        // Final block: bit lengths of the AAD and the ciphertext.
        yHigh = yHigh xor ((aad?.size ?: 0).toLong() * 8)
        yLow = yLow xor (ciphertextLength.toLong() * 8)
        val product = multiply(yHigh, yLow, hHigh, hLow)
        yHigh = product[0]
        yLow = product[1]

        val counterZero = ByteArray(BLOCK_SIZE)
        nonce.copyInto(counterZero, 0)
        counterZero[15] = 1
        val mask = ByteArray(BLOCK_SIZE)
        schedule.encryptBlock(counterZero, 0, mask, 0)

        val tag = ByteArray(BLOCK_SIZE)
        writeLong(tag, 0, yHigh xor readLong(mask, 0))
        writeLong(tag, 8, yLow xor readLong(mask, 8))
        return tag
    }

    /**
     * XORs [length] bytes from [input] with the AES-CTR keystream, starting at
     * counter block `inc32(J0)` as GCM requires.
     */
    private fun ctrXor(
        schedule: AesKeySchedule,
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        length: Int,
        output: ByteArray,
    ) {
        val counter = ByteArray(BLOCK_SIZE)
        nonce.copyInto(counter, 0)
        counter[15] = 1
        val keystream = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < length) {
            increment32(counter)
            schedule.encryptBlock(counter, 0, keystream, 0)
            val take = minOf(BLOCK_SIZE, length - offset)
            for (i in 0 until take) {
                output[offset + i] = (input[inputOffset + offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += take
        }
    }

    /** Increments the low 32 bits of a counter block, wrapping (GCM's inc32). */
    private fun increment32(counter: ByteArray) {
        for (i in 15 downTo 12) {
            val next = (counter[i].toInt() and 0xFF) + 1
            counter[i] = next.toByte()
            if (next <= 0xFF) return
        }
    }

    /**
     * Multiplication in GF(2^128) with GCM's bit ordering (bit 0 is the most
     * significant bit of byte 0). Shift-and-add over a pair of longs: 16× fewer
     * operations than the equivalent byte-array loop, and simple enough to read
     * against the spec.
     */
    private fun multiply(xHigh: Long, xLow: Long, yHigh: Long, yLow: Long): LongArray {
        var zHigh = 0L
        var zLow = 0L
        var vHigh = yHigh
        var vLow = yLow

        for (i in 0 until 64) {
            if ((xHigh ushr (63 - i)) and 1L == 1L) {
                zHigh = zHigh xor vHigh
                zLow = zLow xor vLow
            }
            val lowestBit = vLow and 1L
            vLow = (vLow ushr 1) or ((vHigh and 1L) shl 63)
            vHigh = vHigh ushr 1
            if (lowestBit == 1L) vHigh = vHigh xor R_HIGH
        }
        for (i in 0 until 64) {
            if ((xLow ushr (63 - i)) and 1L == 1L) {
                zHigh = zHigh xor vHigh
                zLow = zLow xor vLow
            }
            val lowestBit = vLow and 1L
            vLow = (vLow ushr 1) or ((vHigh and 1L) shl 63)
            vHigh = vHigh ushr 1
            if (lowestBit == 1L) vHigh = vHigh xor R_HIGH
        }
        return longArrayOf(zHigh, zLow)
    }

    private fun readLong(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return value
    }

    /**
     * Reads the 8 bytes at [half] (0 or 8) of a 16-byte GHASH block starting at
     * [blockOffset], treating anything past [blockLength] as zero padding.
     */
    private fun readLongPadded(source: ByteArray, blockOffset: Int, blockLength: Int, half: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            val index = half + i
            val byte = if (index < blockLength) source[blockOffset + index].toLong() and 0xFF else 0L
            value = (value shl 8) or byte
        }
        return value
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
        }
    }
}
