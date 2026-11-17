package org.jetbrains.hexana.pcap

/**
 * This platform's TLS record decryptor. Callers should go through
 * [TlsDecryptionContext.build] rather than using this directly.
 */
internal expect fun platformTlsDecryptor(): TlsDecryptor

/** This platform's TLS traffic-key deriver. */
internal expect fun platformTlsKeyDeriver(): TlsKeyDeriver

/**
 * Whether [cipherSuite] can be decrypted on this platform. The JVM covers
 * every suite Hexana knows about; the browser targets rely on a pure-Kotlin
 * AES-GCM implementation and cannot yet do ChaCha20-Poly1305.
 *
 * Mirrors `isCodecSupported` in `parsing/codec`: probe before promising the
 * user a result, and say plainly what is missing when the answer is no.
 */
expect fun isTlsDecryptionSupported(cipherSuite: TlsCipherSuite): Boolean

/**
 * How many bytes of TLS ciphertext one decryption context will open before it
 * stops and reports the remaining records as not decrypted. Guards the browser
 * targets, where AEAD throughput is low enough that a search or a sort over a
 * large capture would otherwise block the main thread for a minute.
 *
 * The limit is applied in capture order at build time, so which records fall
 * inside it is a property of the capture and not of the order rows are read —
 * see [TlsDecryptionContext].
 */
expect val tlsDecryptionByteBudget: Long
