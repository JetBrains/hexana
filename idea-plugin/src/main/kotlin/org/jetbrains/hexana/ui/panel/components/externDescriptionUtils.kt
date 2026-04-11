package org.jetbrains.hexana.ui.panel.components

import org.jetbrains.hexana.wasm.components.*

internal fun ComponentExternDescription.toStringRepresentation() = when (this) {
    is ComponentExternCoreType -> "Extern(Core:Type (${this.idx}))"
    is ComponentExternTypeBound -> "Extern(Type (${this.bound.toStringRepresentation()}))"
    is ComponentExternValueBound -> "Extern(Value (${this.bound.toStringRepresentation()}))"
    is ComponentExternComponent -> "Component(${this.idx})"
    is ComponentExternInstance -> "Instance(${this.idx})"
    is ComponentExternType -> "Extern(Type (${this.idx}))"
}

internal fun TypeBound.toStringRepresentation() = when (this) {
    is TypeBoundType -> "Type(${this.idx})"
    is TypeBoundSubResource -> "SubResource"
}

internal fun ValueBound.toStringRepresentation() = when (this) {
    is ValueBoundType -> "Type(${this.idx})"
    is ValueBoundValue -> "Value(${this.type.toStringRepresentation()})"
}

internal fun ComponentValueType.toStringRepresentation() = when (this) {
    is ComponentValueIdxType -> "Type(${this.idx})"
    is ComponentPrimitiveValueType -> when (this.type) {
        is ComponentPrimitiveBoolType -> "Bool"
        is ComponentPrimitiveS8Type -> "S8"
        is ComponentPrimitiveU8Type -> "U8"
        is ComponentPrimitiveS16Type -> "S16"
        is ComponentPrimitiveU16Type -> "U16"
        is ComponentPrimitiveS32Type -> "S32"
        is ComponentPrimitiveU32Type -> "U32"
        is ComponentPrimitiveS64Type -> "S64"
        is ComponentPrimitiveU64Type -> "U64"
        is ComponentPrimitiveF32Type -> "F32"
        is ComponentPrimitiveF64Type -> "F64"
        is ComponentPrimitiveString -> "String"
        is ComponentPrimitiveCharType -> "Char"
        is ComponentPrimitiveErrorContext -> "ErrorContext"
    }
}

internal fun ComponentSortIndex.toStringRepresentation(): String {
    return when (val sort = this.sort) {
        is ComponentSort.Func -> "Func(${this.index})"
        is ComponentSort.Value -> "Value(${this.index})"
        is ComponentSort.Type -> "Type(${this.index})"
        is ComponentSort.Component -> "Component(${this.index})"
        is ComponentSort.Instance -> "Instance(${this.index})"
        is ComponentSortWithCoreSort -> when (sort.cs) {
            is ComponentCoreSort.Func -> "Core:Func(${this.index})"
            is ComponentCoreSort.Table -> "Core:Table(${this.index})"
            is ComponentCoreSort.Memory -> "Core:Memory(${this.index})"
            is ComponentCoreSort.Global -> "Core:Global(${this.index})"
            is ComponentCoreSort.Type -> "Core:Type(${this.index})"
            is ComponentCoreSort.Module -> "Core:Module(${this.index})"
            is ComponentCoreSort.Instance -> "Core:Instance(${this.index})"
        }
    }
}
