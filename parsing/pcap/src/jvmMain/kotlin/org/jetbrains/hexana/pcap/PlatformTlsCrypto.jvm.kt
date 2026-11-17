package org.jetbrains.hexana.pcap

internal actual fun platformTlsDecryptor(): TlsDecryptor = JvmTlsDecryptor

internal actual fun platformTlsKeyDeriver(): TlsKeyDeriver = TlsKeyDerivation

/** javax.crypto covers every suite in [TlsCipherSuite], including ChaCha20-Poly1305. */
actual fun isTlsDecryptionSupported(cipherSuite: TlsCipherSuite): Boolean = true

/**
 * Unbounded: javax.crypto reaches AES-NI, so even a capture of tens of
 * thousands of records opens fast enough not to need a ceiling.
 */
actual val tlsDecryptionByteBudget: Long = Long.MAX_VALUE
