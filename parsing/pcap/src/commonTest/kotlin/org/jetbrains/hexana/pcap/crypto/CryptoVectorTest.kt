package org.jetbrains.hexana.pcap.crypto

import org.jetbrains.hexana.codec.Inflate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known-answer tests for the pure-Kotlin primitives, using published vectors.
 *
 * These are the anchors that must hold on every target, JS and wasmJs included.
 * The exhaustive comparison against a reference implementation lives in
 * `PureKotlinCryptoVsJavaxTest` (jvmTest), which covers every cipher suite over
 * random inputs; between them the JS builds get the same assurance as the JVM.
 */
class CryptoVectorTest {

    // ---- SHA-2 (FIPS 180-4) ----

    @Test
    fun sha256KnownAnswers() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(sha256("abc".encodeToByteArray())),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(sha256(ByteArray(0))),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun sha512KnownAnswers() {
        assertEquals(
            "ddaf35a193617abacc417349ae204131" +
                "12e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd" +
                "454d4423643ce80e2a9ac94fa54ca49f",
            hex(Sha512Digest(64).also { it.update("abc".encodeToByteArray()) }.digest()),
        )
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007" +
                "d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f" +
                "63b931bd47417a81a538327af927da3e",
            hex(Sha512Digest(64).also { it.update(ByteArray(0)) }.digest()),
        )
    }

    @Test
    fun sha384KnownAnswers() {
        assertEquals(
            "cb00753f45a35e8bb5a03d699ac65007" +
                "272c32ab0eded1631a8b605a43ff5bed" +
                "8086072ba1e7cc2358baeca134c825a7",
            hex(Sha512Digest(48).also { it.update("abc".encodeToByteArray()) }.digest()),
        )
        assertEquals(
            "38b060a751ac96384cd9327eb1b1e36a" +
                "21fdb71114be07434c0cc7bf63f6e1da" +
                "274edebfe76f65fbd51ad2f14898b95b",
            hex(Sha512Digest(48).also { it.update(ByteArray(0)) }.digest()),
        )
    }

    /**
     * Splitting the same input across `update` calls must not change the digest.
     * Chunk sizes straddle both block boundaries (64 and 128 bytes), which is
     * where a buffering mistake would show up.
     */
    @Test
    fun digestsAreIndependentOfChunking() {
        val input = ByteArray(300) { (it * 7 + 3).toByte() }
        for (chunk in intArrayOf(1, 3, 63, 64, 65, 111, 127, 128, 129, 256)) {
            val sha256 = Sha256Digest()
            val sha384 = Sha512Digest(48)
            var offset = 0
            while (offset < input.size) {
                val take = minOf(chunk, input.size - offset)
                sha256.update(input, offset, take)
                sha384.update(input, offset, take)
                offset += take
            }
            assertEquals(hex(sha256(input)), hex(sha256.digest()), "SHA-256 in $chunk-byte chunks")
            assertEquals(
                hex(Sha512Digest(48).also { it.update(input) }.digest()),
                hex(sha384.digest()),
                "SHA-384 in $chunk-byte chunks",
            )
        }
    }

    /** `digest()` resets, so an instance can serve a second message. */
    @Test
    fun digestResetsAfterFinalising() {
        val digest = Sha256Digest()
        digest.update("abc".encodeToByteArray())
        digest.digest()
        digest.update("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(digest.digest()),
        )
    }

    /** Longer than one block, to exercise the multi-block padding path. */
    @Test
    fun sha256OfAThousandBlocks() {
        val input = ByteArray(64_000) { 'a'.code.toByte() }
        // Self-consistency: the same bytes fed as one call and as 1000 blocks.
        val oneShot = sha256(input)
        val streamed = Sha256Digest()
        for (i in 0 until 1000) streamed.update(input, i * 64, 64)
        assertEquals(hex(oneShot), hex(streamed.digest()))
    }

    // ---- HMAC (RFC 2104 / RFC 4231) ----

    @Test
    fun hmacSha256Rfc4231Case1() {
        // key = 0x0b repeated 20 times, data = "Hi There"
        val mac = Hmac({ Sha256Digest() }, ByteArray(20) { 0x0b })
        mac.update("Hi There".encodeToByteArray())
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hex(mac.doFinal()),
        )
    }

    @Test
    fun hmacSha256Rfc4231Case2() {
        val mac = Hmac({ Sha256Digest() }, "Jefe".encodeToByteArray())
        mac.update("what do ya want for nothing?".encodeToByteArray())
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(mac.doFinal()),
        )
    }

    @Test
    fun hmacSha384Rfc4231Case1() {
        val mac = Hmac({ Sha512Digest(48) }, ByteArray(20) { 0x0b })
        mac.update("Hi There".encodeToByteArray())
        assertEquals(
            "afd03944d84895626b0825f4ab46907f" +
                "15f9dadbe4101ec682aa034c7cebc59c" +
                "faea9ea9076ede7f4af152e8b2fa9cb6",
            hex(mac.doFinal()),
        )
    }

    /**
     * A key longer than the hash's block size must be hashed down first.
     * Getting this branch wrong is silent — it just yields a different MAC.
     */
    @Test
    fun hmacHashesOversizedKeys() {
        val longKey = ByteArray(200) { 0xaa.toByte() }
        val mac = Hmac({ Sha256Digest() }, longKey)
        mac.update("data".encodeToByteArray())
        val fromLongKey = hex(mac.doFinal())

        val hashedKey = sha256(longKey)
        val reference = Hmac({ Sha256Digest() }, hashedKey)
        reference.update("data".encodeToByteArray())
        assertEquals(hex(reference.doFinal()), fromLongKey)
    }

    /** `doFinal()` resets, so one keyed instance can MAC several messages. */
    @Test
    fun hmacResetsAfterDoFinal() {
        val mac = Hmac({ Sha256Digest() }, ByteArray(20) { 0x0b })
        mac.update("Hi There".encodeToByteArray())
        val first = hex(mac.doFinal())
        mac.update("Hi There".encodeToByteArray())
        assertEquals(first, hex(mac.doFinal()))
    }

    // ---- HKDF-Expand (RFC 5869) ----

    @Test
    fun hkdfExpandRfc5869AppendixA1() {
        val prk = unhex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val info = unhex("f0f1f2f3f4f5f6f7f8f9")
        val okm = TlsKeySchedule.hkdfExpand(info, 42, Hmac({ Sha256Digest() }, prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    fun hkdfExpandRfc5869AppendixA3EmptyInfo() {
        val prk = unhex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04")
        val okm = TlsKeySchedule.hkdfExpand(ByteArray(0), 42, Hmac({ Sha256Digest() }, prk))
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            hex(okm),
        )
    }

    /**
     * The `HkdfLabel` struct's exact encoding is what makes TLS 1.3 keys come
     * out right, so pin its bytes rather than only the derived output: length
     * as uint16, then "tls13 " + label with a one-byte length, then context.
     */
    @Test
    fun hkdfExpandLabelBuildsTheRfc8446Struct() {
        val secret = ByteArray(32) { it.toByte() }
        val viaLabel = TlsKeySchedule.hkdfExpandLabel(
            "key", ByteArray(0), 16, Hmac({ Sha256Digest() }, secret),
        )
        // uint16(16) | uint8(9) | "tls13 key" | uint8(0)
        val expectedInfo = byteArrayOf(0, 16, 9) + "tls13 key".encodeToByteArray() + byteArrayOf(0)
        val viaExpand = TlsKeySchedule.hkdfExpand(expectedInfo, 16, Hmac({ Sha256Digest() }, secret))
        assertEquals(hex(viaExpand), hex(viaLabel))
    }

    // ---- AES (FIPS-197) ----

    @Test
    fun aes128Appendix() {
        val key = unhex("000102030405060708090a0b0c0d0e0f")
        val plaintext = unhex("00112233445566778899aabbccddeeff")
        val output = ByteArray(16)
        AesKeySchedule(key).encryptBlock(plaintext, 0, output, 0)
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", hex(output))
    }

    @Test
    fun aes256Appendix() {
        val key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val plaintext = unhex("00112233445566778899aabbccddeeff")
        val output = ByteArray(16)
        AesKeySchedule(key).encryptBlock(plaintext, 0, output, 0)
        assertEquals("8ea2b7ca516745bfeafc49904b496089", hex(output))
    }

    @Test
    fun aesRejectsUnsupportedKeyLengths() {
        for (size in intArrayOf(0, 8, 15, 17, 31, 33, 64)) {
            var threw = false
            try {
                AesKeySchedule(ByteArray(size))
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw, "$size-byte key should be rejected")
        }
    }

    // ---- AES-GCM (NIST SP 800-38D test vectors) ----

    @Test
    fun aesGcmNistCase1EmptyPlaintext() {
        val schedule = AesKeySchedule(ByteArray(16))
        val sealed = AesGcm.encrypt(ByteArray(0), schedule, ByteArray(12), null, 16)
        assertEquals("58e2fccefa7e3061367f1d57a4e7455a", hex(sealed))
    }

    @Test
    fun aesGcmNistCase2SingleBlock() {
        val schedule = AesKeySchedule(ByteArray(16))
        val sealed = AesGcm.encrypt(ByteArray(16), schedule, ByteArray(12), null, 16)
        assertEquals(
            "0388dace60b6a392f328c2b971b2fe78" + "ab6e47d42cec13bdf53a67b21257bddf",
            hex(sealed),
        )
        assertEquals(
            hex(ByteArray(16)),
            hex(AesGcm.decrypt(sealed, schedule, ByteArray(12), null, 16)!!),
        )
    }

    @Test
    fun aesGcmRoundTripsWithAad() {
        val schedule = AesKeySchedule(ByteArray(32) { (it * 3).toByte() })
        val nonce = ByteArray(12) { (it + 1).toByte() }
        // Lengths either side of the 16-byte block boundary, plus an
        // unaligned AAD so the GHASH zero-padding path is exercised.
        for (plaintextSize in intArrayOf(0, 1, 15, 16, 17, 31, 32, 33, 100)) {
            for (aadSize in intArrayOf(0, 5, 13, 16, 20)) {
                val plaintext = ByteArray(plaintextSize) { (it * 11).toByte() }
                val aad = ByteArray(aadSize) { (it * 5).toByte() }
                val sealed = AesGcm.encrypt(plaintext, schedule, nonce, aad, 16)
                assertEquals(plaintextSize + 16, sealed.size)
                val opened = AesGcm.decrypt(sealed, schedule, nonce, aad, 16)
                assertEquals(hex(plaintext), hex(opened!!), "pt=$plaintextSize aad=$aadSize")
            }
        }
    }

    @Test
    fun aesGcmRejectsTamperedInput() {
        val schedule = AesKeySchedule(ByteArray(16) { it.toByte() })
        val nonce = ByteArray(12) { (it + 9).toByte() }
        val aad = "header".encodeToByteArray()
        val sealed = AesGcm.encrypt("payload".encodeToByteArray(), schedule, nonce, aad, 16)

        // A flipped ciphertext bit, a flipped tag bit, the wrong AAD and the
        // wrong nonce must all fail closed rather than return garbage.
        val flippedCiphertext = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(AesGcm.decrypt(flippedCiphertext, schedule, nonce, aad, 16))

        val flippedTag = sealed.copyOf().also {
            it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte()
        }
        assertNull(AesGcm.decrypt(flippedTag, schedule, nonce, aad, 16))

        assertNull(AesGcm.decrypt(sealed, schedule, nonce, "headet".encodeToByteArray(), 16))
        assertNull(AesGcm.decrypt(sealed, schedule, ByteArray(12), aad, 16))
    }

    @Test
    fun aesGcmRejectsNonTwelveByteNonces() {
        val schedule = AesKeySchedule(ByteArray(16))
        val sealed = AesGcm.encrypt(ByteArray(4), schedule, ByteArray(12), null, 16)
        for (size in intArrayOf(0, 8, 11, 13, 16)) {
            assertNull(AesGcm.decrypt(sealed, schedule, ByteArray(size), null, 16))
        }
    }

    /** The counter must start at inc32(J0), not J0 — otherwise block 0 is wrong. */
    @Test
    fun aesGcmKeystreamStartsAtCounterOne() {
        val schedule = AesKeySchedule(ByteArray(16))
        val nonce = ByteArray(12)
        val sealed = AesGcm.encrypt(ByteArray(16), schedule, nonce, null, 16)
        val counterZero = ByteArray(16).also { it[15] = 1 }
        val ekJ0 = ByteArray(16)
        schedule.encryptBlock(counterZero, 0, ekJ0, 0)
        // Encrypting zeros yields the keystream; it must NOT equal E_K(J0).
        assertNotEquals(hex(ekJ0), hex(sealed.copyOfRange(0, 16)))
    }

    // ---- SHA-1 (FIPS 180-4) ----

    @Test
    fun sha1KnownAnswers() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(sha1("abc".encodeToByteArray())))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex(sha1(ByteArray(0))))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            hex(sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        assertEquals(
            "a49b2446a02c645bf419f995b67091253a04a259",
            hex(
                sha1(
                    (
                        "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn" +
                            "hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
                        ).encodeToByteArray(),
                ),
            ),
        )
    }

    /** Split updates must land on the same digest as one shot, at every boundary. */
    @Test
    fun sha1BuffersAcrossUpdates() {
        val input = ByteArray(200) { (it * 31).toByte() }
        val oneShot = hex(sha1(input))
        for (split in intArrayOf(1, 55, 63, 64, 65, 128, 199)) {
            val digest = Sha1Digest()
            digest.update(input, 0, split)
            digest.update(input, split, input.size - split)
            assertEquals(oneShot, hex(digest.digest()), "split at $split")
        }
    }

    // ---- AES inverse cipher and CBC (FIPS-197, NIST SP 800-38A) ----

    @Test
    fun aesDecryptBlockInvertsTheAppendixVectors() {
        val cases = listOf(
            "000102030405060708090a0b0c0d0e0f" to "69c4e0d86a7b0430d8cdb78070b4c55a",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" to
                "8ea2b7ca516745bfeafc49904b496089",
        )
        for ((keyHex, ciphertextHex) in cases) {
            val output = ByteArray(16)
            AesKeySchedule(unhex(keyHex)).decryptBlock(unhex(ciphertextHex), 0, output, 0)
            assertEquals("00112233445566778899aabbccddeeff", hex(output), keyHex)
        }
    }

    @Test
    fun aesCbcKnownAnswers() {
        val iv = unhex("000102030405060708090a0b0c0d0e0f")
        val plaintext = unhex(
            "6bc1bee22e409f96e93d7e117393172a" +
                "ae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52ef" +
                "f69f2445df4f9b17ad2b417be66c3710",
        )
        val cases = listOf(
            // SP 800-38A F.2.1 / F.2.2
            "2b7e151628aed2a6abf7158809cf4f3c" to
                "7649abac8119b246cee98e9b12e9197d" +
                "5086cb9b507219ee95db113a917678b2" +
                "73bed6b8e3c1743b7116e69e22229516" +
                "3ff1caa1681fac09120eca307586e1a7",
            // SP 800-38A F.2.5 / F.2.6
            "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4" to
                "f58c4c04d6e5f1ba779eabfb5f7bfbd6" +
                "9cfc4e967edb808d679f777bc6702c7d" +
                "39f23369a9d9bacfa530e26304231461" +
                "b2eb05e2c39be9fcda6c19078c6a9d1b",
        )
        for ((keyHex, ciphertextHex) in cases) {
            val schedule = AesKeySchedule(unhex(keyHex))
            assertEquals(hex(plaintext), hex(assertNotNull(AesCbc.decrypt(unhex(ciphertextHex), schedule, iv))))
            assertEquals(ciphertextHex, hex(assertNotNull(AesCbc.encrypt(plaintext, schedule, iv))))
        }
    }

    @Test
    fun aesCbcRejectsMisshapenInput() {
        val schedule = AesKeySchedule(ByteArray(16))
        val iv = ByteArray(16)
        assertNull(AesCbc.decrypt(ByteArray(0), schedule, iv))
        assertNull(AesCbc.decrypt(ByteArray(17), schedule, iv))
        assertNull(AesCbc.decrypt(ByteArray(16), schedule, ByteArray(8)))
    }

    // ---- DEFLATE (RFC 1951) ----

    @Test
    fun inflateOpensAWholeRawStream() {
        val inflated = assertNotNull(Inflate().push(unhex(DEFLATE_ONE_SHOT)))
        assertEquals(DEFLATE_TEXT, inflated.decodeToString())
    }

    /**
     * OpenSSL's TLS compression emits a zlib wrapper on the first fragment
     * rather than the raw stream RFC 3749 describes, so the two bytes have to
     * be recognised and skipped or every record after it is garbage.
     */
    @Test
    fun inflateSkipsAZlibHeader() {
        val inflated = assertNotNull(Inflate().push(unhex(DEFLATE_ZLIB_WRAPPED)))
        assertEquals(DEFLATE_TEXT, inflated.decodeToString())
    }

    /**
     * The shape TLS compression actually takes: one sync-flushed chunk per
     * record, each returning only its own output, and later chunks referring
     * back to history from earlier ones — the second chunk here is nine bytes
     * for forty-seven bytes of text because all of it is a back-reference.
     */
    @Test
    fun inflateCarriesHistoryAcrossSyncFlushedChunks() {
        val inflate = Inflate()
        val expected = listOf(
            "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n",
            "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n",
            "POST /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n",
        )
        val chunks = listOf(DEFLATE_CHUNK_0, DEFLATE_CHUNK_1, DEFLATE_CHUNK_2)
        for ((index, chunk) in chunks.withIndex()) {
            val out = assertNotNull(inflate.push(unhex(chunk)), "chunk $index")
            assertEquals(expected[index], out.decodeToString(), "chunk $index")
        }
    }

    /** A record that arrives split mid-block must not lose or duplicate output. */
    @Test
    fun inflateResumesFromAPartialBlock() {
        val whole = unhex(DEFLATE_ONE_SHOT)
        for (split in intArrayOf(1, 3, 7, whole.size - 1)) {
            val inflate = Inflate()
            val first = assertNotNull(inflate.push(whole.copyOfRange(0, split)), "split at $split")
            val second = assertNotNull(
                inflate.push(whole.copyOfRange(split, whole.size)), "split at $split",
            )
            assertEquals(DEFLATE_TEXT, (first + second).decodeToString(), "split at $split")
        }
    }

    /** Corrupt input has to come back null rather than loop or throw. */
    @Test
    fun inflateRejectsCorruptInput() {
        val corrupt = unhex(DEFLATE_ONE_SHOT).also { it[2] = (it[2].toInt() xor 0xFF).toByte() }
        val inflate = Inflate()
        // Either it fails outright, or it decodes something that is not the text.
        assertNotEquals(DEFLATE_TEXT, inflate.push(corrupt)?.decodeToString())
    }

    private companion object {
        fun sha256(input: ByteArray): ByteArray =
            Sha256Digest().also { it.update(input) }.digest()

        fun sha1(input: ByteArray): ByteArray =
            Sha1Digest().also { it.update(input) }.digest()

        const val DEFLATE_TEXT =
            "the quick brown fox jumps over the lazy dog, the quick brown fox jumps again"

        const val DEFLATE_ONE_SHOT =
            "2bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228" +
                "014ae72456552aa4e4a7eb8079d81527a62766e60100"

        const val DEFLATE_ZLIB_WRAPPED =
            "78da2bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d" +
                "5228014ae72456552aa4e4a7eb8079d81527a62766e6010033ec1be8"

        const val DEFLATE_CHUNK_0 =
            "72770d51d0cfcc4b49add0cb28c9cd51f0080909d037d433e4e5f2c82f2eb152" +
                "48ad48cc2dc849d54bcecfe5e5e2e502000000ffff"
        const val DEFLATE_CHUNK_1 = "72274d39000000ffff"
        const val DEFLATE_CHUNK_2 = "0af00f26493d000000ffff"

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun unhex(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
