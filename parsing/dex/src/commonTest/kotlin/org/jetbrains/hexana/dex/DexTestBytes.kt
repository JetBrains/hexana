package org.jetbrains.hexana.dex

/**
 * Little-endian byte builder for hand-written DEX structures in tests, the
 * counterpart of `ClassFileBytes` in the `javaBytecode` tests. DEX has no
 * checked-in fixtures; every test constructs exactly the bytes it needs.
 */
internal class DexBytes {
    private val out = ArrayList<Byte>()

    val size: Int get() = out.size

    fun u1(value: Int): DexBytes {
        out.add((value and 0xFF).toByte())
        return this
    }

    fun u2(value: Int): DexBytes {
        u1(value)
        u1(value shr 8)
        return this
    }

    fun u4(value: Long): DexBytes {
        u2((value and 0xFFFF).toInt())
        u2(((value shr 16) and 0xFFFF).toInt())
        return this
    }

    fun u4(value: Int): DexBytes = u4(value.toLong() and 0xFFFFFFFFL)

    fun uleb128(value: Int): DexBytes {
        var remaining = value
        do {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            u1(if (remaining != 0) byte or 0x80 else byte)
        } while (remaining != 0)
        return this
    }

    fun sleb128(value: Int): DexBytes {
        var remaining = value
        while (true) {
            val byte = remaining and 0x7F
            val signBitSet = (byte and 0x40) != 0
            remaining = remaining shr 7
            val done = (remaining == 0 && !signBitSet) || (remaining == -1 && signBitSet)
            u1(if (done) byte else byte or 0x80)
            if (done) break
        }
        return this
    }

    fun bytes(values: ByteArray): DexBytes {
        out.addAll(values.toList())
        return this
    }

    /** Appends zero bytes until the length is a multiple of [alignment]. */
    fun align(alignment: Int): DexBytes {
        while (out.size % alignment != 0) u1(0)
        return this
    }

    fun build(): ByteArray = out.toByteArray()
}
