package org.jetbrains.hexana.providers.sbom

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.SbomLocator

/** Hard cap on the inflated payload to avoid a zip-bomb blowing up the heap. */
internal const val MAX_SBOM_INFLATED_BYTES = 128 * 1024 * 1024

/**
 * Decompresses a gzip-compressed byte array. Returns null on corrupt input or
 * if the inflated result would exceed [MAX_SBOM_INFLATED_BYTES].
 */
internal expect suspend fun gunzip(data: ByteArray): ByteArray?

/**
 * Extracts the CycloneDX JSON string for [locator] from [bytes].
 *
 * Returns null if the locator is out of bounds, the compressed length is
 * oversized, or the gzip stream is corrupt. Handles [SbomLocator.isGzip]=false
 * (plain JSON) transparently. This function is a coroutine suspend function
 * because the JS/wasmJs target uses the async DecompressionStream API.
 */
suspend fun extractSbomJson(bytes: CommonByteBuffer, locator: SbomLocator): String? {
    val offset = locator.fileOffset
    val length = locator.length
    if (offset < 0 || length <= 0 || offset + length > bytes.size.toLong()) return null
    if (length > MAX_SBOM_INFLATED_BYTES) return null

    val raw = bytes.slice(offset.toInt(), length.toInt()).toByteArray()
    val decoded = if (locator.isGzip) gunzip(raw) ?: return null else raw
    return decoded.decodeToString()
}