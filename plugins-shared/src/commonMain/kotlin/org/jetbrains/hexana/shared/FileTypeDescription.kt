package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.PcapBinaryProvider
import org.jetbrains.hexana.providers.ParquetBinaryProvider
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
    is PcapBinaryProvider -> if (binaryProvider.isPcapNg) "PCAP-NG Capture" else "PCAP Capture"
    is ParquetBinaryProvider -> "Apache Parquet"
    is ArrowBinaryProvider -> "Apache Arrow IPC"
    is GenericBinaryProvider -> binaryProvider.nativeFormat?.displayName ?: "Binary"
}
