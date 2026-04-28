package org.jetbrains.hexana.mcp.tools

val GetTypesForIndicesInput = indicesInput(
    "GetTypesForIndicesInput",
    "List of type indices (`typeidx`) to return.",
)

val TypeFieldResultClass = class_("TypeFieldResult") {
    string("type").describe("Field storage type.")
    boolean("mutable").describe("True when the field is mutable.")
}

val TypeResultClass = class_("TypeResult") {
    string("kind").describe("Type kind (`func`, `struct`, `array`, etc.).")
    int("typeidx").describe("Type index.")
    nullable("elementType", of = TypeFieldResultClass).describe("Array element type, when applicable.")
    nullable("superTypes", of = list(of = uint())).describe("Supertype indices for subtyping, when applicable.")
    nullable("fields", of = list(of = TypeFieldResultClass)).describe("Struct fields, when applicable.")
    nullable("entries", of = list(of = string())).describe("Entry signatures, when applicable.")
    nullable("params", of = list(of = string())).describe("Function parameter types, when applicable.")
    nullable("retTypes", of = list(of = string())).describe("Function return types, when applicable.")
}

val TypesResultClass = class_("TypesResult") {
    list("types", of = TypeResultClass).describe("Declared type entries.")
}

internal val TypeTools: List<ToolSpec> = tools {
    tool("list_types") {
        summary = "List type entries declared by a `.wasm` module."
        guidance = """
            List type entries declared by a WebAssembly (.wasm) module.
            Includes function types and GC-related composite types when present.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = TypesResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readTypes"
    }

    tool("get_types_for_indices") {
        summary = "Return type entries for the requested indices from the type index space."
        guidance = """
            Return only the requested type entries from the type index space.
            Use this when you need the types referenced by specific functions, globals, or tables.
        """
        input = GetTypesForIndicesInput
        inputPattern = ToolInputPattern.Indices
        output = TypesResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readTypesByIndices"
    }
}
