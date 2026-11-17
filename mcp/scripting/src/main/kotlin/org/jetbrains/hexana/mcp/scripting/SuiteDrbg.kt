package org.jetbrains.hexana.mcp.scripting

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Deterministic random byte generator for the suite profile: SHA-256 in counter
 * mode. Block `i` is `SHA-256(seed || counter_i)` with an 8-byte big-endian
 * counter starting at 0; [nextBytes] consumes the concatenated block stream.
 *
 * Backs `crypto.getRandomValues` and `crypto.randomUUID` so that a suite run is
 * reproducible for a fixed seed.
 */
internal class Sha256CounterDrbg(private val seed: ByteArray) {

    private var counter: Long = 0
    private var block: ByteArray = ByteArray(0)
    private var blockPos: Int = 0

    fun nextBytes(count: Int): ByteArray {
        require(count >= 0) { "count must be non-negative" }
        val out = ByteArray(count)
        var written = 0
        while (written < count) {
            if (blockPos == block.size) {
                block = nextBlock()
                blockPos = 0
            }
            val take = minOf(count - written, block.size - blockPos)
            block.copyInto(out, written, blockPos, blockPos + take)
            blockPos += take
            written += take
        }
        return out
    }

    private fun nextBlock(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(seed)
        val counterBytes = ByteArray(Long.SIZE_BYTES)
        for (i in 0 until Long.SIZE_BYTES) {
            counterBytes[i] = (counter ushr ((Long.SIZE_BYTES - 1 - i) * 8)).toByte()
        }
        counter++
        digest.update(counterBytes)
        return digest.digest()
    }

    companion object {

        /**
         * Derives the default seed: SHA-256 over the suite script text bytes
         * followed by each mapped artifact file's content bytes in role-name order.
         */
        fun deriveSeed(scriptText: String, artifactPaths: Map<String, Path>): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(scriptText.toByteArray(Charsets.UTF_8))
            for ((_, path) in artifactPaths.toSortedMap()) {
                DigestInputStream(Files.newInputStream(path), digest).use { input ->
                    input.drain()
                }
            }
            return digest.digest()
        }

        /** Decodes an explicit seed given as a hex string. */
        fun decodeHexSeed(hex: String): ByteArray {
            val cleaned = hex.trim()
            require(cleaned.isNotEmpty() && cleaned.length % 2 == 0 && cleaned.all { it.isHexDigit() }) {
                "Seed must be a non-empty hex string with an even number of digits, got \"$hex\""
            }
            return ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun InputStream.drain() {
            val buffer = ByteArray(64 * 1024)
            while (read(buffer) >= 0) {
                // DigestInputStream updates the digest as a side effect of reading.
            }
        }
    }
}
