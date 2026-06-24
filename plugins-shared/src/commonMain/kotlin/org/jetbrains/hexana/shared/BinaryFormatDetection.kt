package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Detects whether [buffer] starts with the JVM class file magic (`0xCAFEBABE`)
 * and is not a Mach-O universal (fat) binary, which shares the same magic.
 *
 * Disambiguation: the 32-bit big-endian integer at offset 4 in a Mach-O fat
 * binary is the architecture count (currently ≤ 18 labelled architectures).
 * In a Java class file the same bytes hold the minor version (u16) and the
 * start of the major version (u16 ≥ 43). Reading them as a single big-endian
 * int yields a value well above any realistic Mach-O arch count. A threshold
 * of 30 safely separates the two formats with room for future Mach-O additions.
 */
fun isClassFile(buffer: CommonByteBuffer): Boolean {
    if (buffer.size < 8) return false
    val hasMagic = buffer[0] == 0xCA.toByte() &&
        buffer[1] == 0xFE.toByte() &&
        buffer[2] == 0xBA.toByte() &&
        buffer[3] == 0xBE.toByte()
    if (!hasMagic) return false
    // Read bytes 4..7 as a big-endian 32-bit unsigned value.
    val word4 = ((buffer[4].toInt() and 0xFF) shl 24) or
        ((buffer[5].toInt() and 0xFF) shl 16) or
        ((buffer[6].toInt() and 0xFF) shl 8) or
        (buffer[7].toInt() and 0xFF)
    // Mach-O fat binaries have a small arch count here (≤ ~18);
    // Java class files have minor_version (u16) | major_version (u16 ≥ 43),
    // so the combined 32-bit value is always > 30.
    return word4 > 30
}

/**
 * Detects whether [buffer] starts with the DEX magic (`dex\n`).
 */
fun isDexFile(buffer: CommonByteBuffer): Boolean {
    if (buffer.size < 4) return false
    return buffer[0] == 0x64.toByte() && // 'd'
        buffer[1] == 0x65.toByte() &&     // 'e'
        buffer[2] == 0x78.toByte() &&     // 'x'
        buffer[3] == 0x0A.toByte()        // '\n'
}

/**
 * Detects whether [buffer] starts with the ZIP local-file-header magic
 * (`PK\x03\x04`).
 */
fun isZipFile(buffer: CommonByteBuffer): Boolean {
    if (buffer.size < 4) return false
    return buffer[0] == 0x50.toByte() &&
        buffer[1] == 0x4B.toByte() &&
        buffer[2] == 0x03.toByte() &&
        buffer[3] == 0x04.toByte()
}
