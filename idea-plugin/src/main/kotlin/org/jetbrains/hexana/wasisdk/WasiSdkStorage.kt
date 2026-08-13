package org.jetbrains.hexana.wasisdk

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.annotations.TestOnly
import org.jetbrains.hexana.shared.WasiSdkReleases
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Machine-level storage for managed wasi-sdk installations.
 *
 * Root: [PathManager.getCommonDataPath]/Hexana/wasi-sdk
 *
 * java.nio.file is correct here: this is NOT a project-scoped file;
 * it is a machine-wide cache shared by all projects. The IDE VFS must
 * not be used for machine-level paths outside any project root.
 *
 * Light services must be final; tests use the [TestOnly] constructor to
 * inject a temp root and os/arch instead of subclassing.
 */
@Service(Service.Level.APP)
class WasiSdkStorage @TestOnly constructor(
    /** Root directory for all managed wasi-sdk installations. */
    val root: Path,
    private val os: String,
    private val arch: String,
) {

    /** Used by the service container. */
    constructor() : this(
        PathManager.getCommonDataPath().resolve("Hexana").resolve("wasi-sdk"),
        detectOs(),
        detectArch(),
    )

    private val LOG = Logger.getInstance(WasiSdkStorage::class.java)

    private val manifestFile: Path get() = root.resolve("manifest.json")
    private val lockFile: Path get() = root.resolve(".lock")
    private val tmpDir: Path get() = root.resolve(".tmp")

    private val gson = Gson()

    companion object {
        /** Lock is considered stale after this many seconds. */
        private const val LOCK_STALE_SECONDS = 120L

        @JvmStatic
        fun getInstance(): WasiSdkStorage = service()

        private fun detectOs(): String = when {
            SystemInfo.isMac -> "macos"
            SystemInfo.isWindows -> "windows"
            else -> "linux"
        }

        private fun detectArch(): String = when {
            System.getProperty("os.arch").contains("aarch64") -> "arm64"
            else -> "x86_64"
        }
    }

    // ── Manifest data classes ────────────────────────────────────────────────

    data class InstalledEntry(
        @SerializedName("version") val version: Int,
        @SerializedName("installedAt") val installedAt: Long,
    )

    data class Manifest(
        @SerializedName("schemaVersion") val schemaVersion: Int = 1,
        @SerializedName("activeVersion") val activeVersion: Int? = null,
        @SerializedName("installed") val installed: List<InstalledEntry> = emptyList(),
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Lists versions whose install directory exists on disk and contains
     * the expected lldb binary. Result is sorted descending (newest first).
     */
    fun installedVersions(): List<Int> {
        if (!root.isDirectory()) return emptyList()
        val lldbRel = WasiSdkReleases.lldbRelativePath(os)
        return root.listDirectoryEntries()
            .mapNotNull { dir ->
                if (!dir.isDirectory()) return@mapNotNull null
                val name = dir.fileName.toString()
                // Match "wasi-sdk-<N>.0"
                val version = parseInstallDirVersion(name) ?: return@mapNotNull null
                if (dir.resolve(lldbRel).exists()) version else null
            }
            .sortedDescending()
    }

    /** Returns the currently active version from the manifest, or null if none. */
    fun activeVersion(): Int? = readManifest().activeVersion

    /**
     * Sets the active version in the manifest. The version must be installed.
     * If [version] is null, the active version is cleared.
     */
    fun setActiveVersion(version: Int?) {
        val manifest = readManifest()
        writeManifest(manifest.copy(activeVersion = version))
    }

    /**
     * Returns the path to the lldb binary of the active wasi-sdk version,
     * or null if no version is active or the binary does not exist.
     */
    fun activeLldbPath(): Path? {
        val version = activeVersion() ?: return null
        return lldbPathForVersion(version)
    }

    /**
     * Returns the path to the lldb binary for a specific installed version,
     * or null if the binary does not exist.
     */
    fun lldbPathForVersion(version: Int): Path? {
        val installDir = root.resolve(WasiSdkReleases.installDirName(version))
        val lldb = installDir.resolve(WasiSdkReleases.lldbRelativePath(os))
        return if (lldb.exists()) lldb else null
    }

    /** The directory where a version will be installed. */
    fun installDir(version: Int): Path = root.resolve(WasiSdkReleases.installDirName(version))

    /** A staging directory for an in-progress install. */
    fun stagingDir(version: Int): Path = tmpDir.resolve("staging-$version")

    /** A temp file path for downloading into. */
    fun downloadTempFile(version: Int): Path {
        Files.createDirectories(tmpDir)
        return tmpDir.resolve("wasi-sdk-$version.tar.gz.part")
    }

    /** Records an installed version in the manifest and sets it as active. */
    fun recordInstalled(version: Int) {
        val manifest = readManifest()
        val existing = manifest.installed.filter { it.version != version }
        val updated = existing + InstalledEntry(version, System.currentTimeMillis())
        writeManifest(manifest.copy(activeVersion = version, installed = updated.sortedByDescending { it.version }))
    }

    /**
     * Acquires the lock file. Returns a [FileLock] on success, or null if the
     * lock is currently held and not stale. The caller is responsible for
     * releasing the lock via [releaseLock].
     */
    fun acquireLock(): FileLock? {
        Files.createDirectories(root)
        return try {
            val raf = RandomAccessFile(lockFile.toFile(), "rw")
            val channel = raf.channel
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                raf.close()
                return null
            }
            // Write pid + timestamp
            raf.seek(0)
            raf.write("${ProcessHandle.current().pid()}:${System.currentTimeMillis()}\n".toByteArray())
            lock
        } catch (e: Exception) {
            LOG.warn("Failed to acquire wasi-sdk lock", e)
            null
        }
    }

    /** Releases a previously acquired lock and deletes the lock file. */
    fun releaseLock(lock: FileLock) {
        try {
            lock.channel().close()
            lockFile.deleteIfExists()
        } catch (e: Exception) {
            LOG.warn("Failed to release wasi-sdk lock", e)
        }
    }

    /**
     * Sweeps the tmp directory, deleting entries older than one day.
     * Called at startup or before an install.
     */
    fun sweepTmp() {
        if (!tmpDir.isDirectory()) return
        val oneDayMs = 86_400_000L
        val now = System.currentTimeMillis()
        try {
            tmpDir.listDirectoryEntries().forEach { entry ->
                try {
                    val mtime = Files.getLastModifiedTime(entry).toMillis()
                    if (now - mtime > oneDayMs) {
                        entry.toFile().deleteRecursively()
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to sweep tmp entry: $entry", e)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to sweep wasi-sdk tmp dir", e)
        }
    }

    // ── Manifest I/O ─────────────────────────────────────────────────────────

    /**
     * Reads the manifest from disk. On missing or corrupt manifest, rebuilds
     * from the disk state (installed directories) with no active version.
     */
    fun readManifest(): Manifest {
        if (!manifestFile.exists()) return rebuildManifest()
        return try {
            val json = manifestFile.readText()
            gson.fromJson(json, Manifest::class.java) ?: rebuildManifest()
        } catch (_: JsonSyntaxException) {
            LOG.warn("wasi-sdk manifest is corrupt, rebuilding from disk")
            rebuildManifest()
        }
    }

    internal fun writeManifest(manifest: Manifest) {
        Files.createDirectories(root)
        val json = gson.toJson(manifest)
        val tmp = manifestFile.resolveSibling("manifest.json.tmp")
        tmp.writeText(json)
        Files.move(tmp, manifestFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun rebuildManifest(): Manifest {
        val versions = installedVersions()
        val entries = versions.map { v -> InstalledEntry(v, 0L) }
        return Manifest(schemaVersion = 1, activeVersion = null, installed = entries)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseInstallDirVersion(name: String): Int? {
        // Matches "wasi-sdk-<N>.0"
        if (!name.startsWith("wasi-sdk-")) return null
        val rest = name.removePrefix("wasi-sdk-")
        if (!rest.endsWith(".0")) return null
        return rest.removeSuffix(".0").toIntOrNull()
    }

    /** Returns the os string used for asset naming on this machine. */
    fun currentOs(): String = os

    /** Returns the arch string used for asset naming on this machine. */
    fun currentArch(): String = arch

    /** Whether the executable bit is set (relevant on macOS/Linux). */
    fun isExecutable(path: Path): Boolean = path.isExecutable()
}
