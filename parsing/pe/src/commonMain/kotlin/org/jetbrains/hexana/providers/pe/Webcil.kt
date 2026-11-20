package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity

/**
 * The fixed header of a Webcil payload: the container .NET's `browser-wasm` (Blazor WebAssembly)
 * ships assemblies in. It is a PE image with the DOS, COFF and optional headers replaced by this
 * header and a bare section table (dotnet/runtime `docs/design/mono/webcil.md`):
 *
 * ```
 * u8[4] "WbIL"; u16 versionMajor; u16 versionMinor; u16 sectionCount; u16 reserved;
 * u32 cliHeaderRva; u32 cliHeaderSize; u32 debugDirectoryRva; u32 debugDirectorySize   // 28 bytes
 * sectionCount × { u32 virtualSize; u32 virtualAddress; u32 rawDataSize; u32 rawDataPointer }
 * ```
 *
 * Raw-data pointers, including those of debug directory entries, are relative to the payload start.
 */
data class WebcilHeader(
    val versionMajor: Int,
    val versionMinor: Int,
    val sectionCount: Int,
    val cliHeaderRva: UInt,
    val cliHeaderSize: UInt,
    val debugDirectoryRva: UInt,
    val debugDirectorySize: UInt,
    val range: NativeByteRange,
)

/**
 * A decoded Webcil payload located at [payloadRange] of a larger file (the data segment of its Wasm
 * wrapper, or the whole file for a bare `.webcil`).
 *
 * [sections] and [optionalHeader] are PE structures synthesized from the Webcil header, with every
 * raw-data pointer already rebased to a file offset, so `ClrMetadataParser`, `PeParser.parseDebugEntries`
 * (with [payloadRange]'s offset as `rawDataBase`) and the PE size analysis read it like any PE image.
 * [optionalHeader] carries only the debug and CLR runtime header directories.
 */
data class WebcilImage(
    val payloadRange: NativeByteRange,
    val header: WebcilHeader,
    val sections: List<PeSection>,
    val optionalHeader: PeOptionalHeader,
    val diagnostics: List<NativeParserDiagnostic>,
)

object WebcilParser {
    /** `WbIL`, read little-endian. */
    const val MAGIC: UInt = 0x4C496257u

    const val HEADER_SIZE: Int = 28
    const val SECTION_HEADER_SIZE: Int = 16

    /** The only layout this parser knows; a later major version may move fields. */
    const val SUPPORTED_MAJOR_VERSION: Int = 0

    /** The import module every Webcil Wasm wrapper declares (`(import "webcil" "memory" …)`). */
    const val WASM_WRAPPER_IMPORT_MODULE: String = "webcil"

    /** Loose cap: assemblies have 2–4 sections; the PE format itself stops at 96. */
    private const val MAX_SECTIONS = 96

    fun hasMagic(bytes: CommonByteBuffer, offset: Int): Boolean = bytes.u32LeOrNull(offset) == MAGIC

    /**
     * Decodes the Webcil payload of [size] bytes at [offset] of [bytes]. Returns null when there is no
     * `WbIL` magic there, the header or section table does not fit, or the major version is unknown.
     * Sections whose raw data leaves the payload are kept but reported, and do not map file offsets.
     */
    fun parse(bytes: CommonByteBuffer, offset: Int, size: Int): WebcilImage? {
        if (offset < 0 || size < HEADER_SIZE || !bytes.containsRange(offset, size)) return null
        if (!hasMagic(bytes, offset)) return null
        val versionMajor = bytes.u16LeOrNull(offset + 4) ?: return null
        if (versionMajor != SUPPORTED_MAJOR_VERSION) return null
        val sectionCount = bytes.u16LeOrNull(offset + 8) ?: return null
        if (sectionCount > MAX_SECTIONS) return null
        val tableSize = sectionCount * SECTION_HEADER_SIZE
        if (HEADER_SIZE + tableSize > size) return null
        val header = WebcilHeader(
            versionMajor = versionMajor,
            versionMinor = bytes.u16LeOrNull(offset + 6) ?: return null,
            sectionCount = sectionCount,
            cliHeaderRva = bytes.u32LeOrNull(offset + 12) ?: return null,
            cliHeaderSize = bytes.u32LeOrNull(offset + 16) ?: return null,
            debugDirectoryRva = bytes.u32LeOrNull(offset + 20) ?: return null,
            debugDirectorySize = bytes.u32LeOrNull(offset + 24) ?: return null,
            range = NativeByteRange(offset, HEADER_SIZE),
        )

        val diagnostics = mutableListOf<NativeParserDiagnostic>()
        val sections = (0 until sectionCount).map { index ->
            val headerOffset = offset + HEADER_SIZE + index * SECTION_HEADER_SIZE
            val virtualSize = bytes.u32LeOrNull(headerOffset) ?: return null
            val virtualAddress = bytes.u32LeOrNull(headerOffset + 4) ?: return null
            val rawDataSize = bytes.u32LeOrNull(headerOffset + 8) ?: return null
            val relativePointer = bytes.u32LeOrNull(headerOffset + 12) ?: return null
            val fits = relativePointer.toULong() + rawDataSize.toULong() <= size.toULong()
            if (!fits) {
                diagnostics += NativeParserDiagnostic(
                    code = "webcil.section_out_of_bounds",
                    message = "Webcil section $index raw data (0x${relativePointer.toString(16)}, ${rawDataSize} bytes) " +
                        "ends past the $size-byte payload",
                    range = NativeByteRange(headerOffset, SECTION_HEADER_SIZE),
                    severity = NativeParserSeverity.Warning,
                )
            }
            val filePointer = offset.toUInt() + relativePointer
            PeSection(
                index = index,
                name = "",
                virtualSize = virtualSize,
                virtualAddress = virtualAddress,
                // A section that does not fit maps nothing, so no RVA resolves outside the payload.
                rawDataSize = if (fits) rawDataSize else 0u,
                rawDataPointer = if (fits) filePointer else 0u,
                relocationPointer = 0u,
                lineNumberPointer = 0u,
                relocationCount = 0,
                lineNumberCount = 0,
                characteristics = 0u,
                headerRange = NativeByteRange(headerOffset, SECTION_HEADER_SIZE),
                contentRange = if (fits && rawDataSize > 0u) NativeByteRange(filePointer.toInt(), rawDataSize.toInt()) else null,
            )
        }.map { section -> section.copy(name = sectionName(section, header)) }

        val directories = (0..PeDataDirectoryIndex.ClrRuntimeHeader).map { index ->
            val (rva, directorySize, fieldOffset) = when (index) {
                PeDataDirectoryIndex.Debug -> Triple(header.debugDirectoryRva, header.debugDirectorySize, 20)
                PeDataDirectoryIndex.ClrRuntimeHeader -> Triple(header.cliHeaderRva, header.cliHeaderSize, 12)
                else -> Triple(0u, 0u, -1)
            }
            PeDataDirectory(
                index = index,
                name = if (index == PeDataDirectoryIndex.Debug) "Debug" else if (index == PeDataDirectoryIndex.ClrRuntimeHeader) "CLR Runtime Header" else "",
                rva = rva,
                size = directorySize,
                recordRange = if (fieldOffset >= 0) NativeByteRange(offset + fieldOffset, 8) else NativeByteRange(offset, 0),
            )
        }
        val optionalHeader = PeOptionalHeader(
            wordSize = NativeBinaryWordSize.Unknown,
            magic = 0,
            addressOfEntryPoint = 0u,
            imageBase = 0u,
            sectionAlignment = 0u,
            fileAlignment = 0u,
            sizeOfImage = sections.maxOfOrNull { it.virtualAddress + maxOf(it.virtualSize, it.rawDataSize) } ?: 0u,
            // Zero: unlike a PE file, no RVA maps into the headers, which are not part of the image.
            sizeOfHeaders = 0u,
            subsystem = 0,
            dllCharacteristics = 0,
            dataDirectoryCount = directories.size.toUInt(),
            dataDirectories = directories,
            headerRange = header.range,
        )
        return WebcilImage(NativeByteRange(offset, size), header, sections, optionalHeader, diagnostics)
    }

    /**
     * Webcil drops section names. The one holding the CLI header is the IL image's `.text`; the others
     * are named by position, since `.rsrc` / `.reloc` are only conventions.
     */
    private fun sectionName(section: PeSection, header: WebcilHeader): String {
        val start = section.virtualAddress.toULong()
        val end = start + maxOf(section.virtualSize, section.rawDataSize).toULong()
        return if (header.cliHeaderRva.toULong() in start until end) ".text" else "section ${section.index}"
    }
}
