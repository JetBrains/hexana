package org.jetbrains.hexana.pcap.crypto

/**
 * A streaming message digest. Streaming rather than single-shot because HMAC
 * and HKDF feed a digest in several pieces and would otherwise have to
 * concatenate buffers on every block.
 *
 * `hexanaDocument` has its own single-shot pure-Kotlin SHA-256 for journal
 * hashing; it is module-internal and `CommonByteBuffer`-based, so it cannot be
 * reused here.
 */
internal interface Digest {
    /** Length of [digest]'s output in bytes. */
    val digestSize: Int

    /** The compression function's block size in bytes; HMAC pads keys to this. */
    val blockSize: Int

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset)

    /** Finalises the digest and resets it, ready for reuse. */
    fun digest(): ByteArray

    fun reset()
}
