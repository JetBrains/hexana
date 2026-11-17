package org.jetbrains.hexana.mcp.tools

private const val WASM_INPUT_VALIDATION =
    "Provide exactly one of `path`, `bytesBase64`, or `bytes`."
private const val BINARY_INPUT_VALIDATION =
    "Provide exactly one of `path`, `bytesBase64`, or `bytes`."

private val wasmInput = class_("WasmInput") {
    inputCapabilities(
        InputConversionCapability.JsonObject,
        InputConversionCapability.WasmPath,
        InputConversionCapability.WasmFromString,
        InputConversionCapability.Stdin,
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
}

val WasmInputClass = wasmInput.withExactOneSourceGroup(
    "wasm input",
    pathSource(wasmInput.fieldRef("path")),
    base64Source(wasmInput.fieldRef("bytesBase64")),
    bytesSource(wasmInput.fieldRef("bytes")),
)

private val binaryInput = class_("BinaryInput") {
    inputCapabilities(
        InputConversionCapability.JsonObject,
        InputConversionCapability.BinaryPath,
        InputConversionCapability.Stdin,
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
}

val BinaryInputClass = binaryInput.withExactOneSourceGroup(
    "binary input",
    pathSource(binaryInput.fieldRef("path")),
    base64Source(binaryInput.fieldRef("bytesBase64")),
    bytesSource(binaryInput.fieldRef("bytes")),
)

internal val ClosedIntRange = class_("ClosedIntRange") {
    int("from").describe("Inclusive zero-based lower bound.").minimum(0)
    int("to").describe("Inclusive zero-based upper bound.").minimum(0)
}

internal fun indicesInput(name: String, indicesDescription: String): ClassSpec =
    WasmInputClass.with(name) {
        list("indices", of = int())
            .describe(indicesDescription)
            .itemsMinimum(0)
            .maxItems(10_000)
    }
