package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

fun fileTypeDescription(
    binaryProvider: BinaryProvider,
    fileName: String? = null,
): String = when (binaryProvider) {
    is WasmBinaryProvider -> "Core Wasm"
    is WasmComponentBinaryProvider -> "Component Model"
    is NativeBinaryProvider -> binaryProvider.nativeFormat.displayName
    is GenericBinaryProvider -> {
        when {
            isClassFile(binaryProvider.bytes) -> "Class File"
            isZipFile(binaryProvider.bytes) -> if (fileName.orEmpty().endsWith(".jar", true)) "Jar File" else "Zip File"
            isDexFile(binaryProvider.bytes) -> "Android DEX File"
            else -> binaryProvider.nativeFormat?.displayName ?: "Binary"
        }
    }
    else -> "Binary"
}
