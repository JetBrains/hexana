package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ArscBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
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

fun fileTypeDescription(
    binaryProvider: BinaryProvider,
    fileName: String? = null,
): String = when (binaryProvider) {
    is WasmBinaryProvider -> "Core Wasm"
    is WasmComponentBinaryProvider -> "Component Model"
    is NativeBinaryProvider -> binaryProvider.nativeFormat.displayName
    is ZipBinaryProvider -> if (fileName.orEmpty().endsWith(".jar", true)) "Jar File" else "Zip File"
    is TarBinaryProvider -> "Tar Archive"
    is ClassFileBinaryProvider -> "Class File"
    is DexBinaryProvider -> "Android DEX File"
    is ArscBinaryProvider -> "Android Resource Table"
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
    is PcapBinaryProvider -> if (binaryProvider.isPcapNg) "pcapng" else "pcap"
    is ParquetBinaryProvider -> "parquet"
    is ArrowBinaryProvider -> "arrow"
    is ProtobufBinaryProvider -> "protobuf"
    is GenericBinaryProvider -> "binary"
}
