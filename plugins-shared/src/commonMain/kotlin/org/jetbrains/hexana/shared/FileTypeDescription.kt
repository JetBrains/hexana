package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ArscBinaryProvider
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.binaryParserOf
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.ClrResourcesBinaryProvider
import org.jetbrains.hexana.providers.GgufBinaryProvider
import org.jetbrains.hexana.providers.SafetensorsBinaryProvider
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.ParquetBinaryProvider
import org.jetbrains.hexana.providers.ProtobufBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.TarBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider

/**
 * The format badge's label for [binaryProvider] when the user chose [chosenParser] for the
 * file (`null` when it was detected automatically).
 *
 * Differs from [fileTypeDescription] in one case: a plain [GenericBinaryProvider] labels
 * itself with whatever native header it sniffs, which is right for a file detection gave up
 * on but wrong for one the user asked to see unparsed — a forced "Generic binary" on an ELF
 * would otherwise still read "ELF64" and look as if the choice had not taken.
 */
fun fileTypeDescription(
    binaryProvider: BinaryProvider,
    fileName: String?,
    chosenParser: BinaryParser?,
): String =
    // Matched through binaryParserOf rather than on the exact class: the IntelliJ editor
    // hands over a GenericBinaryVirtualFileProvider subclass, which must count too.
    if (chosenParser == BinaryParser.GENERIC && binaryParserOf(binaryProvider) == BinaryParser.GENERIC) {
        BinaryParser.GENERIC.displayName
    } else {
        fileTypeDescription(binaryProvider, fileName)
    }

fun fileTypeDescription(
    binaryProvider: BinaryProvider,
    fileName: String? = null,
): String = when (binaryProvider) {
    // A Blazor WebAssembly assembly: what the user cares about is the .NET assembly, not the wrapper.
    is WasmBinaryProvider -> if (binaryProvider.webcil != null) "Core Wasm (.NET Webcil)" else "Core Wasm"
    is WasmComponentBinaryProvider -> "Component Model"
    is PeBinaryProvider -> binaryProvider.nativeFormat.displayName + if (binaryProvider.isManaged) " (.NET)" else ""
    is NativeBinaryProvider -> binaryProvider.nativeFormat.displayName
    is ZipBinaryProvider -> if (fileName.orEmpty().endsWith(".jar", true)) "Jar File" else "Zip File"
    is TarBinaryProvider -> "Tar Archive"
    is ClassFileBinaryProvider -> "Class File"
    is DexBinaryProvider -> "Android DEX File"
    is ArscBinaryProvider -> "Android Resource Table"
    is ClrResourcesBinaryProvider -> ".NET Resources"
    is GgufBinaryProvider -> "GGUF Model"
    is SafetensorsBinaryProvider -> "Safetensors Model"
    is PdbBinaryProvider -> "Windows PDB"
    is PortablePdbBinaryProvider -> "Portable PDB"
    is PcapBinaryProvider -> if (binaryProvider.isPcapNg) "PCAP-NG Capture" else "PCAP Capture"
    is ParquetBinaryProvider -> "Apache Parquet"
    is ArrowBinaryProvider -> "Apache Arrow IPC"
    is GenericBinaryProvider -> binaryProvider.nativeFormat?.displayName ?: "Binary"
}

/**
 * Stable, machine-readable format identifier, exposed to user scripts as
 * `binary.format`.
 *
 * Unlike [fileTypeDescription] this does not vary with word size, endianness, or
 * file extension, so a script can branch on it: `if (binary.format === "elf")`.
 * Values are part of the scripting API contract — see
 * `scripting/ScriptApiReference.kt` — and must not be renamed casually.
 */
fun binaryFormatId(binaryProvider: BinaryProvider): String = when (binaryProvider) {
    is WasmBinaryProvider -> "wasm"
    is WasmComponentBinaryProvider -> "wasm-component"
    is ElfBinaryProvider -> "elf"
    is MachOBinaryProvider -> "macho"
    is FatMachOBinaryProvider -> "macho-universal"
    is PeBinaryProvider -> "pe"
    is CoffBinaryProvider -> "coff"
    is ArchiveBinaryProvider -> "archive"
    is ZipBinaryProvider -> "zip"
    is TarBinaryProvider -> "tar"
    is ClassFileBinaryProvider -> "class"
    is DexBinaryProvider -> "dex"
    is ArscBinaryProvider -> "arsc"
    is ClrResourcesBinaryProvider -> "dotnet-resources"
    is GgufBinaryProvider -> "gguf"
    is SafetensorsBinaryProvider -> "safetensors"
    is PdbBinaryProvider -> "pdb"
    is PortablePdbBinaryProvider -> "portable-pdb"
    is PcapBinaryProvider -> if (binaryProvider.isPcapNg) "pcapng" else "pcap"
    is ParquetBinaryProvider -> "parquet"
    is ArrowBinaryProvider -> "arrow"
    is ProtobufBinaryProvider -> "protobuf"
    is GenericBinaryProvider -> "binary"
}
