package org.jetbrains.hexana.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.jetbrains.hexana.DwarfIndexId
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wat.WatFileType

/**
 * Line breakpoint type for WebAssembly debugging.
 *
 * Breakpoints can be placed in WAT files and in any text-based source
 * file (C, Rust, etc.) that is referenced by DWARF debug information
 * embedded in a Wasm binary present in the project.  The native debugger
 * (LLDB/GDB) attached to the wasmtime process uses these source
 * locations when DWARF metadata is present.
 */
class WasmLineBreakpointType :
    XLineBreakpointType<WasmBreakpointProperties>(ID, HexanaBundle.message("wasm.breakpoint.type.title")) {

    override fun createBreakpointProperties(file: VirtualFile, line: Int): WasmBreakpointProperties {
        return WasmBreakpointProperties()
    }

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType is WatFileType) return true

        val extension = file.extension?.lowercase() ?: return false
        if (extension !in SOURCE_EXTENSIONS) return false

        // canPutAt is called on the EDT, where index access is a
        // prohibited slow operation — consult the async cache instead.
        val names = project.service<WasmDwarfSourceNamesCache>().sourceFileNames() ?: return false
        return file.name in names
    }

    companion object {
        const val ID = "WasmLineBreakpoint"

        /**
         * Source file extensions commonly associated with compiled-to-Wasm
         * languages whose DWARF metadata a native debugger can consume.
         */
        private val SOURCE_EXTENSIONS = setOf(
            "c", "h",
            "cpp", "cxx", "cc", "hpp", "hxx",
            "rs",
            "zig",
            "wat",
            "go"
        )
    }
}

/**
 * Project-level cache of source file names referenced by DWARF debug info
 * in the project's wasm binaries (basenames of [DwarfIndexId] keys).
 *
 * [WasmLineBreakpointType.canPutAt] runs on the EDT where querying the
 * file-based index directly is a prohibited slow operation, so the index
 * is read on a pooled thread and the result cached. Reads return the last
 * loaded snapshot (or `null` before the first load completes — breakpoint
 * placement is then temporarily unavailable and self-heals on the next
 * gutter interaction) and schedule a background refresh at most once per
 * [REFRESH_INTERVAL_MS].
 */
@Service(Service.Level.PROJECT)
class WasmDwarfSourceNamesCache(private val project: Project) {

    @Volatile
    private var names: Set<String>? = null

    private var lastLoadStarted = 0L

    /** Returns the cached DWARF source basenames, scheduling a refresh when stale. */
    fun sourceFileNames(): Set<String>? {
        scheduleRefreshIfStale()
        return names
    }

    private fun scheduleRefreshIfStale() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastLoadStarted < REFRESH_INTERVAL_MS) return
            lastLoadStarted = now
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val keys = DumbService.getInstance(project).runReadActionInSmartMode<Collection<String>> {
                    FileBasedIndex.getInstance().getAllKeys(DwarfIndexId, project)
                }
                names = keys.mapTo(mutableSetOf()) { it.substringAfterLast('/') }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                Logger.getInstance(WasmDwarfSourceNamesCache::class.java)
                    .debug("Failed to load DWARF source names", e)
            }
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5_000L
    }
}
