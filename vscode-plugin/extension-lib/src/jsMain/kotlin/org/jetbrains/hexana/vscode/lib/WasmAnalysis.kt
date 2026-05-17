@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.shared.WasmImportStubGenerator
import org.jetbrains.hexana.shared.componentExportNames
import org.jetbrains.hexana.shared.componentImportNames
import org.jetbrains.hexana.shared.detectBinaryType
import org.jetbrains.hexana.shared.isRuntimeImport
import org.jetbrains.hexana.shared.splitNameAndVersion

/**
 * Returns `true` when the given bytes represent a Wasm component binary.
 */
@JsExport
fun isComponentBinary(bytes: ByteArray): Boolean =
    detectBinaryType(bytes) == BinaryProviderType.WasmComponent

/**
 * Returns `true` when [name] is a well-known WASI import provided
 * by the runtime (e.g. `wasi:cli/run@0.2.3`).
 */
@JsExport
fun isWasiRuntimeImport(name: String): Boolean = isRuntimeImport(name)

/**
 * Strips the `@version` suffix from a WIT name.
 * E.g. `component:foo/bar@0.1.0` → `component:foo/bar`.
 */
@JsExport
fun stripVersion(name: String): String = splitNameAndVersion(name).first

/**
 * Parses component export names from a Wasm component binary.
 * Returns an empty array when the binary is not a valid component.
 */
@JsExport
fun parseComponentExportNames(bytes: ByteArray): Array<String> =
    componentExportNames(bytes).toTypedArray()

/**
 * Parses component import names from a Wasm component binary.
 * Returns an empty array when the binary is not a valid component.
 */
@JsExport
fun parseComponentImportNames(bytes: ByteArray): Array<String> =
    componentImportNames(bytes).toTypedArray()

/**
 * JS-visible representation of a generated import stub module.
 */
@JsExport
data class JsImportModuleStub(
    val moduleName: String,
    val wasmBytes: ByteArray,
)

/**
 * Generates stub Wasm modules for all imports of the given core Wasm binary.
 * Returns one stub per distinct import module name.
 */
@JsExport
fun generateImportStubs(wasmBytes: ByteArray): Array<JsImportModuleStub> =
    WasmImportStubGenerator.generateStubs(wasmBytes).map {
        JsImportModuleStub(it.moduleName, it.wasmBytes)
    }.toTypedArray()
