package org.jetbrains.hexana.pcap.crypto

/**
 * AES in CBC mode (NIST SP 800-38A) for the TLS 1.2 MAC-then-encrypt suites.
 * No padding is applied or removed here: TLS padding is its own scheme,
 * verified together with the record MAC, so both directions work on raw blocks.
 *
 * [encrypt] exists so tests can build a sealed record to open again; nothing in
 * the parser encrypts.
 */
internal object AesCbc {

    const val BLOCK_SIZE = 16

    /**
     * Decrypts [ciphertext], whose length must be a non-zero multiple of
     * [BLOCK_SIZE], chaining from [iv]. Returns null if the shape is wrong.
     */
    fun decrypt(ciphertext: ByteArray, schedule: AesKeySchedule, iv: ByteArray): ByteArray? {
        if (iv.size != BLOCK_SIZE) return null
        if (ciphertext.isEmpty() || ciphertext.size % BLOCK_SIZE != 0) return null
        val output = ByteArray(ciphertext.size)
        val previous = iv.copyOf()
        val block = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < ciphertext.size) {
            schedule.decryptBlock(ciphertext, offset, block, 0)
            for (i in 0 until BLOCK_SIZE) {
                output[offset + i] = (block[i].toInt() xor previous[i].toInt()).toByte()
            }
            ciphertext.copyInto(previous, 0, offset, offset + BLOCK_SIZE)
            offset += BLOCK_SIZE
        }
        return output
    }

    /**
     * Encrypts [plaintext], whose length must be a non-zero multiple of
     * [BLOCK_SIZE], chaining from [iv]. Returns null if the shape is wrong.
     */
    fun encrypt(plaintext: ByteArray, schedule: AesKeySchedule, iv: ByteArray): ByteArray? {
        if (iv.size != BLOCK_SIZE) return null
        if (plaintext.isEmpty() || plaintext.size % BLOCK_SIZE != 0) return null
        val output = ByteArray(plaintext.size)
        val previous = iv.copyOf()
        val block = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < plaintext.size) {
            for (i in 0 until BLOCK_SIZE) {
                block[i] = (plaintext[offset + i].toInt() xor previous[i].toInt()).toByte()
            }
            schedule.encryptBlock(block, 0, output, offset)
            output.copyInto(previous, 0, offset, offset + BLOCK_SIZE)
            offset += BLOCK_SIZE
        }
        return output
    }
}
