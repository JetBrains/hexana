package org.jetbrains.hexana.mcp.tools

val WasmToWatResultClass = class_(
    "WasmToWatResult",
    kotlinPackage = "org.jetbrains.hexana.mcp.model.converters",
) {
    outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.RawText)
    string("wat").describe("Converted WebAssembly text format.")
    list("warnings", of = string()).describe("Warnings emitted during conversion.")
}

val ConverterTools = tools {
    tool("wasm_to_wat") {
        summary = "Convert a `.wasm` module to WebAssembly text format."
        input = WasmInputClass
        output = WasmToWatResultClass
    }
}
