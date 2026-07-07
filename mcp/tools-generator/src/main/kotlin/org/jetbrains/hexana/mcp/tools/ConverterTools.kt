package org.jetbrains.hexana.mcp.tools

val WasmToWatResultClass = class_(
    "WasmToWatResult",
    kotlinPackage = "org.jetbrains.hexana.mcp.model.converters",
) {
    outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.RawTextPlaceholder)
    string("wat").describe("Converted WebAssembly text format.")
    list("warnings", of = string()).describe("Warnings emitted during conversion.")
}

val ConverterTools = tools {
    tool("wasm_to_wat") {
        summary = "Convert a `.wasm` module to WebAssembly text format."
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = WasmToWatResultClass
        surfaces = noMcpCliSurface(
            "wasm-to-wat",
            ToolGroup.Converters,
            ToolOutputPolicy.ToolDefined,
            defaultOutput = OutputConversionCapability.RawTextPlaceholder,
        )
        handlerFqn = "org.jetbrains.hexana.mcp.server.wasmToWat"
    }
}
