package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector

/**
 * A parser a user can pick for a file by hand, overriding what
 * [resolveBinaryProviderKind] would choose.
 *
 * This is the vocabulary of `.hexana/file_associations.json`: [id] is what the file
 * stores, so it is part of a format other people commit and must not be renamed. The
 * ids reuse the `binary.format` names scripts already see (`binaryFormatId` in
 * `plugins-shared`) wherever one exists, so there is one spelling per format.
 *
 * Entries are coarser than providers where the distinction is the bytes' business
 * rather than the user's: [MACH_O] covers thin and universal binaries, [ARROW] covers
 * IPC files and streams, [PCAP] covers both capture formats. The parser works out the
 * variant itself.
 */
enum class BinaryParser(val id: String, val displayName: String) {
    /** No parsing at all: the hex view and nothing else. */
    GENERIC("generic", "Generic binary"),
    WASM("wasm", "Core Wasm"),
    WASM_COMPONENT("wasm-component", "Wasm Component"),
    ELF("elf", "ELF"),
    MACH_O("macho", "Mach-O"),
    PE("pe", "PE"),
    COFF("coff", "COFF object"),
    AR_ARCHIVE("archive", "Unix ar archive"),
    ZIP("zip", "ZIP / JAR"),
    TAR("tar", "Tar archive"),
    JAVA_CLASS("class", "Java class file"),
    DEX("dex", "Android DEX"),
    ARSC("arsc", "Android resource table"),
    DOTNET_RESOURCES("dotnet-resources", ".NET resources"),
    GGUF("gguf", "GGUF model"),
    SAFETENSORS("safetensors", "Safetensors model"),
    PDB("pdb", "Windows PDB"),
    PORTABLE_PDB("portable-pdb", "Portable PDB"),
    PCAP("pcap", "PCAP capture"),
    PROTOBUF_DESCRIPTOR_SET("protobuf", "Protobuf descriptor set"),
    PROTOBUF_MESSAGE("protobuf-message", "Protobuf message"),
    PARQUET("parquet", "Apache Parquet"),
    ARROW("arrow", "Apache Arrow IPC"),
    ;

    companion object {
        /** The parser stored under [id], or `null` for an id this Hexana does not know. */
        fun fromId(id: String): BinaryParser? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The [BinaryParser] that built [provider], which is what a parser picker marks as
 * current.
 *
 * Subclasses of [GenericBinaryProvider] are matched before it, since several formats
 * ([ClassFileBinaryProvider], the two Protobuf providers) extend it.
 */
fun binaryParserOf(provider: BinaryProvider): BinaryParser = when (provider) {
    is WasmBinaryProvider -> BinaryParser.WASM
    is WasmComponentBinaryProvider -> BinaryParser.WASM_COMPONENT
    is ElfBinaryProvider -> BinaryParser.ELF
    is MachOBinaryProvider, is FatMachOBinaryProvider -> BinaryParser.MACH_O
    is PeBinaryProvider -> BinaryParser.PE
    is CoffBinaryProvider -> BinaryParser.COFF
    is ArchiveBinaryProvider -> BinaryParser.AR_ARCHIVE
    is ZipBinaryProvider -> BinaryParser.ZIP
    is TarBinaryProvider -> BinaryParser.TAR
    is ClassFileBinaryProvider -> BinaryParser.JAVA_CLASS
    is DexBinaryProvider -> BinaryParser.DEX
    is ArscBinaryProvider -> BinaryParser.ARSC
    is ClrResourcesBinaryProvider -> BinaryParser.DOTNET_RESOURCES
    is GgufBinaryProvider -> BinaryParser.GGUF
    is SafetensorsBinaryProvider -> BinaryParser.SAFETENSORS
    is PdbBinaryProvider -> BinaryParser.PDB
    is PortablePdbBinaryProvider -> BinaryParser.PORTABLE_PDB
    is PcapBinaryProvider -> BinaryParser.PCAP
    is ProtobufBinaryProvider -> BinaryParser.PROTOBUF_DESCRIPTOR_SET
    is ProtobufMessageBinaryProvider -> BinaryParser.PROTOBUF_MESSAGE
    is ParquetBinaryProvider -> BinaryParser.PARQUET
    is ArrowBinaryProvider -> BinaryParser.ARROW
    is GenericBinaryProvider -> BinaryParser.GENERIC
}

/**
 * Builds the provider for a parser the user chose, ignoring what the bytes and the name
 * would otherwise resolve to.
 *
 * Returns `null` when [parser] cannot read [bytes] at all, so the host can fall back to
 * the automatic choice and say why, rather than show an empty editor. "At all" is
 * deliberately narrow: the native parsers need the header to identify as their own
 * format (an ELF parser has nothing to go on without an ELF header), and any parser
 * whose constructor throws is refused. Everything else is the user's call — overriding
 * the detection is the point, and a parser that copes badly with the bytes still shows
 * its tabs, and the hex view, as it would for a corrupt file.
 *
 * [sourcePath] is what the providers that record one surface as `binary.path`; see
 * [createBinaryProvider].
 */
fun createBinaryProvider(
    parser: BinaryParser,
    bytes: CommonByteBuffer,
    sourcePath: String? = null,
): BinaryProvider? {
    // Rewound once for every provider, for the reason [createBinaryProvider] gives.
    val shared = bytes.duplicate().position(0)
    return try {
        when (parser) {
            BinaryParser.GENERIC -> GenericBinaryProvider(shared)
            BinaryParser.WASM -> WasmBinaryProvider(shared)
            BinaryParser.WASM_COMPONENT -> WasmComponentBinaryProvider(shared)
            BinaryParser.ELF -> (detectNative(shared) as? NativeBinaryFormat.Elf)
                ?.let { ElfBinaryProvider(shared, it, sourcePath) }
            BinaryParser.MACH_O -> when (val format = detectNative(shared)) {
                is NativeBinaryFormat.MachO -> MachOBinaryProvider(shared, format, sourcePath)
                is NativeBinaryFormat.FatMachO -> FatMachOBinaryProvider(shared, format, sourcePath)
                else -> null
            }
            BinaryParser.PE -> (detectNative(shared) as? NativeBinaryFormat.Pe)
                ?.let { PeBinaryProvider(shared, it, sourcePath) }
            BinaryParser.COFF -> (detectNative(shared) as? NativeBinaryFormat.Coff)
                ?.let { CoffBinaryProvider(shared, it, sourcePath) }
            BinaryParser.AR_ARCHIVE -> if (detectNative(shared) == NativeBinaryFormat.Archive) {
                ArchiveBinaryProvider(shared, sourcePath)
            } else {
                null
            }
            BinaryParser.ZIP -> ZipBinaryProvider(shared)
            BinaryParser.TAR -> TarBinaryProvider(shared)
            BinaryParser.JAVA_CLASS -> ClassFileBinaryProvider(shared)
            BinaryParser.DEX -> DexBinaryProvider(shared)
            BinaryParser.ARSC -> ArscBinaryProvider(shared)
            BinaryParser.DOTNET_RESOURCES -> ClrResourcesBinaryProvider(shared)
            BinaryParser.GGUF -> GgufBinaryProvider(shared)
            BinaryParser.SAFETENSORS -> SafetensorsBinaryProvider(shared)
            BinaryParser.PDB -> PdbBinaryProvider(shared)
            BinaryParser.PORTABLE_PDB -> PortablePdbBinaryProvider(shared)
            BinaryParser.PCAP -> PcapBinaryProvider(shared, sourcePath)
            BinaryParser.PROTOBUF_DESCRIPTOR_SET -> ProtobufBinaryProvider(shared)
            BinaryParser.PROTOBUF_MESSAGE -> ProtobufMessageBinaryProvider(shared)
            BinaryParser.PARQUET -> ParquetBinaryProvider(shared, sourcePath)
            // Same rule the automatic path applies once a name has said Arrow: the
            // stream marker is only believed when something else already narrowed the
            // format down, and here the user has.
            BinaryParser.ARROW -> ArrowBinaryProvider(
                shared,
                if (isArrowStreamMagic(shared.duplicate().position(0))) ArrowFormat.STREAM else ArrowFormat.FILE,
                sourcePath,
            )
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * The [BinaryProviderKind] a chosen [parser] corresponds to, for hosts that build the
 * Wasm providers themselves (the IntelliJ editor needs `VirtualFile`-backed ones for
 * the WAT view). `null` for every parser that has no kind of its own.
 */
fun BinaryParser.providerKindOrNull(): BinaryProviderKind? = when (this) {
    BinaryParser.WASM -> BinaryProviderKind.Wasm
    BinaryParser.WASM_COMPONENT -> BinaryProviderKind.WasmComponent
    else -> null
}

private fun detectNative(bytes: CommonByteBuffer): NativeBinaryFormat? =
    NativeBinaryFormatDetector.detect(bytes.duplicate().position(0))
