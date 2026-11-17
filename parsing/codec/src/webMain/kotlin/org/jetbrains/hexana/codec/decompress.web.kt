package org.jetbrains.hexana.codec

actual fun isCodecSupported(codec: Codec): Boolean = when (codec) {
    Codec.UNCOMPRESSED,
    Codec.SNAPPY -> true
    Codec.GZIP,
    Codec.ZSTD,
    Codec.LZ4,
    Codec.LZ4_RAW,
    Codec.BROTLI,
    Codec.LZO -> false
}

actual fun decompress(codec: Codec, input: ByteArray, expectedSize: Int): ByteArray = when (codec) {
    Codec.UNCOMPRESSED -> input
    Codec.SNAPPY -> snappyUncompress(input, expectedSize)
    else -> throw CodecException("unsupported codec on web: $codec")
}
