package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Detects whether [buffer] starts with the JVM class file magic (`0xCAFEBABE`).
 */
fun isClassFile(buffer: CommonByteBuffer): Boolean {
    if (buffer.size < 4) return false
    return buffer[0] == 0xCA.toByte() &&
        buffer[1] == 0xFE.toByte() &&
        buffer[2] == 0xBA.toByte() &&
        buffer[3] == 0xBE.toByte()
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
