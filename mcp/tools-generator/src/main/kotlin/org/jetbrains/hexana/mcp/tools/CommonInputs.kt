package org.jetbrains.hexana.mcp.tools

private const val WASM_INPUT_VALIDATION =
    "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`."

val WasmInputClass = class_("WasmInput") {
    nullable("path", string())
        .describe("Path to a local `.wasm` file readable by the Hexana MCP server. $WASM_INPUT_VALIDATION")
    nullable("bytesBase64", string())
        .describe("Base64-encoded `.wasm` bytes. $WASM_INPUT_VALIDATION")
        .maxLength(SchemaIntValue.MaxInlineWasmBase64Length)
    nullable("bytes", list(of = int()))
        .describe("Raw `.wasm` bytes as integers in the range 0..255. $WASM_INPUT_VALIDATION")
        .itemsMinimum(0)
        .itemsMaximum(255)
        .maxItems(SchemaIntValue.MaxInlineWasmBytes)
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
