package org.jetbrains.hexana.breakpoints

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.toCommonByteBuffer
import java.security.MessageDigest

/**
 * Computes a [BinaryIdentity] for a [VirtualFile].
 *
 * SHA-256 is computed by reading the file in 1 MB chunks via [CommonByteBuffer.get]
 * to avoid allocating a full-size array for large binaries (files can be 50 MB+).
 * Results are cached by (url, modificationStamp).
 *
 * [identityFor] must be called on Dispatchers.IO only; it performs blocking I/O.
 */
object BinaryIdentityProvider {

    private data class CacheKey(val url: String, val modificationStamp: Long)

    private val cache = LinkedHashMap<CacheKey, BinaryIdentity>(64, 0.75f, true)
    private const val MAX_CACHE_SIZE = 128
    private val lock = Any()

    /**
     * Returns a [BinaryIdentity] for [file], or null when the file cannot be read.
     *
     * Pass [elfBuildId] when the caller already holds an [ElfBinaryProvider] so the
     * more expensive ELF note parse is not repeated here.
     *
     * Must be called on Dispatchers.IO.
     */
    fun identityFor(file: VirtualFile, elfBuildId: String? = null): BinaryIdentity? {
        val key = CacheKey(file.url, file.modificationStamp)
        synchronized(lock) { cache[key] }?.let { return it }

        val sha = computeSha256(file) ?: return null
        val identity = BinaryIdentity(contentSha256 = sha, elfBuildId = elfBuildId)
        synchronized(lock) {
            cache[key] = identity
            if (cache.size > MAX_CACHE_SIZE) {
                cache.entries.iterator().also { it.next(); it.remove() }
            }
        }
        return identity
    }

    private fun computeSha256(file: VirtualFile): String? {
        return try {
            val buf = file.toCommonByteBuffer() ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(1024 * 1024)
            var pos = 0
            val total = buf.size
            while (pos < total) {
                val len = minOf(chunk.size, total - pos)
                buf.get(chunk, 0, len)
                digest.update(chunk, 0, len)
                pos += len
            }
            digest.digest().joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        } catch (e: Exception) {
            HexanaLog.LOG.warn("BinaryIdentityProvider: failed to hash ${file.url}", e)
            null
        }
    }
}
