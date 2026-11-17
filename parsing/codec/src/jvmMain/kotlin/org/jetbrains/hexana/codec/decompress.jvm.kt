package org.jetbrains.hexana.codec

import io.airlift.compress.lz4.Lz4Decompressor
import io.airlift.compress.zstd.ZstdDecompressor
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

actual fun isCodecSupported(codec: Codec): Boolean = when (codec) {
    Codec.UNCOMPRESSED,
    Codec.SNAPPY,
    Codec.GZIP,
    Codec.ZSTD,
    Codec.LZ4 -> true
    Codec.LZ4_RAW,
    Codec.BROTLI,
    Codec.LZO -> false
}

actual fun decompress(codec: Codec, input: ByteArray, expectedSize: Int): ByteArray = when (codec) {
    Codec.UNCOMPRESSED -> input

    Codec.SNAPPY -> snappyUncompress(input, expectedSize)

    Codec.GZIP -> {
        val out = ByteArray(expectedSize)
        GZIPInputStream(ByteArrayInputStream(input)).use { gz ->
            var offset = 0
            while (offset < expectedSize) {
                val read = gz.read(out, offset, expectedSize - offset)
                if (read == -1) break
                offset += read
            }
            if (offset != expectedSize) {
                throw CodecException("gzip: decompressed $offset bytes but expected $expectedSize")
            }
        }
        out
    }

    Codec.ZSTD -> {
        val out = ByteArray(expectedSize)
        val written = ZstdDecompressor().decompress(input, 0, input.size, out, 0, expectedSize)
        if (written != expectedSize) {
            throw CodecException("zstd: decompressed $written bytes but expected $expectedSize")
        }
        out
    }

    Codec.LZ4 -> {
        // LZ4 with Hadoop framing (used by some Arrow IPC implementations)
        val out = ByteArray(expectedSize)
        val written = Lz4Decompressor().decompress(input, 0, input.size, out, 0, expectedSize)
        if (written != expectedSize) {
            throw CodecException("lz4: decompressed $written bytes but expected $expectedSize")
        }
        out
    }

    // LZ4_RAW (Parquet raw block) is intentionally deferred: aircompressor's raw
    // decoder requires sun.misc.Unsafe base-offset addressing, which is fragile under
    // the IntelliJ/JBR plugin runtime. Revisit with a safe impl + a real LZ4-Parquet
    // fixture in the Parquet-compression phase.
    Codec.LZ4_RAW -> throw CodecException("unsupported codec: LZ4_RAW")
    Codec.BROTLI -> throw CodecException("unsupported codec: BROTLI")
    Codec.LZO -> throw CodecException("unsupported codec: LZO")
}
