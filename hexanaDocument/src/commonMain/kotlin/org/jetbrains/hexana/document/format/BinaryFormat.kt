package org.jetbrains.hexana.document.format

import org.jetbrains.hexana.document.BinaryDocumentSnapshot

/**
 * Format SPI for the Hexana binary document framework.
 *
 * Each supported file format (WASM, ELF, PE/COFF, JVM classfile, LLVM bitcode)
 * implements this interface. The viewer panel is parameterized by a
 * [BinaryFormat] and stays format-agnostic. The IDE-side
 * `StructuralEditor` (Phase 2+) wraps a separate adapter; this SPI deliberately
 * has no IDE dependency so it lives alongside `wasmParser` in pure-Kotlin
 * common code.
 *
 * The type parameter [P] is the format-specific parsed-model type. Adapters
 * extend [ParsedBinary] with whatever the parser produces and use that
 * concrete type internally; the framework only needs the marker.
 */
public interface BinaryFormat<P : ParsedBinary> {
    /** Human-readable name shown in UI ("WebAssembly", "ELF", …). */
    public val displayName: String

    /** Cheap content sniff: is this snapshot plausibly this format? Reads only the header. */
    public fun probe(snapshot: BinaryDocumentSnapshot): Boolean

    /**
     * Parses [snapshot] into a format-specific [ParsedBinary]. Throws on
     * malformed input — caller decides whether to surface an error chunk or
     * refuse to render the tab.
     */
    public fun parse(snapshot: BinaryDocumentSnapshot): P

    /** Builds the chunk tree (lazy where possible) for the parsed binary. */
    public fun structure(parsed: P): ChunkTree

    /** Returns the renderer that converts chunks to token streams. */
    public fun renderer(parsed: P): ChunkRenderer

    /** Returns the symbol resolver for human-readable names. */
    public fun symbols(parsed: P): SymbolResolver
}

/**
 * Marker for the format-specific parsed-model produced by [BinaryFormat.parse].
 * Adapters subclass with whatever they need (`WasmBinaryProvider`, ELF DIEs,
 * JVM classfile records, …).
 */
public interface ParsedBinary {
    /** The snapshot this parse came from; lets callers re-read the same bytes. */
    public val snapshot: BinaryDocumentSnapshot

    /** Matches [BinaryDocumentSnapshot.modificationStamp] at parse time; used as a cache key. */
    public val modificationStamp: Long
}
