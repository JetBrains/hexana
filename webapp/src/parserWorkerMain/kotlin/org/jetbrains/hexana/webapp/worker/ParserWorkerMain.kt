package org.jetbrains.hexana.webapp.worker

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmModule
import org.khronos.webgl.ArrayBuffer

private const val PROTOCOL_VERSION = 1
private const val SCOPE_MARKER = "DedicatedWorkerGlobalScope"

fun main() {
    val workerScope = js("self")
    val isDedicatedWorker = js(
        "typeof DedicatedWorkerGlobalScope !== 'undefined' && self instanceof DedicatedWorkerGlobalScope"
    ).unsafeCast<Boolean>()
    val documentAbsent = js("typeof document === 'undefined'").unsafeCast<Boolean>()

    workerScope.onmessage = { event: dynamic ->
        val request = event.data
        val requestId = if (request == null) null else request.requestId as? String
        if (!isDedicatedWorker || !documentAbsent) {
            postError(workerScope, requestId ?: "", "INVALID_SCOPE", "Parser worker scope is unavailable.")
        } else if (
            !isPlainObject(request) ||
            request.protocolVersion != PROTOCOL_VERSION ||
            request.kind != "parse" ||
            requestId.isNullOrBlank() ||
            !js("request.buffer instanceof ArrayBuffer").unsafeCast<Boolean>()
        ) {
            postError(workerScope, requestId ?: "", "INVALID_REQUEST", "Parser worker request is invalid.")
        } else {
            parse(workerScope, requestId, request.buffer.unsafeCast<ArrayBuffer>())
        }
    }
}

private fun isPlainObject(value: dynamic): Boolean =
    value != null && js("Object.prototype.toString.call(value) === '[object Object]'").unsafeCast<Boolean>()

private fun parse(workerScope: dynamic, requestId: String, input: ArrayBuffer) {
    val startedAt = now()
    try {
        val buffer = input.toCommonByteBuffer()
        val result = WasmParser().parse(buffer)
        val module = (result as? WasmParseSuccess)?.wasmModule as? WasmModule
            ?: return postError(workerScope, requestId, "PARSE_FAILED", "The input is not a supported core WebAssembly module.")

        val importCount = module.importSection?.let { with(it) { buffer.getImportRecords().size } } ?: 0
        val definedFunctionCount = module.functionSection?.let { with(it) { buffer.parseFunctions().size } } ?: 0
        val codeBodyCount = module.codeSection?.let { with(it) { buffer.scan().size } } ?: 0

        val response = js("({})")
        response.protocolVersion = PROTOCOL_VERSION
        response.kind = "success"
        response.requestId = requestId
        response.scopeMarker = SCOPE_MARKER
        response.documentAbsent = true
        response.inputBytes = input.byteLength
        response.sectionCount = module.sections.size
        response.importCount = importCount
        response.definedFunctionCount = definedFunctionCount
        response.codeBodyCount = codeBodyCount
        response.elapsedMs = now() - startedAt
        workerScope.postMessage(response)
    } catch (_: Throwable) {
        postError(workerScope, requestId, "PARSE_FAILED", "The input could not be parsed safely.")
    }
}

private fun postError(
    workerScope: dynamic,
    requestId: String,
    code: String,
    message: String,
) {
    val response = js("({})")
    response.protocolVersion = PROTOCOL_VERSION
    response.kind = "error"
    response.requestId = requestId
    response.code = code
    response.message = message
    workerScope.postMessage(response)
}

private fun now(): Double = js("performance.now()").unsafeCast<Double>()
