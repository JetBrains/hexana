package org.jetbrains.hexana.parquet

/**
 * Render an INT96 physical value (12 little-endian bytes) as a base-10 string.
 *
 * INT96 is a fixed 12-byte value with no standard scalar Kotlin type. Parquet tooling
 * conventionally exposes it as a signed two's-complement 96-bit integer (see parquet-mr's
 * `Binary` -> `BigInteger` conversion). This routine reproduces that as a decimal string
 * without `java.math.BigInteger`, so it decodes identically on JVM, JS and Wasm.
 *
 * @throws IllegalArgumentException if [le] is not exactly 12 bytes.
 */
internal fun int96ToDecimalString(le: ByteArray): String {
    require(le.size == 12) { "INT96 must be 12 bytes, was ${le.size}" }

    // Big-endian magnitude, MSB first.
    val be = IntArray(12) { le[11 - it].toInt() and 0xFF }
    val negative = (be[0] and 0x80) != 0
    if (negative) {
        // Two's-complement negate to obtain the magnitude: invert all bytes, then add 1.
        var carry = 1
        for (i in 11 downTo 0) {
            val v = (be[i].inv() and 0xFF) + carry
            be[i] = v and 0xFF
            carry = v ushr 8
        }
    }

    // Convert the big-endian magnitude to decimal by repeated division by 1e9.
    var start = 0
    while (start < 12 && be[start] == 0) start++
    if (start == 12) return "0"

    val chunks = ArrayList<Int>()  // 9-digit base-1e9 chunks, least-significant first
    while (start < 12) {
        var rem = 0L
        for (i in start until 12) {
            val acc = (rem shl 8) or be[i].toLong()
            be[i] = (acc / 1_000_000_000L).toInt()
            rem = acc % 1_000_000_000L
        }
        chunks.add(rem.toInt())
        while (start < 12 && be[start] == 0) start++
    }

    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append(chunks[chunks.size - 1].toString())
    for (i in chunks.size - 2 downTo 0) {
        sb.append(chunks[i].toString().padStart(9, '0'))
    }
    return sb.toString()
}
