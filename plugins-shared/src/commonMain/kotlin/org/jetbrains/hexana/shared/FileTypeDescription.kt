package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
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
    is GenericBinaryProvider -> {
        when {
            isClassFile(binaryProvider.bytes) -> "Class File"
            isDexFile(binaryProvider.bytes) -> "Android DEX File"
            else -> binaryProvider.nativeFormat?.displayName ?: "Binary"
        }
    }
    else -> "Binary"
}
