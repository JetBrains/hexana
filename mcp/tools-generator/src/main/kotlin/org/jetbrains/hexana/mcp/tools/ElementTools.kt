package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.elements"

val ElementSegmentResultClass = class_("ElementSegmentResult") {
    int("elementidx").describe("Element segment index.")
    int("kind").describe("Raw element segment kind byte.")
    string("mode").describe("Segment mode (`active`, `passive`, `declarative`).")
    nullable("tableIdx", int()).describe("Target table index for active segments.")
    nullable("offsetExpr", of = list(of = string())).describe("Offset expression tokens for active segments.")
    string("elementType").describe("Element reference type.")
    nullable("elements", of = list(of = string())).describe("Element expressions or funcidx list.")
}

val ListElementSegmentsResultClass = class_("ListElementSegmentsResult", kotlinPackage = MODEL_PACKAGE) {
    list("segments", of = ElementSegmentResultClass).describe("Element segments declared by the module.")
}

internal val ElementTools: List<ToolSpec> = tools {
    tool("list_element_segments") {
        summary = "List element segments declared by a `.wasm` module."
        surfaces = productionCliSurface("list-element-segments", ToolGroup.Element, ToolOutputPolicy.ToolDefined)
        guidance = """
            List element segments declared by a WebAssembly (.wasm) module.
            Covers active, passive, and declarative segments, including function-index and expression-based initializers.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListElementSegmentsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readElementSegments"
    }
}
