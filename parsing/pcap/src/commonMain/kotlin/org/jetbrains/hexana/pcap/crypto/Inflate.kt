package org.jetbrains.hexana.pcap.crypto

/**
 * Streaming raw-DEFLATE decompressor (RFC 1951), used for TLS-level record
 * compression (RFC 3749). Pure Kotlin on every target: the JVM has
 * `java.util.zip.Inflater`, but the browser has no synchronous inflate at all
 * — `DecompressionStream` is async — and a TLS record is small enough that one
 * shared implementation costs nothing and is the same code the tests cover.
 *
 * Streaming matters here. TLS compresses a connection, not a record: the
 * DEFLATE stream runs the length of one direction, so a back-reference in
 * record five may point into record two. Each record is fed to [push] in
 * capture order and the history carries across.
 *
 * TLS 1.2 requires a flush at every record boundary, so a [push] normally ends
 * on a block and byte boundary. When it does not — a truncated capture, or a
 * record split across TCP segments — the partially decoded block is rolled
 * back and retried once the next [push] supplies the rest.
 *
 * A leading zlib header (RFC 1950) is skipped if the stream carries one.
 * RFC 3749 specifies raw DEFLATE, but OpenSSL compresses through zlib's
 * default wrapper, so real captures put `78 9c` in front of the first record's
 * fragment and nothing in front of the rest.
 */
internal class Inflate(private val maxOutput: Int = DEFAULT_MAX_OUTPUT) {

    private var input = ByteArray(0)
    /** Bit offset into [input]; the low three bits are the offset within a byte. */
    private var bitPos = 0
    private var output = ByteArray(INITIAL_OUTPUT)
    private var outLen = 0
    private var finished = false

    /** Set once the stream is corrupt, after which every [push] returns null. */
    private var failed = false

    /** Whether the optional zlib header has been looked for yet. */
    private var headerChecked = false

    /**
     * Feeds the next chunk of the compressed stream and returns everything it
     * decoded, or `null` if the stream is corrupt or has outgrown [maxOutput].
     * Once null is returned the instance stays failed.
     */
    fun push(data: ByteArray): ByteArray? {
        if (failed) return null
        input = if (input.isEmpty()) data else input + data
        if (!headerChecked && input.size >= ZLIB_HEADER_SIZE) {
            headerChecked = true
            if (looksLikeZlibHeader(input)) bitPos = ZLIB_HEADER_SIZE * 8
        }
        val producedFrom = outLen
        while (!finished) {
            val markBit = bitPos
            val markOut = outLen
            val result = try {
                inflateBlock()
            } catch (_: Underflow) {
                bitPos = markBit
                outLen = markOut
                break
            } catch (_: Corrupt) {
                failed = true
                return null
            }
            if (result) finished = true
            // A complete block leaves nothing to redo, so the bytes behind the
            // cursor can go; the LZ77 window lives in `output`, not here.
            if (bitPos >= 8) {
                input = input.copyOfRange(bitPos ushr 3, input.size)
                bitPos = bitPos and 7
            }
        }
        return output.copyOfRange(producedFrom, outLen)
    }

    // ---- bit reader ----

    private fun bits(count: Int): Int {
        var value = 0
        for (i in 0 until count) {
            val index = bitPos ushr 3
            if (index >= input.size) throw Underflow()
            value = value or (((input[index].toInt() ushr (bitPos and 7)) and 1) shl i)
            bitPos++
        }
        return value
    }

    // ---- block decoding ----

    /** Decodes one DEFLATE block; returns whether it was the final one. */
    private fun inflateBlock(): Boolean {
        val isFinal = bits(1) == 1
        when (bits(2)) {
            0 -> storedBlock()
            1 -> compressedBlock(fixedLiteralTable(), fixedDistanceTable())
            2 -> {
                val tables = dynamicTables()
                compressedBlock(tables.first, tables.second)
            }
            else -> throw Corrupt()
        }
        return isFinal
    }

    private fun storedBlock() {
        bitPos = (bitPos + 7) and (7.inv())
        val start = bitPos ushr 3
        if (start + 4 > input.size) throw Underflow()
        val len = (input[start].toInt() and 0xFF) or ((input[start + 1].toInt() and 0xFF) shl 8)
        val nlen = (input[start + 2].toInt() and 0xFF) or ((input[start + 3].toInt() and 0xFF) shl 8)
        if (len != nlen.inv() and 0xFFFF) throw Corrupt()
        if (start + 4 + len > input.size) throw Underflow()
        ensureCapacity(len)
        input.copyInto(output, outLen, start + 4, start + 4 + len)
        outLen += len
        bitPos = (start + 4 + len) shl 3
    }

    private fun compressedBlock(literals: Huffman, distances: Huffman) {
        while (true) {
            val symbol = decodeSymbol(literals)
            when {
                symbol < 256 -> {
                    ensureCapacity(1)
                    output[outLen++] = symbol.toByte()
                }

                symbol == 256 -> return

                else -> {
                    val lengthIndex = symbol - 257
                    if (lengthIndex >= LENGTH_BASE.size) throw Corrupt()
                    val length = LENGTH_BASE[lengthIndex] + bits(LENGTH_EXTRA[lengthIndex])
                    val distanceSymbol = decodeSymbol(distances)
                    if (distanceSymbol >= DISTANCE_BASE.size) throw Corrupt()
                    val distance = DISTANCE_BASE[distanceSymbol] + bits(DISTANCE_EXTRA[distanceSymbol])
                    if (distance > outLen) throw Corrupt()
                    ensureCapacity(length)
                    // Byte at a time on purpose: overlapping copies are legal
                    // and are how DEFLATE encodes runs.
                    var from = outLen - distance
                    repeat(length) { output[outLen++] = output[from++] }
                }
            }
        }
    }

    private fun dynamicTables(): Pair<Huffman, Huffman> {
        val literalCount = bits(5) + 257
        val distanceCount = bits(5) + 1
        val codeLengthCount = bits(4) + 4
        if (literalCount > MAX_LITERAL_CODES || distanceCount > MAX_DISTANCE_CODES) throw Corrupt()

        val codeLengths = IntArray(CODE_LENGTH_ORDER.size)
        for (i in 0 until codeLengthCount) codeLengths[CODE_LENGTH_ORDER[i]] = bits(3)
        val codeLengthTable = Huffman(codeLengths, codeLengths.size)

        val lengths = IntArray(literalCount + distanceCount)
        var index = 0
        while (index < lengths.size) {
            when (val symbol = decodeSymbol(codeLengthTable)) {
                in 0..15 -> lengths[index++] = symbol
                16 -> {
                    if (index == 0) throw Corrupt()
                    val previous = lengths[index - 1]
                    repeat(3 + bits(2)) { if (index < lengths.size) lengths[index++] = previous }
                }

                17 -> repeat(3 + bits(3)) { if (index < lengths.size) lengths[index++] = 0 }
                18 -> repeat(11 + bits(7)) { if (index < lengths.size) lengths[index++] = 0 }
                else -> throw Corrupt()
            }
        }
        return Huffman(lengths, literalCount) to
            Huffman(lengths.copyOfRange(literalCount, lengths.size), distanceCount)
    }

    /**
     * Canonical Huffman decoding, one bit at a time, in the counts-and-offsets
     * form from zlib's `puff` reference decoder. No lookup table: a TLS record
     * is at most 16 KiB, so building one would cost more than it saves.
     */
    private fun decodeSymbol(table: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (length in 1..MAX_CODE_BITS) {
            code = code or bits(1)
            val count = table.count[length]
            if (code - first < count) return table.symbol[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw Corrupt()
    }

    private fun ensureCapacity(extra: Int) {
        if (outLen + extra > maxOutput) throw Corrupt()
        if (outLen + extra <= output.size) return
        var size = output.size
        while (size < outLen + extra) size *= 2
        output = output.copyOf(size)
    }

    /** A canonical Huffman table: symbol counts per code length, symbols in code order. */
    private class Huffman(lengths: IntArray, symbolCount: Int) {
        val count = IntArray(MAX_CODE_BITS + 1)
        val symbol = IntArray(symbolCount)

        init {
            for (i in 0 until symbolCount) {
                val length = lengths[i]
                if (length > MAX_CODE_BITS) throw Corrupt()
                count[length]++
            }
            // Length zero means "symbol unused", so it is excluded from the
            // offsets rather than shifting every other length along.
            val offsets = IntArray(MAX_CODE_BITS + 2)
            for (length in 1..MAX_CODE_BITS) offsets[length + 1] = offsets[length] + count[length]
            for (i in 0 until symbolCount) {
                val length = lengths[i]
                if (length != 0) symbol[offsets[length]++] = i
            }
        }
    }

    /** Not enough input yet; the caller rolls back and waits for the next chunk. */
    private class Underflow : Exception(null, null)

    /** The stream does not decode; nothing later in it can be trusted either. */
    private class Corrupt : Exception(null, null)

    private companion object {
        const val INITIAL_OUTPUT = 8 * 1024
        const val ZLIB_HEADER_SIZE = 2
        const val DEFLATE_COMPRESSION_METHOD = 8
        const val MAX_CODE_BITS = 15
        const val MAX_LITERAL_CODES = 286
        const val MAX_DISTANCE_CODES = 30

        /**
         * 16 MiB of plaintext per direction. TLS compression is decompressed
         * eagerly — a DEFLATE stream cannot be entered halfway — so unlike the
         * AEAD path there is no way to defer the work to the rows a user
         * actually looks at. The ceiling keeps a compressed capture from
         * turning into an unbounded allocation at open time.
         */
        const val DEFAULT_MAX_OUTPUT = 16 * 1024 * 1024

        val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        val LENGTH_EXTRA = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
        )
        val DISTANCE_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
        )
        val DISTANCE_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
        )

        /**
         * RFC 1950 §2.2: low nibble 8 for DEFLATE, no preset dictionary, and
         * the two bytes together a multiple of 31. A raw DEFLATE stream can
         * only collide with that by starting with a stored block whose length
         * field then has to match, which no real encoder emits.
         */
        fun looksLikeZlibHeader(input: ByteArray): Boolean {
            val cmf = input[0].toInt() and 0xFF
            val flg = input[1].toInt() and 0xFF
            if (cmf and 0x0F != DEFLATE_COMPRESSION_METHOD) return false
            if (flg and 0x20 != 0) return false
            return ((cmf shl 8) or flg) % 31 == 0
        }

        /** The fixed literal/length code of RFC 1951 §3.2.6. */
        fun fixedLiteralTable(): Huffman {
            val lengths = IntArray(288)
            for (i in 0..143) lengths[i] = 8
            for (i in 144..255) lengths[i] = 9
            for (i in 256..279) lengths[i] = 7
            for (i in 280..287) lengths[i] = 8
            return Huffman(lengths, lengths.size)
        }

        /** The fixed distance code: thirty-two 5-bit codes. */
        fun fixedDistanceTable(): Huffman = Huffman(IntArray(32) { 5 }, 32)
    }
}
