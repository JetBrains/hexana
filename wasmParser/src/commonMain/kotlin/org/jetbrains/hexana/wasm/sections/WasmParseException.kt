package org.jetbrains.hexana.wasm.sections

/** Thrown when a malformed byte sequence is encountered during WASM binary parsing. */
class WasmParseException(message: String) : Exception(message)