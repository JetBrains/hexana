package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.pe.PeDebugEntry
import org.jetbrains.hexana.providers.pe.PeParser
import org.jetbrains.hexana.providers.pe.WebcilImage
import org.jetbrains.hexana.providers.pe.WebcilParser
import org.jetbrains.hexana.providers.pe.clr.ClrEmbeddedPortablePdb
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMetadataParser
import org.jetbrains.hexana.providers.pe.clr.ClrPortablePdb
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import kotlin.concurrent.Volatile

/**
 * A .NET assembly, whatever container it came in: a managed PE file ([PeBinaryProvider]) or a Webcil
 * payload inside a Blazor WebAssembly `.wasm` ([WebcilAssembly]). The CLR tabs and the IL view read
 * only this, so they work for both.
 *
 * All byte ranges in [clrMetadata] are offsets into [bytes].
 */
interface ClrAssemblySource {
    /** The whole file; a fresh cursor on every access, like [BinaryProvider.bytes]. */
    val bytes: CommonByteBuffer

    /** Decoded metadata, or null when the CLI header cannot be read. */
    val clrMetadata: ClrMetadata?

    /** Sequence points of managed methods, when a Portable PDB is embedded or attached. */
    val portablePdb: ClrPortablePdb?

    /** The COFF machine, or null when the container records none (Webcil drops the COFF header). */
    val clrMachine: Int?
}

/**
 * A .NET assembly shipped as Webcil (.NET 8+ `browser-wasm`, Blazor WebAssembly). [image] maps the
 * payload's sections to file offsets in [bytes], so everything decoded here — metadata, IL bodies,
 * resources, debug entries — points into the `.wasm` file the user has open.
 */
class WebcilAssembly(private val sharedBytes: CommonByteBuffer, val image: WebcilImage) : ClrAssemblySource {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    override val clrMetadata: ClrMetadata? by lazy {
        ClrMetadataParser.parse(bytes, image.optionalHeader, image.sections)
    }

    override val clrMachine: Int? get() = null

    /** Debug directory entries, with their data ranges rebased from the payload to the file. */
    val debugEntries: List<PeDebugEntry> by lazy {
        PeParser.parseDebugEntries(bytes, image.optionalHeader, image.sections, image.payloadRange.start.toUInt()).orEmpty()
    }

    /** The Portable PDB the CodeView record names; `.pdb` files are published next to the `.wasm`. */
    val pdbReference: PePdbReference? by lazy { PePdbReference.of(debugEntries) }

    private val embeddedPortablePdb: ClrPortablePdb? by lazy {
        ClrEmbeddedPortablePdb.entry(debugEntries)?.let { ClrEmbeddedPortablePdb.decode(bytes, it)?.portablePdb }
    }

    @Volatile
    private var attachedPdb: ClrPortablePdb? = null

    /** Uses [symbols] for sequence points when it is the PDB [pdbReference] names. */
    fun attachDebugSymbols(symbols: PortablePdbBinaryProvider): PdbMatch {
        val match = pdbMatch(pdbReference, symbols)
        if (match == PdbMatch.Matches) attachedPdb = symbols.metadata.portablePdb
        return match
    }

    override val portablePdb: ClrPortablePdb? get() = attachedPdb ?: embeddedPortablePdb

    /** Managed size breakdown of the payload: per section, then methods, IL, metadata and resources. */
    val sizeAnalysis: BinarySizeNode? by lazy {
        val metadata = clrMetadata ?: return@lazy null
        clrSizeAnalysis(bytes, image.sections, image.optionalHeader, debugEntries, metadata, readyToRun = null)
    }

    companion object {
        /**
         * Finds the Webcil payload of a Wasm wrapper module: the module imports from `webcil` and one
         * of its data segments starts with `WbIL` (segment 1 in every SDK so far; segment 0
         * holds the payload size). Null for any other module.
         */
        fun locate(bytes: CommonByteBuffer, imports: List<WasmImportRecord>, data: () -> List<WasmDataRecord>): WebcilAssembly? {
            // Checked first so other modules never pay for scanning their (possibly thousands of) data segments.
            if (imports.none { it.prefix == WebcilParser.WASM_WRAPPER_IMPORT_MODULE }) return null
            for (record in data()) {
                val payloadSize = record.data.size
                if (payloadSize < WebcilParser.HEADER_SIZE) continue
                // The segment's bytes are the tail of its record: `flags, [memidx, expr,] len, bytes`.
                val payloadOffset = record.offset + record.recordLength - payloadSize
                if (!WebcilParser.hasMagic(bytes, payloadOffset)) continue
                val image = WebcilParser.parse(bytes, payloadOffset, payloadSize) ?: continue
                return WebcilAssembly(bytes, image)
            }
            return null
        }
    }
}
