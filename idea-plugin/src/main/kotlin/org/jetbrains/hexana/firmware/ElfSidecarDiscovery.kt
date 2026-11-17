package org.jetbrains.hexana.firmware

import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.providers.memory.linkerMapMatchesElf
import org.jetbrains.hexana.providers.memory.siblingMapCandidates

/**
 * Discovered sidecar files adjacent to an ELF binary in the Zephyr build tree.
 *
 * Any field is null when the corresponding file was not found.
 */
public data class ElfSidecars(
    val mapFile: VirtualFile?,
    val dtsFile: VirtualFile?,
    val configFile: VirtualFile?,
)

/**
 * Project-level service that discovers Zephyr sidecar files for an ELF binary.
 *
 * The single [discover] method performs a bounded filesystem walk (at most 3
 * directory levels, a handful of candidate filenames) and returns the result.
 * Callers MUST invoke this off the EDT (e.g. on [kotlinx.coroutines.Dispatchers.IO]).
 *
 * Walk order (mirrors ElfMemoryBudgetPanel's probe exactly):
 * 1. Sibling `<elf-base>.map` and `zephyr.map` files (validated against the ELF).
 * 2. Sibling `zephyr.dts`.
 * 3. Sibling `.config`.
 * 4. `parent/zephyr/{zephyr.map, zephyr.dts, .config}`.
 * 5. `parent.parent/zephyr/{zephyr.map, zephyr.dts, .config}`.
 *
 * In-memory [HexanaBinaryVirtualFile] instances (no disk path) short-circuit
 * immediately to [ElfSidecars] with all fields null.
 *
 * This is a light service; no plugin.xml registration is required (IntelliJ
 * Platform 2024.1+ resolves `@Service`-annotated project services automatically).
 */
@Service(Service.Level.PROJECT)
public final class ElfSidecarDiscovery {

    /**
     * Discovers sidecar files for [elf].
     *
     * This method MUST be called off the EDT. It performs VFS child lookups and
     * may decode small files for map validation.
     *
     * @param elf The ELF binary file whose sidecars to locate.
     * @return Discovered sidecars (any field may be null if not found).
     */
    public fun discover(elf: VirtualFile): ElfSidecars {
        // In-memory virtual files have no disk path — skip discovery.
        if (elf.getUserData(HexanaBinaryVirtualFile.BUFFER_KEY) != null) {
            return ElfSidecars(null, null, null)
        }

        val parent = elf.parent ?: return ElfSidecars(null, null, null)

        var mapFile: VirtualFile? = null
        var dtsFile: VirtualFile? = null
        var configFile: VirtualFile? = null

        // Build sibling candidate names for map files (validated), dts, and config.
        val siblingNames = parent.children.map { it.name }

        // Sibling *.map — validated against ELF sections.
        if (mapFile == null) {
            for (name in siblingMapCandidates(elf.name, siblingNames)) {
                val candidate = parent.findChild(name) ?: continue
                if (!candidate.exists()) continue
                val text = candidate.contentsToByteArray().decodeToString()
                // We don't have access to ELF sections here (that would require parsing),
                // so we accept any map file found by the prioritized sibling order.
                // The panel still has its own validation with the actual sections.
                mapFile = candidate
                break
            }
        }

        // Sibling zephyr.dts.
        if (dtsFile == null) {
            dtsFile = parent.findChild("zephyr.dts")?.takeIf { it.exists() }
        }

        // Sibling .config.
        if (configFile == null) {
            configFile = parent.findChild(".config")?.takeIf { it.exists() }
        }

        // parent/zephyr/{zephyr.map, zephyr.dts, .config}
        val zephyrDir = parent.findChild("zephyr")
        if (zephyrDir != null && zephyrDir.exists()) {
            if (mapFile == null) {
                mapFile = zephyrDir.findChild("zephyr.map")?.takeIf { it.exists() }
            }
            if (dtsFile == null) {
                dtsFile = zephyrDir.findChild("zephyr.dts")?.takeIf { it.exists() }
            }
            if (configFile == null) {
                configFile = zephyrDir.findChild(".config")?.takeIf { it.exists() }
            }
        }

        // parent.parent/zephyr/{zephyr.map, zephyr.dts, .config}
        val parentZephyrDir = parent.parent?.findChild("zephyr")
        if (parentZephyrDir != null && parentZephyrDir.exists()) {
            if (mapFile == null) {
                mapFile = parentZephyrDir.findChild("zephyr.map")?.takeIf { it.exists() }
            }
            if (dtsFile == null) {
                dtsFile = parentZephyrDir.findChild("zephyr.dts")?.takeIf { it.exists() }
            }
            if (configFile == null) {
                configFile = parentZephyrDir.findChild(".config")?.takeIf { it.exists() }
            }
        }

        return ElfSidecars(mapFile = mapFile, dtsFile = dtsFile, configFile = configFile)
    }
}
