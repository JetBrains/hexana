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
