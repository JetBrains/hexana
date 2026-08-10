@file:OptIn(ExperimentalJsExport::class, ExperimentalJsStatic::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.dwarf.DwarfTypeInfo
import org.jetbrains.hexana.dwarf.Tag
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

    /**
     * Returns DWARF-described variables for the subprogram covering [offset] whose values
     * live in Wasm linear memory (frame-relative `DW_OP_fbreg` or static `DW_OP_addr`).
     *
     * Variables already visible as V8 `wasmvalue` scope entries (wasm locals/globals) are
     * omitted; only memory-backed variables are returned here.
     *
     * @param offset the CDP columnNumber for the wasm:// frame (module-absolute byte offset)
     */
    fun memoryVariablesAt(offset: Int): Array<MemoryVariable> {
        return delegate.memoryVariablesAt(offset.toULong()).map { v ->
            val isFbreg = v.fbregOffset != null
            val isGlobal = isFbreg && (v.frameBaseLocalIndex ?: 0) < 0
            MemoryVariable(
                name = v.name,
                typeSummary = typeSummary(v.type),
                fbregOffset = v.fbregOffset?.toInt() ?: 0,
                frameBaseLocalIndex = v.frameBaseLocalIndex?.toInt() ?: 0,
                absoluteAddress = v.absoluteAddress?.toInt() ?: 0,
                byteSize = v.byteSize,
                isFbreg = isFbreg,
                frameBaseIsGlobal = isGlobal,
            )
        }.toTypedArray()
    }

    private fun typeSummary(type: DwarfTypeInfo): String = when (type) {
        is DwarfTypeInfo.Base -> type.name
        is DwarfTypeInfo.Pointer -> "${typeSummary(type.pointee ?: DwarfTypeInfo.Unknown(Tag.DW_TAG_unspecified_type))}*"
        is DwarfTypeInfo.Struct -> type.name ?: "struct"
        is DwarfTypeInfo.Array -> "[${type.count ?: "?"}]${typeSummary(type.elementType ?: DwarfTypeInfo.Unknown(Tag.DW_TAG_unspecified_type))}"
        is DwarfTypeInfo.Enumeration -> type.name ?: "enum"
        is DwarfTypeInfo.Unknown -> "?"
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

/**
 * A DWARF-described variable whose value lives in Wasm linear memory.
 *
 * CDP callers should:
 * 1. If [absoluteAddress] is non-null: read [byteSize] bytes from `memories[0]` at [absoluteAddress].
 * 2. Otherwise: read the value of wasm local at index [frameBaseLocalIndex] from the frame's scope
 *    (as a number), add [fbregOffset], and read [byteSize] bytes from `memories[0]` at that address.
 *    A negative [frameBaseLocalIndex] encodes `-(globalIndex+1)` meaning "use wasm global, not local".
 *
 * @property name DWARF source variable name
 * @property typeSummary one-line type description for display
 * @property fbregOffset signed byte offset from the frame-base pointer; null for absolute-address vars
 * @property frameBaseLocalIndex the wasm local index of the shadow stack pointer; null for absolute-address vars
 * @property absoluteAddress fixed linear-memory address for `DW_OP_addr` variables; null for frame-relative
 * @property byteSize number of bytes to read from linear memory (capped at 4096)
 */
@JsExport
data class MemoryVariable(
    val name: String,
    val typeSummary: String,
    val fbregOffset: Int,
    val frameBaseLocalIndex: Int,
    val absoluteAddress: Int,
    val byteSize: Int,
    /** True when this is a frame-relative variable (use frameBaseLocalIndex + fbregOffset). */
    val isFbreg: Boolean,
    /** True when frameBaseLocalIndex is actually a wasm global index (negated). */
    val frameBaseIsGlobal: Boolean,
)
