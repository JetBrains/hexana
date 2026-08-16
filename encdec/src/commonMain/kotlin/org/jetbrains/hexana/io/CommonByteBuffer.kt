package org.jetbrains.hexana.io

interface CommonByteBuffer {
    fun get(): Byte
    fun get(dst: ByteArray, offset: Int, size: Int)

    /**
     * Absolute random-access read. Does **not** advance the cursor. Mirrors
     * `ByteArray[i]` so `buf[i]` is source-compatible with array-style access.
     */
    operator fun get(index: Int): Byte

    fun put(value: Byte)
    fun put(src: ByteArray, offset: Int, size: Int)
    fun putIntLE(value: Int)
    fun getIntLE(): Int
    fun position(): Int
    fun position(index: Int): CommonByteBuffer
    fun remaining(): Int
    fun hasRemaining(): Boolean
    fun getFloatLE(): Float
    fun getDoubleLE(): Double
    fun limit(): Int

    /**
     * Returns a buffer view over `[offset, offset + length)` of the underlying
     * bytes, with its own cursor starting at 0 and `limit() == length`.
     * Coordinates are translated; absolute file offsets are NOT preserved.
     * For a race-free shared cursor without coordinate translation, use [duplicate].
     */
    fun slice(offset: Int, length: Int): CommonByteBuffer

    /** Mirrors [ByteArray.size] for source compatibility. */
    val size: Int
        get() = limit()

    /** Mirrors [ByteArray.isEmpty] for source compatibility. */
    fun isEmpty(): Boolean = limit() == 0

    /**
     * Materializes a copy of the buffer's contents as a [ByteArray]. Use only
     * at boundaries that demand `ByteArray` (serialization, host-side I/O). For
     * in-process consumers prefer indexed access via [get] to avoid the copy.
     */
    fun toByteArray(): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = get(i)
        return out
    }

    /**
     * Returns a new [CommonByteBuffer] that shares the underlying bytes with this one,
     * but has an independent position cursor in the **same coordinate frame** (absolute
     * file offsets are preserved). Use this whenever a buffer is handed off to a code
     * path that may run concurrently with other readers — every reader needs its own
     * cursor.
     *
     * Unlike [slice], this does NOT translate offsets: `duplicate().position(0x1234)`
     * lands on the same byte as `original.position(0x1234)`.
     */
    fun duplicate(): CommonByteBuffer

    val lastIndex: Int
        get() = limit() - 1

    val indices: IntRange
        get() = IntRange(0, lastIndex)

    fun getULEB128(): Long {
        var result = 0L
        var shift = 0
        var bytesRead = 0

        while (true) {
            val byte = get().toInt() and 0xFF
            bytesRead++
            // Convert to Long BEFORE shifting — UInt is 32-bit, so `UInt shl 33`
            // would wrap mod 32 and lose high bits in 10-byte ULEB128 values.
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) {
                break
            }
            shift += 7
            // The WASM spec caps u64 LEB128 at 10 bytes (ceil(64/7)). An 11th
            // continuation byte is malformed; stop rather than drift past valid data.
            if (bytesRead >= 10) {
                break
            }
        }

        return result
    }

    fun getSLEB128(): Long {
        var result = 0L
        var shift = 0
        var bytesRead = 0

        while (true) {
            val byte = get().toInt() and 0xFF
            bytesRead++
            // Convert to Long BEFORE shifting — see getULEB128 for rationale.
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) {
                // Sign-extend if the sign bit (bit 6) of the final byte is set.
                // The data bits we just OR'd occupy positions [shift, shift+6];
                // the sign extension must fill positions [shift+7, ∞).
                if (shift + 7 < 64 && (byte and 0x40) != 0) {
                    result = result or ((-1L) shl (shift + 7))
                }
                break
            }
            shift += 7
            // 10-byte cap mirrors getULEB128 — s64 is also at most 10 bytes.
            if (bytesRead >= 10) {
                break
            }
        }

        return result
    }

    fun uint32(): UInt {
        return getULEB128().also {
            require(it <= UInt.MAX_VALUE.toLong()) { "Value exceeds UInt range" }
        }.toUInt()
    }

    fun sint32(): Int {
        return getSLEB128().also {
            require(it <= Int.MAX_VALUE) { "Value exceeds Int range" }
            require(it >= Int.MIN_VALUE) { "Value below Int range" }
        }.toInt()
    }

    fun uint64(): ULong {
        return getULEB128().also {
            require(it.toULong() <= ULong.MAX_VALUE) { "Value exceeds ULong range" }
        }.toULong()
    }

    fun sint64(): Long {
        return getSLEB128().also {
            require(it <= Long.MAX_VALUE) { "Value exceeds Long range" }
            require(it >= Long.MIN_VALUE) { "Value below Long range" }
        }
    }

    fun getShortLE(): Short {
        return ByteArray(2).let {
            get(it, 0, 2)
            it[1].toUByte().toInt().shl(8).or(it[0].toUByte().toInt())
        }.toShort()
    }

    fun getLongLE(): Long {
        return ByteArray(8).let {
            get(it, 0, 8)
            var value = 0L
            for (i in 0 until 8) {
                value = value or (it[i].toUByte().toLong().shl(i * 8))
            }
            value
        }
    }

    fun zigzag32(): Int = getULEB128().toInt().let { (it ushr 1) xor -(it and 1) }

    fun zigzag64(): Long = getULEB128().let { (it ushr 1) xor -(it and 1) }
}

inline fun <R> CommonByteBuffer.crossSectionOperation(op: () -> R): R {
    val position = position()
    try {
        return op()
    } finally {
        position(position)
    }
}

