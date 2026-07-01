package org.jetbrains.hexana.providers

import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Binary provider for Android `.dex` files. Extends [GenericBinaryProvider] so the
 * hex view works as before, and adds a [parsedDexFile] lazy property that parses
 * the DEX structure once and caches the result for all panels.
 */
class DexBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily parsed DEX file structure, or `null` when parsing fails.
     */
    val parsedDexFile: DexFile? by lazy {
        try {
            DexFileParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** Detects whether [buffer] starts with the DEX magic (`dex\n`). */
        fun isDexFile(buffer: CommonByteBuffer): Boolean {
            if (buffer.size < 4) return false
            return buffer[0] == 0x64.toByte() && // 'd'
                buffer[1] == 0x65.toByte() &&     // 'e'
                buffer[2] == 0x78.toByte() &&     // 'x'
                buffer[3] == 0x0A.toByte()        // '\n'
        }
    }
}
