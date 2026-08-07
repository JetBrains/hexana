package org.jetbrains.hexana.providers.sbom

/**
 * WasmJs actual for [gunzip]. There is no current wasmJs consumer of the SBOM
 * tab (the browser webapp is built on the js() target). Full Kotlin/Wasm
 * DecompressionStream interop is feasible via @JsFun/@JsExport bridge but
 * not warranted until the webapp moves to the wasmJs target.
 */
internal actual suspend fun gunzip(data: ByteArray): ByteArray? =
    throw UnsupportedOperationException("gunzip is not yet implemented for the wasmJs target")