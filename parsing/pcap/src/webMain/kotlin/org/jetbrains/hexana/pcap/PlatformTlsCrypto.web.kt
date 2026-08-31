package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.pcap.crypto.PureKotlinTlsDecryptor
import org.jetbrains.hexana.pcap.crypto.PureKotlinTlsKeyDeriver

internal actual fun platformTlsDecryptor(): TlsDecryptor = PureKotlinTlsDecryptor()

internal actual fun platformTlsKeyDeriver(): TlsKeyDeriver = PureKotlinTlsKeyDeriver

/**
 * The browser targets decrypt AES-GCM through a pure-Kotlin implementation.
 * ChaCha20-Poly1305 has no pure-Kotlin implementation yet, so captures using
 * it stay encrypted here — [TlsDecryptionContext.undecryptableCipherSuites]
 * surfaces that in the packet tab header rather than leaving empty cells.
 */
actual fun isTlsDecryptionSupported(cipherSuite: TlsCipherSuite): Boolean =
    cipherSuite != TlsCipherSuite.CHACHA20_POLY1305_SHA256

/**
 * 2 MiB of ciphertext. Measured pure-Kotlin AES-128-GCM open throughput in
 * Chrome is ~1.2 MB/s on the JS target and ~8.7 MB/s on wasmJs, so this caps
 * the worst case at roughly two seconds of blocked main thread on JS and a
 * quarter of a second on wasmJs. Sized for JS because that is the target the
 * VS Code webview runs on, and so the only web host that can load a key log.
 *
 * Records past the limit read as not decrypted rather than as encrypted, so
 * the reason is visible instead of looking like missing key material.
 */
actual val tlsDecryptionByteBudget: Long = 2L * 1024 * 1024
