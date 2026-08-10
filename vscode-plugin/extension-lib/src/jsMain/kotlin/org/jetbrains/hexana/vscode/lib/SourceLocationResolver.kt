@file:OptIn(ExperimentalJsExport::class, ExperimentalJsStatic::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.CdpSourceLocationResolver

/**
 * JS-exported wrapper around [CdpSourceLocationResolver] for use
 * by the VS Code CDP debug adapter.
 *
 * Exposes DWARF-based source ↔ Wasm byte offset resolution so that
 * the TypeScript debug adapter can set breakpoints at correct offsets
 * and map paused wasm frames back to source locations.
 */
@JsExport
class SourceLocationResolver private constructor(
    private val delegate: CdpSourceLocationResolver,
) {
    /**
     * Returns all Wasm module-absolute byte offsets that map to the
     * given source file and line number.
     *
     * @param fileName source file name (full path or just the file name)
     * @param line 0-based line number (as DAP sends it)
     * @return array of offsets (may be empty)
     */
    fun sourceToAllWasmOffsets(fileName: String, line: Int): IntArray {
        return delegate.sourceToAllWasmOffsets(fileName, line)
            .map { it.toInt() }
            .toIntArray()
    }

    /**
     * Resolves a Wasm module-absolute byte offset back to a source
     * location (file + line).
     *
     * @param offset the byte offset (CDP columnNumber for wasm scripts)
     * @return a [ResolvedSourceLocation], or `null` if no mapping exists
     */
    fun wasmOffsetToSource(offset: Int): ResolvedSourceLocation? {
        val loc = delegate.wasmOffsetToSource(offset.toULong()) ?: return null
        return ResolvedSourceLocation(loc.file, loc.line)
    }

    /**
     * Returns all known source file names (normalized to just the
     * file-name component) that have DWARF line mappings.
     */
    fun knownSourceFiles(): Array<String> {
        return delegate.knownSourceFiles().toTypedArray()
    }

    /**
     * Returns whether the given CDP function name has a matching DWARF
     * subprogram entry.
     */
    fun hasDwarfSubprogram(functionName: String?): Boolean {
        return delegate.hasDwarfSubprogram(functionName)
    }

    /**
     * Returns Wasm local-index to DWARF source-name mappings for the
     * given CDP function name.
     */
    fun wasmLocalNames(functionName: String?): Array<WasmLocalName> {
        return delegate.wasmLocalNames(functionName)
            .map { WasmLocalName(it.key.toInt(), it.value) }
            .toTypedArray()
    }

    /**
     * Returns the inlined call chain covering [offset] (module-absolute
     * byte offset, as reported by CDP `columnNumber` for wasm:// scripts),
     * innermost first. Empty when no inlined subroutines cover the offset
     * or when DWARF info is absent.
     *
     * @param offset the CDP columnNumber for the wasm:// frame
     */
    fun inlinedFramesAt(offset: Int): Array<InlinedFrame> {
        return delegate.inlinedFramesAt(offset.toULong())
            .map { f -> InlinedFrame(f.name, f.callFile, f.callLine, f.callColumn, f.depth) }
            .toTypedArray()
    }

    companion object {
        /**
         * Creates a resolver from raw Wasm binary bytes.
         *
         * @return a [SourceLocationResolver], or `null` if the binary
         *         has no DWARF line info or parsing fails
         */
        @JsStatic
        fun create(wasmBytes: ByteArray): SourceLocationResolver? {
            val delegate = CdpSourceLocationResolver.create(wasmBytes) ?: return null
            return SourceLocationResolver(delegate)
        }
    }
}

/**
 * A resolved source location returned by [SourceLocationResolver.wasmOffsetToSource].
 */
@JsExport
data class ResolvedSourceLocation(
    val file: String,
    /** 1-based line number from DWARF. */
    val line: Int,
)

/**
 * A Wasm local-index to source-name mapping for CDP variable rendering.
 */
@JsExport
data class WasmLocalName(
    val index: Int,
    val name: String,
)

/**
 * One frame in an inlined call chain, as returned by
 * [SourceLocationResolver.inlinedFramesAt].
 *
 * @property name the resolved function name, or `null` if unavailable
 * @property callFile the source file at the call site, or `null`
 * @property callLine 1-based source line at the call site, or `null`
 * @property callColumn source column at the call site, or `null`
 * @property depth nesting depth (1 = directly inlined, 2 = inlined inside inlined, ...)
 */
@JsExport
data class InlinedFrame(
    val name: String?,
    val callFile: String?,
    val callLine: Int?,
    val callColumn: Int?,
    val depth: Int,
)
