package org.jetbrains.hexana.nativebin.vuln

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.HexanaLog
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * Local OSV vulnerability database for the Maven ecosystem. Downloads the OSV GCS
 * export once, stores it under the IDE system directory, and builds an in-memory
 * `groupId:artifactId -> records` index for fully-offline matching — the binary's
 * component list never leaves the machine. Download/index are blocking; callers run
 * them under `Task.Backgroundable` (see the SBOM panel).
 */
@Service
class OsvDatabase {

    private val dbDir: Path = PathManager.getSystemDir().resolve("hexana").resolve("osv")
    private val zipFile: Path = dbDir.resolve("maven-all.zip")

    @Volatile
    private var cachedMatcher: VulnerabilityMatcher? = null

    @Volatile
    private var cachedRecordCount: Int = 0

    fun isPresent(): Boolean = zipFile.exists()

    fun lastUpdated(): Instant? =
        if (zipFile.exists()) runCatching { zipFile.getLastModifiedTime().toInstant() }.getOrNull() else null

    fun recordCount(): Int = cachedRecordCount

    /** Downloads the Maven OSV export to disk (blocking; off-EDT). Invalidates the cache. */
    fun download(indicator: ProgressIndicator?) {
        Files.createDirectories(dbDir)
        indicator?.text = "Downloading OSV vulnerability database…"
        val bytes = HttpClient().use { client ->
            runBlocking { client.request(MAVEN_ALL_ZIP_URL) { method = HttpMethod.Get }.body<ByteArray>() }
        }
        val tmp = dbDir.resolve("maven-all.zip.part")
        Files.write(tmp, bytes)
        Files.move(tmp, zipFile, StandardCopyOption.REPLACE_EXISTING)
        cachedMatcher = null
        cachedRecordCount = 0
    }

    /** Loads + caches a matcher from the on-disk zip, or null if absent/unreadable. Blocking; off-EDT. */
    fun loadMatcher(indicator: ProgressIndicator? = null): VulnerabilityMatcher? {
        cachedMatcher?.let { return it }
        if (!zipFile.exists()) return null
        indicator?.text = "Indexing OSV vulnerability database…"
        val records = readRecords() ?: return null
        cachedRecordCount = records.size
        return VulnerabilityMatcher(VulnerabilityMatcher.indexByMavenPackage(records)).also { cachedMatcher = it }
    }

    private fun readRecords(): List<OsvRecord>? = try {
        val records = ArrayList<OsvRecord>()
        Files.newInputStream(zipFile).use { fileIn ->
            ZipInputStream(BufferedInputStream(fileIn)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // ZipInputStream.read() returns -1 at the end of the *current* entry, so
                    // readBytes() yields exactly this entry's bytes.
                    if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                        OsvParser.parseRecord(zip.readBytes().toString(Charsets.UTF_8))?.let { records.add(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        records
    } catch (e: Exception) {
        HexanaLog.LOG.warn("Failed to read OSV database zip", e)
        null
    }

    companion object {
        const val MAVEN_ALL_ZIP_URL = "https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip"

        @JvmStatic
        fun getInstance(): OsvDatabase = service()
    }
}
