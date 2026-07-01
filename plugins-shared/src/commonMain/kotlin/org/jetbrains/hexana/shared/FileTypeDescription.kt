package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ClassFileBinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider

fun fileTypeDescription(
    binaryProvider: BinaryProvider,
    fileName: String? = null,
): String = when (binaryProvider) {
    is WasmBinaryProvider -> "Core Wasm"
    is WasmComponentBinaryProvider -> "Component Model"
    is NativeBinaryProvider -> binaryProvider.nativeFormat.displayName
    is ZipBinaryProvider -> if (fileName.orEmpty().endsWith(".jar", true)) "Jar File" else "Zip File"
    is ClassFileBinaryProvider -> "Class File"
    is DexBinaryProvider -> "Android DEX File"
    is GenericBinaryProvider -> binaryProvider.nativeFormat?.displayName ?: "Binary"
}
