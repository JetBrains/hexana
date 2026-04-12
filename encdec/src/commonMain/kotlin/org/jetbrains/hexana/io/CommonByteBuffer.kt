package org.jetbrains.hexana.io

interface CommonByteBuffer {
    fun get(): Byte
    fun get(dst: ByteArray, offset: Int, size: Int)
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
    fun slice(range: IntRange): CommonByteBuffer

    val lastIndex: Int
        get() = limit() - 1

    val indices: IntRange
        get() = IntRange(0, lastIndex)

    fun getULEB128(): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val byte = get().toUInt()
            result = result or ((byte and 0x7Fu) shl shift).toLong()
            if (byte and 0x80u == 0u) {
                break
            }
            shift +=7
        }

        return result
    }

    fun getSLEB128(): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val byte = get().toUInt()
            result = result or ((byte and 0x7Fu) shl shift).toLong()
            if (byte and 0x80u == 0u) {
                if (shift < 32 && (byte and 0x40u) != 0u) {
                    result = result or ((-1L) shl shift)
                    break
                }
                break
            }
            shift +=7
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
}

inline fun <R> CommonByteBuffer.crossSectionOperation(op: () -> R): R {
    val position = position()
    try {
        return op()
    } finally {
        position(position)
    }
}

