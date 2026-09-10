package org.jetbrains.hexana.mcp.tools

val WasmToWatToolId = ToolId("wasm_to_wat")

val WasmToWatResultClass = class_(
    "WasmToWatResult",
    kotlinPackage = "org.jetbrains.hexana.mcp.model.converters",
) {
    outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.RawText)
    string("wat").describe("Converted WebAssembly text format.")
    list("warnings", of = string()).describe("Warnings emitted during conversion.")
}

val ConverterTools = tools {
    tool(WasmToWatToolId) {
        summary = "Convert a `.wasm` module to WebAssembly text format."
        input = WasmInputClass
        output = WasmToWatResultClass
    }
}
