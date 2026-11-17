package org.jetbrains.hexana.codec

/** Compression codecs used in Parquet and Arrow IPC. */
enum class Codec {
    UNCOMPRESSED,
    SNAPPY,
    GZIP,
    ZSTD,
    LZ4,
    LZ4_RAW,
    BROTLI,
    LZO,
}
