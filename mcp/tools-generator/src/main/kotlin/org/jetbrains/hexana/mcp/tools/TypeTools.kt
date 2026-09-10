package org.jetbrains.hexana.mcp.tools

val ListTypesToolId = ToolId("list_types")
val ListValueTypesToolId = ToolId("list_value_types")
val ListReferenceTypesToolId = ToolId("list_reference_types")
val GetTypesForIndicesToolId = ToolId("get_types_for_indices")

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.types"

val GetTypesForIndicesInput = indicesInput(
    "GetTypesForIndicesInput",
    "List of type indices (`typeidx`) to return.",
)

val TypeFieldResultClass = class_("TypeFieldResult") {
    string("type").describe("Field storage type.")
    boolean("mutable").describe("True when the field is mutable.")
}

val TypeImportLocationResultClass = class_("TypeImportLocationResult") {
    string("locationKind").describe("Stable location kind. Function imports use `import`.")
    string("module").describe("Imported module name.")
    string("name").describe("Imported field name.")
    string("locationDisplay").describe("Stable human-readable import location, for example `import host.bind_external`.")
    int("funcidx").describe("Funcidx of the imported function using this type.")
    int("typeidx").describe("Type index referenced by the imported function.")
}

val TypeResultClass = class_("TypeResult") {
    string("kind").describe("Type kind (`func`, `struct`, `array`, etc.).")
    int("typeidx").describe("Type index.")
    list("importLocations", of = TypeImportLocationResultClass)
        .describe("Function imports that reference this type, with stable display strings.")
    nullable("elementType", of = TypeFieldResultClass).describe("Array element type, when applicable.")
    nullable("superTypes", of = list(of = uint())).describe("Supertype indices for subtyping, when applicable.")
    nullable("fields", of = list(of = TypeFieldResultClass)).describe("Struct fields, when applicable.")
    nullable("entries", of = list(of = string())).describe("Entry signatures, when applicable.")
    nullable("params", of = list(of = string())).describe("Function parameter types, when applicable.")
    nullable("retTypes", of = list(of = string())).describe("Function return types, when applicable.")
}

val TypesResultClass = class_("TypesResult", kotlinPackage = MODEL_PACKAGE) {
    list("types", of = TypeResultClass).describe("Declared type entries.")
}

val ReferenceValueTypeResultClass = class_("ReferenceValueTypeResult") {
    string("type").describe("Canonical reference value type, for example `funcref`, `externref`, or `ref struct`.")
    string("refKind").describe("Heap kind without nullability, for example `func`, `extern`, `struct`, or `array`.")
    boolean("nullable").describe("True when the reference type is nullable.")
}

val ValueTypesResultClass = class_("ValueTypesResult") {
    list("scalar", of = string()).describe("Scalar numeric value types used by the module.")
    list("vector", of = string()).describe("Vector value types used by the module.")
    list("packed", of = string()).describe("Packed storage value types used by GC fields or locals.")
    list("reference", of = ReferenceValueTypeResultClass).describe("Reference value types used by the module.")
}

val ValueTypeCountResultClass = class_("ValueTypeCountResult") {
    string("type").describe("Value type name.")
    int("count").describe("Number of observed type occurrences.")
}

val ReferenceValueTypeCountResultClass = class_("ReferenceValueTypeCountResult") {
    string("type").describe("Canonical reference value type.")
    string("refKind").describe("Heap kind without nullability.")
    boolean("nullable").describe("True when the reference type is nullable.")
    int("count").describe("Number of observed type occurrences.")
}

val ValueTypeCategoryCountsResultClass = class_("ValueTypeCategoryCountsResult") {
    list("scalar", of = ValueTypeCountResultClass).describe("Counts for scalar numeric value types.")
    list("vector", of = ValueTypeCountResultClass).describe("Counts for vector value types.")
    list("packed", of = ValueTypeCountResultClass).describe("Counts for packed storage value types.")
    list("reference", of = ReferenceValueTypeCountResultClass).describe("Counts for reference value types.")
}

val ValueTypeCountsResultClass = class_("ValueTypeCountsResult") {
    embed("valueTypes", of = ValueTypeCategoryCountsResultClass).describe("Counts grouped by value-type category.")
}

val ValueTypeSummaryResultClass = class_("ValueTypeSummaryResult") {
    boolean("hasGC").describe("True when GC-only value types or storage types are present.")
    boolean("hasPacked").describe("True when packed storage types such as `i8` or `i16` are present.")
    boolean("hasSIMD").describe("True when `v128` or SIMD instructions are present.")
    boolean("hasReferenceTypes").describe("True when reference value types are present.")
    boolean("pureMVP").describe("True when no post-MVP value-type features were observed.")
}

val ListValueTypesResultClass = class_("ListValueTypesResult", kotlinPackage = MODEL_PACKAGE) {
    embed("valueTypes", of = ValueTypesResultClass).describe("Distinct value types grouped by category.")
    embed("counts", of = ValueTypeCountsResultClass).describe("Observed value-type occurrence counts.")
    embed("summary", of = ValueTypeSummaryResultClass).describe("High-level feature flags inferred from value types.")
}

val ReferenceTypesResultClass = class_("ReferenceTypesResult") {
    list("functionReferences", of = ReferenceValueTypeResultClass).describe("Function reference types such as `funcref`.")
    list("externalReferences", of = ReferenceValueTypeResultClass).describe("External reference types such as `externref`.")
    list("gcReferences", of = ReferenceValueTypeResultClass).describe("GC composite reference types such as `structref` and `arrayref`.")
    list("primitiveReferences", of = ReferenceValueTypeResultClass).describe("Primitive GC reference types such as `i31ref`.")
    list("anyReferences", of = ReferenceValueTypeResultClass).describe("Top-like GC reference types such as `anyref` and `eqref`.")
    list("noneReferences", of = ReferenceValueTypeResultClass).describe("Bottom/null reference types such as `nullref`.")
}

val ReferenceTypesSummaryResultClass = class_("ReferenceTypesSummaryResult") {
    boolean("hasGC").describe("True when GC-only reference kinds are present.")
    boolean("hasI31").describe("True when `i31ref` or `ref i31` is present.")
    boolean("hasStruct").describe("True when struct references are present.")
    boolean("hasArray").describe("True when array references are present.")
    boolean("hasRefEq").describe("True when `eqref` or `ref eq` is present.")
    boolean("hasAnyRef").describe("True when `anyref` or `ref any` is present.")
    boolean("hasExternRef").describe("True when external references are present.")
}

val ListReferenceTypesResultClass = class_("ListReferenceTypesResult", kotlinPackage = MODEL_PACKAGE) {
    embed("referenceTypes", of = ReferenceTypesResultClass).describe("Distinct reference types grouped by semantic family.")
    list("counts", of = ReferenceValueTypeCountResultClass).describe("Observed reference-type occurrence counts.")
    embed("summary", of = ReferenceTypesSummaryResultClass).describe("High-level reference feature flags.")
}

internal val TypeTools: List<ToolSpec> = tools {
    tool(ListTypesToolId) {
        summary = "List type entries declared by a `.wasm` module."
        guidance = """
            List type entries declared by a WebAssembly (.wasm) module.
            Includes function types and GC-related composite types when present.
            Function types include `importLocations` for imported functions that use the type, including a stable `locationDisplay` string.
        """
        input = WasmInputClass
        output = TypesResultClass
    }

    tool(ListValueTypesToolId) {
        summary = "List value types used by a `.wasm` module with counts and feature flags."
        guidance = """
            List scalar, vector, packed, and reference value types observed across declarations, globals, tables, locals, and typed instructions.
            Use this before deeper type or body inspection when you need the module's type vocabulary and a quick signal for GC, SIMD, packed storage, or reference-type usage.
        """
        input = WasmInputClass
        output = ListValueTypesResultClass
    }

    tool(ListReferenceTypesToolId) {
        summary = "List reference value types used by a `.wasm` module."
        guidance = """
            List reference value types grouped by function, external, GC, primitive, top-like, and bottom/null families.
            Use this when the agent needs exact reference syntax and feature signals before writing WAT, diagnosing host interop, or deciding whether GC/reference-type semantics matter.
        """
        input = WasmInputClass
        output = ListReferenceTypesResultClass
    }

    tool(GetTypesForIndicesToolId) {
        summary = "Return type entries for the requested indices from the type index space."
        guidance = """
            Return only the requested type entries from the type index space.
            Use this when you need the types referenced by specific functions, globals, or tables.
        """
        input = GetTypesForIndicesInput
        output = TypesResultClass
    }
}
