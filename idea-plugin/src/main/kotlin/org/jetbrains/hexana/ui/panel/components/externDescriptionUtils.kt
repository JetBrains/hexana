package org.jetbrains.hexana.ui.panel.components

import org.jetbrains.hexana.wasm.components.ComponentExternComponent
import org.jetbrains.hexana.wasm.components.ComponentExternCoreType
import org.jetbrains.hexana.wasm.components.ComponentExternDescription
import org.jetbrains.hexana.wasm.components.ComponentExternInstance
import org.jetbrains.hexana.wasm.components.ComponentExternType
import org.jetbrains.hexana.wasm.components.ComponentExternTypeBound
import org.jetbrains.hexana.wasm.components.ComponentExternValueBound
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveBoolType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveErrorContext
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveString
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType
import org.jetbrains.hexana.wasm.components.ComponentValueIdxType
import org.jetbrains.hexana.wasm.components.ComponentValueType
import org.jetbrains.hexana.wasm.components.TypeBound
import org.jetbrains.hexana.wasm.components.TypeBoundSubResource
import org.jetbrains.hexana.wasm.components.TypeBoundType
import org.jetbrains.hexana.wasm.components.ValueBound
import org.jetbrains.hexana.wasm.components.ValueBoundType
import org.jetbrains.hexana.wasm.components.ValueBoundValue

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
