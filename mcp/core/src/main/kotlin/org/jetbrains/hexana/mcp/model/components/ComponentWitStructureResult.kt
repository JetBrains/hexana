package org.jetbrains.hexana.mcp.model.components

data class ComponentWitStructureResult(
    val layer: Int?,
    val surface: ListComponentSurfaceResult,
    val types: ComponentTypeGraphResult,
)
