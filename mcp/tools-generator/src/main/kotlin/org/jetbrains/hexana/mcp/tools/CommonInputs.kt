package org.jetbrains.hexana.mcp.tools

private const val WASM_INPUT_VALIDATION =
    "Provide exactly one of `path`, `bytesBase64`, or `bytes`."
private const val BINARY_INPUT_VALIDATION =
    "Provide exactly one of `path`, `bytesBase64`, or `bytes`."

val WasmInputClass = class_("WasmInput") {
    inputCapabilities(
        InputConversionCapability.JsonObject,
        InputConversionCapability.WasmPath,
        InputConversionCapability.WasmFromString,
        InputConversionCapability.StdinPlaceholder,
    )
    nullable("path", string())
        .describe("Readable local `.wasm` path. $WASM_INPUT_VALIDATION")
    nullable("bytesBase64", string())
        .describe("Base64 `.wasm` bytes. $WASM_INPUT_VALIDATION")
        .maxLength(SchemaIntValue.MaxInlineWasmBase64Length)
    nullable("bytes", list(of = int()))
        .describe("Raw `.wasm` bytes as 0..255 integers. $WASM_INPUT_VALIDATION")
        .itemsMinimum(0)
        .itemsMaximum(255)
        .maxItems(SchemaIntValue.MaxInlineWasmBytes)
    oneOf("path", "bytesBase64", "bytes")
}

val BinaryInputClass = class_("BinaryInput") {
    inputCapabilities(
        InputConversionCapability.JsonObject,
        InputConversionCapability.BinaryPath,
        InputConversionCapability.StdinPlaceholder,
    )
    nullable("path", string())
        .describe("Readable local binary path. $BINARY_INPUT_VALIDATION")
    nullable("bytesBase64", string())
        .describe("Base64 binary bytes. $BINARY_INPUT_VALIDATION")
        .maxLength(SchemaIntValue.MaxInlineBinaryBase64Length)
    nullable("bytes", list(of = int()))
        .describe("Raw binary bytes as 0..255 integers. $BINARY_INPUT_VALIDATION")
        .itemsMinimum(0)
        .itemsMaximum(255)
        .maxItems(SchemaIntValue.MaxInlineBinaryBytes)
    oneOf("path", "bytesBase64", "bytes")
}

internal val ClosedIntRange = class_("ClosedIntRange") {
    int("from").describe("Inclusive zero-based lower bound.").minimum(0)
    int("to").describe("Inclusive zero-based upper bound.").minimum(0)
}

internal fun indicesInput(name: String, indicesDescription: String): ClassSpec =
    WasmInputClass.with(name) {
        list("indices", of = int())
            .describe(indicesDescription)
            .itemsMinimum(0)
    }
