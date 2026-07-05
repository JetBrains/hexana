package org.jetbrains.hexana.codec

/**
 * Decompress [input] using [codec] into exactly [expectedSize] bytes.
 *
 * [UNCOMPRESSED] returns the input unchanged (no copy).
 *
 * @throws CodecException if the codec is not supported on this platform, or
 *   if decompression fails or the decompressed length does not match [expectedSize].
 */
expect fun decompress(codec: Codec, input: ByteArray, expectedSize: Int): ByteArray

/**
 * Returns true if [codec] can be decompressed on the current platform.
 */
expect fun isCodecSupported(codec: Codec): Boolean
