package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.format.BinaryFormat
import org.jetbrains.hexana.document.format.ChunkRenderer
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.ParsedBinary
import org.jetbrains.hexana.document.format.SymbolResolver
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * The first concrete [BinaryFormat] in Hexana. Wraps the existing
 * [WasmBinaryProvider] without any changes to `wasmParser`. Phase 1 ships
 * with a placeholder renderer; PR 2 swaps in the Hexana-flavored WAT printer.
 *
 * Probe: checks for the `\0asm` magic + version `0x00000001` (core module —
 * components have version `0x0000000d` and use a different format).
 */
public object WasmBinaryFormat : BinaryFormat<WasmParsedBinary> {

    override val displayName: String = "WebAssembly"

    override fun probe(snapshot: BinaryDocumentSnapshot): Boolean {
        if (snapshot.length < 8L) return false
        return snapshot.readByte(0L) == 0x00.toByte() &&
            snapshot.readByte(1L) == 0x61.toByte() && // 'a'
            snapshot.readByte(2L) == 0x73.toByte() && // 's'
            snapshot.readByte(3L) == 0x6D.toByte() && // 'm'
            snapshot.readByte(4L) == 0x01.toByte() // core module version 1
    }

    override fun parse(snapshot: BinaryDocumentSnapshot): WasmParsedBinary {
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        val dwarfIndex = WasmDwarfNameIndex(provider)
        val resolver = WasmSymbolResolver(provider, dwarfIndex)
        return WasmParsedBinary(
            snapshot = snapshot,
            modificationStamp = snapshot.modificationStamp,
            provider = provider,
            symbolResolver = resolver,
        )
    }

    override fun structure(parsed: WasmParsedBinary): ChunkTree = buildWasmChunkTree(parsed)

    override fun renderer(parsed: WasmParsedBinary): ChunkRenderer = WasmHexanaWatRenderer(parsed)

    override fun symbols(parsed: WasmParsedBinary): SymbolResolver = parsed.symbolResolver
}

/**
 * [ParsedBinary] for WASM. Carries the existing [WasmBinaryProvider] alongside
 * the symbol resolver so the chunk tree builder and renderer can access them
 * without recomputation.
 */
public class WasmParsedBinary internal constructor(
    override val snapshot: BinaryDocumentSnapshot,
    override val modificationStamp: Long,
    public val provider: WasmBinaryProvider,
    internal val symbolResolver: WasmSymbolResolver,
) : ParsedBinary {
    public companion object {
        /**
         * Phase 1 helper: wraps an existing [WasmBinaryProvider] (already
         * parsed by upstream code) plus a [snapshot] to produce a
         * [WasmParsedBinary] without re-parsing. Phase 2 callers will go
         * through [WasmBinaryFormat.parse] instead.
         */
        public fun fromProvider(
            provider: WasmBinaryProvider,
            snapshot: BinaryDocumentSnapshot,
        ): WasmParsedBinary {
            val dwarfIndex = WasmDwarfNameIndex(provider)
            return WasmParsedBinary(
                snapshot = snapshot,
                modificationStamp = snapshot.modificationStamp,
                provider = provider,
                symbolResolver = WasmSymbolResolver(provider, dwarfIndex),
            )
        }
    }
}
