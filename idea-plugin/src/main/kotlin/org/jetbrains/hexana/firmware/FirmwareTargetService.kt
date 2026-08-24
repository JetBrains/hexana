package org.jetbrains.hexana.firmware

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.ElfHeaderFacts
import org.jetbrains.hexana.providers.firmware.TargetProfile
import org.jetbrains.hexana.providers.firmware.detectTargetProfile
import org.jetbrains.hexana.providers.firmware.selectBackend

/**
 * Resolved firmware target: the detected profile, selected backend, and discovered sidecars.
 *
 * [selection] is always a [BackendSelection.Ok] — callers that need to display failure reasons
 * should call [selectBackend] directly or call [FirmwareTargetService.resolve] and handle null.
 */
public data class FirmwareTarget(
    val profile: TargetProfile,
    val selection: BackendSelection,
    val sidecars: ElfSidecars,
)

/**
 * Project-level cache for resolved [FirmwareTarget] instances.
 *
 * [resolve] detects the target profile from the ELF header and DTS/config sidecars, runs
 * backend selection, and caches the result keyed by `(VirtualFile.url, modificationStamp)`.
 * The cache is invalidated by a [BulkFileListener] when any of the ELF, map, DTS, or config
 * files change.
 *
 * This service MUST NOT be called on the EDT. All resolution work runs on [Dispatchers.IO].
 *
 * This is a light service; no plugin.xml registration is required (IntelliJ Platform 2024.1+
 * resolves `@Service`-annotated project services automatically).
 */
@Service(Service.Level.PROJECT)
public final class FirmwareTargetService(private val project: Project) {

    private data class CacheKey(val url: String, val modStamp: Long)

    @Volatile private var cache: Map<CacheKey, FirmwareTarget?> = emptyMap()

    // Tracked urls for cache invalidation: elf url → set of sidecar urls.
    @Volatile private var watchedUrls: Map<String, Set<String>> = emptyMap()

    init {
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val changedUrls = events.mapNotNull { it.file?.url }.toSet()
                    val watched = watchedUrls
                    val affectedElfs = watched.entries
                        .filter { (elfUrl, sidecars) ->
                            elfUrl in changedUrls || sidecars.any { it in changedUrls }
                        }
                        .map { it.key }
                        .toSet()
                    if (affectedElfs.isNotEmpty()) {
                        cache = cache.filterKeys { it.url !in affectedElfs }
                    }
                }
            },
        )
    }

    /**
     * Resolves the [FirmwareTarget] for [elf].
     *
     * Returns null when the ELF is in-memory (no disk path), when the ELF header cannot
     * be parsed, or when backend selection fails ([BackendSelection.Fail]).
     * Callers can call [selectBackend] directly to obtain the [BackendSelection.Fail] reason.
     *
     * This method MUST be called off the EDT. It performs VFS lookups and file reads on
     * [Dispatchers.IO].
     */
    public suspend fun resolve(elf: VirtualFile): FirmwareTarget? = withContext(Dispatchers.IO) {
        val key = CacheKey(elf.url, elf.modificationStamp)
        cache[key]?.let { return@withContext it }

        val discovery = project.service<ElfSidecarDiscovery>()
        val sidecars = discovery.discover(elf)

        val headerFacts = readElfHeaderFacts(elf) ?: return@withContext null

        val dtsText = sidecars.dtsFile?.let { vf ->
            runCatching { VfsUtil.loadText(vf) }.getOrNull()
        }
        val configText = sidecars.configFile?.let { vf ->
            runCatching { VfsUtil.loadText(vf) }.getOrNull()
        }

        val profile = detectTargetProfile(headerFacts, dtsText, configText)
        val selection = selectBackend(profile)

        val result: FirmwareTarget? = when (selection) {
            is BackendSelection.Ok -> FirmwareTarget(profile, selection, sidecars)
            is BackendSelection.Fail -> null
        }

        // Update cache and watched-url registry.
        val newCache = cache.toMutableMap()
        newCache[key] = result
        cache = newCache

        val sidecarUrls = setOfNotNull(
            sidecars.mapFile?.url,
            sidecars.dtsFile?.url,
            sidecars.configFile?.url,
        )
        val newWatched = watchedUrls.toMutableMap()
        newWatched[elf.url] = sidecarUrls
        watchedUrls = newWatched

        result
    }

    public companion object {
        public fun getInstance(project: Project): FirmwareTargetService = project.service()
    }
}

/**
 * Reads the minimal ELF header fields needed for target detection without triggering a
 * full [ElfBinaryProvider] parse. Uses the ELF magic bytes and the first 64 bytes.
 *
 * Returns null for in-memory files or files whose first bytes do not look like ELF.
 */
private fun readElfHeaderFacts(file: VirtualFile): ElfHeaderFacts? {
    return try {
        val bytes = file.contentsToByteArray(false)
        if (bytes.size < 20) return null
        // ELF magic: 0x7f 'E' 'L' 'F'
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 0x45.toByte() ||
            bytes[2] != 0x4c.toByte() || bytes[3] != 0x46.toByte()
        ) return null

        val is64Bit = bytes[4].toInt() == 2 // EI_CLASS: 1=32, 2=64
        val littleEndian = bytes[5].toInt() == 1 // EI_DATA: 1=LE, 2=BE

        // e_machine is at offset 18 (2 bytes).
        val machine = if (littleEndian) {
            (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        } else {
            ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
        }

        // e_flags at offset 36 (4 bytes) for ELF32, offset 48 for ELF64.
        val flagsOffset = if (is64Bit) 48 else 36
        val flags = if (bytes.size >= flagsOffset + 4) {
            if (littleEndian) {
                (bytes[flagsOffset].toInt() and 0xff) or
                    ((bytes[flagsOffset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[flagsOffset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[flagsOffset + 3].toInt() and 0xff) shl 24)
            } else {
                ((bytes[flagsOffset].toInt() and 0xff) shl 24) or
                    ((bytes[flagsOffset + 1].toInt() and 0xff) shl 16) or
                    ((bytes[flagsOffset + 2].toInt() and 0xff) shl 8) or
                    (bytes[flagsOffset + 3].toInt() and 0xff)
            }
        } else 0

        ElfHeaderFacts(machine = machine, is64Bit = is64Bit, flags = flags)
    } catch (_: Exception) {
        null
    }
}
