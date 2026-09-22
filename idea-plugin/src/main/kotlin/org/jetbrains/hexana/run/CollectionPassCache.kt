package org.jetbrains.hexana.run

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic content-keyed cache for collection passes: keyed by the SHA-256 hex of
 * the file bytes so results survive file renames but invalidate on content change.
 *
 * The fast-path lookup [tryGet] uses a cheap (path, lastModified, length) triplet to
 * avoid reading the file at all when nothing has changed on disk since the last hit.
 */
internal class CollectionPassCache<V> {

    /** Primary store: sha256hex -> result. */
    private val byHash = ConcurrentHashMap<String, V>()

    /** Cheap key: path -> (lastModified, length, sha256hex). Updated on every [compute]. */
    private val byMeta = ConcurrentHashMap<String, Triple<Long, Long, String>>()

    /**
     * Returns the cached value for [file] without reading its bytes if the
     * (lastModified, length) pair matches a previously recorded entry.
     * Returns null on any cache miss or I/O error.
     */
    fun tryGet(file: File): V? {
        if (!file.exists() || file.isDirectory) return null
        val meta = byMeta[file.path] ?: return null
        val (mtime, length, hash) = meta
        if (file.lastModified() != mtime || file.length() != length) return null
        return byHash[hash]
    }

    /**
     * Reads [file], computes its SHA-256, and returns the cached value if present.
     * If absent, calls [block] to produce the value, stores it, and returns it.
     * Returns null on any I/O or computation error.
     */
    fun compute(file: File, block: (ByteArray) -> V?): V? {
        if (!file.exists() || file.isDirectory) return null
        return try {
            val bytes = file.readBytes()
            val hash = sha256Hex(bytes)
            val cached = byHash[hash]
            if (cached != null) {
                byMeta[file.path] = Triple(file.lastModified(), file.length(), hash)
                return cached
            }
            val result = block(bytes) ?: return null
            byHash[hash] = result
            byMeta[file.path] = Triple(file.lastModified(), file.length(), hash)
            result
        } catch (_: Exception) {
            null
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
