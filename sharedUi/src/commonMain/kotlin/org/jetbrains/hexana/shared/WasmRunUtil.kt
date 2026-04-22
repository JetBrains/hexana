package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

/** Well-known WASI namespace prefixes that are provided by the runtime. */
private val RUNTIME_PREFIXES = listOf("wasi:")

/**
 * Returns `true` when [name] is a well-known WASI import that the
 * Wasm runtime provides natively (e.g. `wasi:cli/run@0.2.3`).
 */
fun isRuntimeImport(name: String): Boolean =
    RUNTIME_PREFIXES.any { name.startsWith(it) }

/**
 * Collects the distinct import names from a Wasm component binary.
 * Returns an empty list when the binary is not a valid component.
 */
fun componentImportNames(wasmBytes: ByteArray): List<String> {
    if (WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer()) != BinaryProviderType.WasmComponent) {
        return emptyList()
    }
    val provider = WasmComponentBinaryProvider(wasmBytes.toCommonByteBuffer())
    if (!provider.canProvideWasmModule()) return emptyList()
    return provider.imports.map { it.importName }.distinct()
}

/**
 * Collects the distinct export names from a Wasm component binary.
 * Returns an empty list when the binary is not a valid component.
 */
fun componentExportNames(wasmBytes: ByteArray): List<String> {
    if (WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer()) != BinaryProviderType.WasmComponent) {
        return emptyList()
    }
    val provider = WasmComponentBinaryProvider(wasmBytes.toCommonByteBuffer())
    if (!provider.canProvideWasmModule()) return emptyList()
    return provider.exports.map { it.exportName }.distinct()
}

/**
 * Determines the [BinaryProviderType] for the given raw Wasm bytes.
 */
fun detectBinaryType(wasmBytes: ByteArray): BinaryProviderType =
    WasmBinaryProviderSelector.select(wasmBytes.toCommonByteBuffer())
