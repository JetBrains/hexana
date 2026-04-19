package org.jetbrains.hexana.analytics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/**
 * Persists a random UUID to a local file the first time it is read.
 * Default path: ~/.hexana/analytics-id
 * No hostname, username, or machine-identifiable info is stored.
 */
class UuidDistinctIdProvider(
    private val storageFile: Path = Paths.get(
        System.getProperty("user.home"), ".hexana", "analytics-id"
    )
) : DistinctIdProvider {

    private val id: String by lazy { loadOrCreate() }

    override fun distinctId(): String = id

    private fun loadOrCreate(): String {
        try {
            if (Files.exists(storageFile)) {
                val existing = Files.readString(storageFile).trim()
                if (existing.isNotEmpty()) return existing
            }
        } catch (_: Exception) {
            // Fall through to create a new ID
        }

        val newId = UUID.randomUUID().toString()
        try {
            Files.createDirectories(storageFile.parent)
            Files.writeString(storageFile, newId)
        } catch (_: Exception) {
            // Best effort — use the ID in-memory even if persistence fails
        }
        return newId
    }
}
