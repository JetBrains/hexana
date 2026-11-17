package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.pcap.crypto.AesCbc
import org.jetbrains.hexana.pcap.crypto.AesGcm
import org.jetbrains.hexana.pcap.crypto.AesKeySchedule
import org.jetbrains.hexana.pcap.crypto.Hmac
import org.jetbrains.hexana.pcap.crypto.PureKotlinTlsDecryptor
import org.jetbrains.hexana.pcap.crypto.PureKotlinTlsKeyDeriver
import org.jetbrains.hexana.pcap.crypto.Sha1Digest
import org.jetbrains.hexana.pcap.crypto.Sha256Digest
import org.jetbrains.hexana.pcap.crypto.Sha512Digest
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Compares the pure-Kotlin crypto against the JVM's own implementation over
 * random inputs. This is what lets the JVM keep using javax.crypto (which is
 * hardware-accelerated) while the browser targets use the Kotlin code: the two
 * are held to producing identical bytes, so a capture decrypts the same either
 * way.
 *
 * Runs on a fixed seed so a failure is reproducible.
 */
class PureKotlinCryptoVsJavaxTest {

    /** The suites the pure-Kotlin path claims to support. */
    /** Every suite whose key schedule both derivers are expected to agree on. */
    private val aesSuites = TlsCipherSuite.entries
        .filter { it != TlsCipherSuite.CHACHA20_POLY1305_SHA256 }

    /** Of those, the AEAD ones — the only suites sealed with a single GCM call. */
    private val aeadSuites = aesSuites.filter { !it.isCbc }

    /** And the MAC-then-encrypt ones, which take a separate CBC and HMAC pass. */
    private val cbcSuites = aesSuites.filter { it.isCbc }

    @Test
    fun digestsMatchMessageDigest() {
        val random = Random(20260831)
        for (size in intArrayOf(0, 1, 55, 56, 63, 64, 65, 111, 112, 127, 128, 129, 1000, 5000)) {
            val input = random.nextBytes(size)
            assertEquals(
                hex(MessageDigest.getInstance("SHA-256").digest(input)),
                hex(Sha256Digest().also { it.update(input) }.digest()),
                "SHA-256 of $size bytes",
            )
            assertEquals(
                hex(MessageDigest.getInstance("SHA-384").digest(input)),
                hex(Sha512Digest(48).also { it.update(input) }.digest()),
                "SHA-384 of $size bytes",
            )
            assertEquals(
                hex(MessageDigest.getInstance("SHA-512").digest(input)),
                hex(Sha512Digest(64).also { it.update(input) }.digest()),
                "SHA-512 of $size bytes",
            )
        }
    }

    @Test
    fun hmacMatchesJavaxMac() {
        val random = Random(20260901)
        // Key sizes straddling both block sizes, since oversized keys get hashed.
        for (keySize in intArrayOf(1, 16, 32, 63, 64, 65, 127, 128, 129, 200)) {
            for (dataSize in intArrayOf(0, 1, 64, 65, 200, 1000)) {
                val key = random.nextBytes(keySize)
                val data = random.nextBytes(dataSize)

                val expected256 = Mac.getInstance("HmacSHA256").let {
                    it.init(SecretKeySpec(key, "HmacSHA256"))
                    it.doFinal(data)
                }
                val actual256 = Hmac({ Sha256Digest() }, key).let {
                    it.update(data)
                    it.doFinal()
                }
                assertEquals(hex(expected256), hex(actual256), "HmacSHA256 key=$keySize data=$dataSize")

                val expected384 = Mac.getInstance("HmacSHA384").let {
                    it.init(SecretKeySpec(key, "HmacSHA384"))
                    it.doFinal(data)
                }
                val actual384 = Hmac({ Sha512Digest(48) }, key).let {
                    it.update(data)
                    it.doFinal()
                }
                assertEquals(hex(expected384), hex(actual384), "HmacSHA384 key=$keySize data=$dataSize")
            }
        }
    }

    @Test
    fun aesGcmEncryptMatchesJavax() {
        val random = Random(20260902)
        for (keySize in intArrayOf(16, 32)) {
            for (plaintextSize in intArrayOf(0, 1, 16, 17, 64, 1000, 5000)) {
                for (aadSize in intArrayOf(0, 5, 16, 29)) {
                    val key = random.nextBytes(keySize)
                    val nonce = random.nextBytes(12)
                    val plaintext = random.nextBytes(plaintextSize)
                    val aad = random.nextBytes(aadSize)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.ENCRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, nonce),
                    )
                    cipher.updateAAD(aad)
                    val expected = cipher.doFinal(plaintext)

                    val actual = AesGcm.encrypt(plaintext, AesKeySchedule(key), nonce, aad, 16)
                    assertEquals(
                        hex(expected), hex(actual),
                        "AES-${keySize * 8}-GCM seal pt=$plaintextSize aad=$aadSize",
                    )
                }
            }
        }
    }

    @Test
    fun aesGcmDecryptOpensJavaxCiphertext() {
        val random = Random(20260903)
        for (keySize in intArrayOf(16, 32)) {
            for (plaintextSize in intArrayOf(0, 1, 16, 17, 64, 1000, 5000)) {
                val key = random.nextBytes(keySize)
                val nonce = random.nextBytes(12)
                val plaintext = random.nextBytes(plaintextSize)
                val aad = random.nextBytes(13)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce),
                )
                cipher.updateAAD(aad)
                val sealed = cipher.doFinal(plaintext)

                val opened = AesGcm.decrypt(sealed, AesKeySchedule(key), nonce, aad, 16)
                assertNotNull(opened, "AES-${keySize * 8}-GCM open pt=$plaintextSize")
                assertEquals(hex(plaintext), hex(opened))
            }
        }
    }

    /**
     * The whole point of the cross-check: the two [TlsKeyDeriver]s must agree,
     * because a capture opened in the IDE and in the browser has to show the
     * same plaintext.
     */
    @Test
    fun tls13TrafficKeysMatchJvmKeyDerivation() {
        val random = Random(20260904)
        for (suite in aesSuites) {
            repeat(20) {
                val secret = random.nextBytes(48)
                val expected = TlsKeyDerivation.deriveTrafficKeyAndIv(secret, suite)
                val actual = PureKotlinTlsKeyDeriver.deriveTrafficKeyAndIv(secret, suite)
                assertNotNull(expected, "${suite.displayName}: JVM derivation")
                assertNotNull(actual, "${suite.displayName}: pure-Kotlin derivation")
                assertEquals(hex(expected.first), hex(actual.first), "${suite.displayName} key")
                assertEquals(hex(expected.second), hex(actual.second), "${suite.displayName} iv")
                assertEquals(suite.keyLength, actual.first.size)
                assertEquals(suite.ivLength, actual.second.size)
            }
        }
    }

    @Test
    fun tls12KeyMaterialMatchesJvmKeyDerivation() {
        val random = Random(20260905)
        for (suite in aesSuites) {
            repeat(20) {
                val masterSecret = random.nextBytes(48)
                val serverRandom = random.nextBytes(32)
                val clientRandom = random.nextBytes(32)
                val expected = TlsKeyDerivation.deriveTls12Keys(
                    masterSecret, serverRandom, clientRandom, suite,
                )
                val actual = PureKotlinTlsKeyDeriver.deriveTls12Keys(
                    masterSecret, serverRandom, clientRandom, suite,
                )
                assertNotNull(expected, "${suite.displayName}: JVM derivation")
                assertNotNull(actual, "${suite.displayName}: pure-Kotlin derivation")
                assertEquals(expected, actual, suite.displayName)
            }
        }
    }

    /**
     * End to end at the [TlsDecryptor] level: a record sealed by javax.crypto
     * opens with the pure-Kotlin decryptor, for every suite it supports.
     */
    @Test
    fun pureKotlinDecryptorOpensJavaxSealedRecords() {
        val random = Random(20260906)
        val decryptor = PureKotlinTlsDecryptor()
        for (suite in aeadSuites) {
            val key = random.nextBytes(suite.keyLength)
            val nonce = random.nextBytes(12)
            val aad = random.nextBytes(5)
            val plaintext = random.nextBytes(200)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val sealed = cipher.doFinal(plaintext)

            val opened = decryptor.decrypt(sealed, key, nonce, aad, suite)
            assertNotNull(opened, suite.displayName)
            assertEquals(hex(plaintext), hex(opened), suite.displayName)

            // And the JVM decryptor opens the same record, as it does today.
            assertEquals(
                hex(plaintext),
                hex(JvmTlsDecryptor.decrypt(sealed, key, nonce, aad, suite)!!),
                suite.displayName,
            )
        }
    }

    /**
     * The same, for the MAC-then-encrypt suites: both decryptors must open a
     * record javax.crypto sealed with `AES/CBC/NoPadding`, over every block
     * count a real record can have.
     */
    @Test
    fun bothDecryptorsOpenJavaxSealedCbcRecords() {
        val random = Random(20260908)
        val pureKotlin = PureKotlinTlsDecryptor()
        for (suite in cbcSuites) {
            for (blocks in intArrayOf(1, 2, 5, 64)) {
                val key = random.nextBytes(suite.keyLength)
                val iv = random.nextBytes(16)
                val plaintext = random.nextBytes(blocks * 16)

                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val sealed = cipher.doFinal(plaintext)

                val label = "${suite.displayName} x$blocks"
                val reSealed = assertNotNull(AesCbc.encrypt(plaintext, AesKeySchedule(key), iv))
                assertEquals(hex(sealed), hex(reSealed), label)
                assertEquals(hex(plaintext), hex(assertNotNull(pureKotlin.decryptCbc(sealed, key, iv))), label)
                assertEquals(hex(plaintext), hex(assertNotNull(JvmTlsDecryptor.decryptCbc(sealed, key, iv))), label)
            }
        }
    }

    /** HMAC-SHA-1 backs the oldest CBC suites, so it gets the same treatment. */
    @Test
    fun hmacSha1MatchesJavaxMac() {
        val random = Random(20260909)
        for (keySize in intArrayOf(1, 20, 64, 65, 200)) {
            for (messageSize in intArrayOf(0, 1, 64, 1000)) {
                val key = random.nextBytes(keySize)
                val message = random.nextBytes(messageSize)
                val mac = Mac.getInstance("HmacSHA1")
                mac.init(SecretKeySpec(key, "HmacSHA1"))
                val expected = mac.doFinal(message)
                val actual = Hmac({ Sha1Digest() }, key).also { it.update(message) }.doFinal()
                assertEquals(hex(expected), hex(actual), "HMAC-SHA-1 key=$keySize msg=$messageSize")
            }
        }
    }

    /**
     * The schedule cache must key on the actual key bytes. Alternating between
     * two keys — exactly what the client and server directions of one stream do
     * — has to keep producing the right plaintext for each.
     */
    @Test
    fun decryptorCacheKeepsKeysApart() {
        val random = Random(20260907)
        val decryptor = PureKotlinTlsDecryptor()
        val suite = TlsCipherSuite.AES_128_GCM_SHA256
        val cases = (0 until 6).map {
            val key = random.nextBytes(16)
            val nonce = random.nextBytes(12)
            val plaintext = random.nextBytes(48)
            val sealed = AesGcm.encrypt(plaintext, AesKeySchedule(key), nonce, null, 16)
            Triple(key, nonce, plaintext to sealed)
        }
        // Two passes, interleaved, so entries are evicted and re-added.
        repeat(2) {
            for ((key, nonce, payload) in cases) {
                val (plaintext, sealed) = payload
                val opened = decryptor.decrypt(sealed, key, nonce, null, suite)
                assertEquals(hex(plaintext), hex(opened!!))
            }
        }
    }

    @Test
    fun pureKotlinDecryptorDeclinesChaCha20() {
        val decryptor = PureKotlinTlsDecryptor()
        assertNull(
            decryptor.decrypt(
                ByteArray(64), ByteArray(32), ByteArray(12), null,
                TlsCipherSuite.CHACHA20_POLY1305_SHA256,
            ),
        )
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
