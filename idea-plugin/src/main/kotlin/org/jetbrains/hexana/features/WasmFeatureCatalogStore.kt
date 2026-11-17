package org.jetbrains.hexana.features

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.jetbrains.hexana.shared.features.CachedCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * On-disk cache of WebAssembly's `features.json`.
 *
 * The payload and its validation metadata are kept in two files so the JSON stays readable
 * as-is (handy when diagnosing a stale catalog) and so a `304` only has to rewrite the tiny
 * metadata file.
 *
 * Deliberately free of IntelliJ APIs and of the network so it can be tested against a
 * temporary directory; [WasmFeatureCatalogService] owns the platform wiring.
 */
class WasmFeatureCatalogStore(val root: Path) {

    private val catalogFile: Path get() = root.resolve(CATALOG_FILE)
    private val metadataFile: Path get() = root.resolve(METADATA_FILE)
    private val gson = Gson()

    private data class Metadata(
        @SerializedName("etag") val etag: String?,
        @SerializedName("fetchedAt") val fetchedAt: Long,
    )

    /** Returns `null` when nothing usable is cached. */
    fun read(): CachedCatalog? {
        if (!catalogFile.exists()) return null
        val json = try {
            catalogFile.readText()
        } catch (_: Exception) {
            return null
        }
        if (json.isBlank()) return null
        val metadata = readMetadata()
        return CachedCatalog(
            json = json,
            etag = metadata?.etag,
            // No metadata means the payload cannot be revalidated or aged; treat it as
            // ancient so the next read refreshes rather than trusting it forever.
            fetchedAtMillis = metadata?.fetchedAt ?: 0L,
        )
    }

    /** Replaces both files after a successful download. */
    fun write(json: String, etag: String?, fetchedAtMillis: Long) {
        Files.createDirectories(root)
        writeAtomically(catalogFile, json)
        writeAtomically(metadataFile, gson.toJson(Metadata(etag, fetchedAtMillis)))
    }

    /**
     * Records that the cached payload was revalidated: a `304` means the bytes we hold are
     * still current, so only the freshness timestamp moves.
     */
    fun touch(fetchedAtMillis: Long) {
        if (!catalogFile.exists()) return
        Files.createDirectories(root)
        writeAtomically(metadataFile, gson.toJson(Metadata(readMetadata()?.etag, fetchedAtMillis)))
    }

    private fun readMetadata(): Metadata? {
        if (!metadataFile.exists()) return null
        return try {
            gson.fromJson(metadataFile.readText(), Metadata::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes through a sibling temp file so a crash or a concurrently reading IDE never
     * observes a half-written catalog.
     */
    private fun writeAtomically(target: Path, text: String) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        temp.writeText(text)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val CATALOG_FILE = "features.json"
        const val METADATA_FILE = "features-meta.json"
    }
}
