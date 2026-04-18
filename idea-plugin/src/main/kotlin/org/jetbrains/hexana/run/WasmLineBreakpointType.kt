package org.jetbrains.hexana.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wat.WatFileType

/**
 * Line breakpoint type for WebAssembly debugging.
 *
 * Breakpoints can be placed in WAT files and in any text-based source
 * file (C, Rust, etc.) that might be referenced by DWARF debug
 * information embedded in the Wasm binary.  The native debugger
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
        return extension in SOURCE_EXTENSIONS
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
