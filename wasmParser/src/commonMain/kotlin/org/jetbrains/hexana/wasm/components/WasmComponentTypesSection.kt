package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.Alias.componentAlias
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeString

class WasmComponentTypesSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("Types", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
) {
    companion object {
        const val SECTION_ID: Byte = 7
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scanTypes(): List<ComponentDefType> {
        position(contentOffset)
        val types = mutableListOf<ComponentDefType>()
        consumeArray {
            types.add(componentDefType())
        }
        return types
    }
}


sealed class ComponentDefType: InstanceDeclaration

class ComponentAliasedType(val alias: ComponentAlias) : ComponentDefType()

class ComponentImportedType(val import: WasmComponentImport) : ComponentDefType()

sealed class ComponentPrimitiveType: ComponentDefType()
object ComponentPrimitiveBoolType: ComponentPrimitiveType()
object ComponentPrimitiveS8Type: ComponentPrimitiveType()
object ComponentPrimitiveU8Type: ComponentPrimitiveType()
object ComponentPrimitiveS16Type: ComponentPrimitiveType()
object ComponentPrimitiveU16Type: ComponentPrimitiveType()
object ComponentPrimitiveS32Type: ComponentPrimitiveType()
object ComponentPrimitiveU32Type: ComponentPrimitiveType()
object ComponentPrimitiveS64Type: ComponentPrimitiveType()
object ComponentPrimitiveU64Type: ComponentPrimitiveType()
object ComponentPrimitiveF32Type: ComponentPrimitiveType()
object ComponentPrimitiveF64Type: ComponentPrimitiveType()
object ComponentPrimitiveCharType: ComponentPrimitiveType()
object ComponentPrimitiveString: ComponentPrimitiveType()
object ComponentPrimitiveErrorContext: ComponentPrimitiveType()

sealed class ComponentDefValueType: ComponentDefType()
class ComponentRecordType(val fields: List<LabelValueType>): ComponentDefValueType()
class ComponentVariantType(val variants: List<CaseLabelValueType>): ComponentDefValueType()
class ComponentListType(val elementType: ComponentValueType) : ComponentDefValueType()
class ComponentArrayType(val elementType: ComponentValueType, val len: UInt) : ComponentDefValueType()
class ComponentTupleType(val elementTypes: List<ComponentValueType>) : ComponentDefValueType()
class ComponentFlagType(val flags: List<String>) : ComponentDefValueType()
class ComponentEnumType(val entries: List<String>) : ComponentDefValueType()
class ComponentOptionType(val option: ComponentValueType) : ComponentDefValueType()
class ComponentResultType(val result: ComponentValueType?, val error: ComponentValueType? = null) : ComponentDefValueType()
class ComponentOwnType(val idx: UInt) : ComponentDefValueType()
class ComponentBorrowType(val idx: UInt) : ComponentDefValueType()
class ComponentStreamType(val stream: ComponentValueType? = null) : ComponentDefValueType()
class ComponentFutureType(val future: ComponentValueType? = null) : ComponentDefValueType()

class LabelValueType(val label: String, val value: ComponentValueType)
class CaseLabelValueType(val label: String, val value: ComponentValueType? = null)

interface ComponentReturnType

sealed class ComponentValueType: ComponentDefValueType(), ComponentReturnType
class ComponentValueIdxType(val idx: UInt) : ComponentValueType()
class ComponentPrimitiveValueType(val type: ComponentPrimitiveType) : ComponentValueType()

object ComponentVoidType: ComponentReturnType

sealed class ComponentRoutineType(val params: List<LabelValueType>, val ret: ComponentReturnType) : ComponentDefType()
class ComponentFunctionType(params: List<LabelValueType>, ret: ComponentReturnType): ComponentRoutineType(params, ret)
class ComponentAsyncFunctionType(params: List<LabelValueType>, ret: ComponentReturnType): ComponentRoutineType(params, ret)

class ComponentType(val cd: List<ComponentDeclaration>): ComponentDefType()

sealed class ComponentDeclaration
class ComponentImportDeclaration(val id: ImportDeclaration): ComponentDeclaration()
class ComponentInstanceDeclaration(val id: InstanceDeclaration): ComponentDeclaration()

class ComponentInstanceType(val cd: List<InstanceDeclaration>): ComponentDefType()

class ImportDeclaration(val importName: String, val ed: ComponentExternDescription, val versionSuffix: String? = null): ComponentDeclaration()
class ExportDeclaration(val exportName: String, val ed: ComponentExternDescription, val versionSuffix: String? = null): InstanceDeclaration

sealed class ComponentExternDescription
class ComponentExternCoreType(val idx: UInt): ComponentExternDescription()
class ComponentExternType(val idx: UInt): ComponentExternDescription()
class ComponentExternValueBound(val bound: ValueBound): ComponentExternDescription()
class ComponentExternTypeBound(val bound: TypeBound): ComponentExternDescription()
class ComponentExternComponent(val idx: UInt): ComponentExternDescription()
class ComponentExternInstance(val idx: UInt): ComponentExternDescription()

sealed class ValueBound
class ValueBoundType(val idx: UInt): ValueBound()
class ValueBoundValue(val type: ComponentValueType): ValueBound()

sealed class TypeBound
class TypeBoundType(val idx: UInt): TypeBound()
class TypeBoundSubResource(): TypeBound()

sealed class ComponentResourceType: ComponentDefValueType()
class ComponentAsyncFuncResourceType(val funcIdx: UInt, val callbackIdx: UInt?): ComponentResourceType()
class ComponentSyncFuncResourceType(val funcIdx: UInt? = null): ComponentResourceType()

interface InstanceDeclaration
fun CommonByteBuffer.componentDefType(): ComponentDefType {
    val byte = get()
    return when (byte) {
        in 0x64.toByte()..0x7f.toByte() -> {
            position(position() - 1)
            componentDefValType()
        }

        0x41.toByte() -> {
            componentType()
        }

        0x42.toByte() -> {
            componentInstantType()
        }

        0x3e.toByte(), 0x3f.toByte() -> {
            val subByte = get()
            if (subByte == 0x7f.toByte()) {
                componentResourceType(0x3e.toByte() == byte)
            } else TODO()
        }

        0x40.toByte(), 0x43.toByte() -> {
            componentFunctionType(byte == 0x43.toByte())
        }

        else -> { TODO() }
    }
}

fun CommonByteBuffer.componentDefValType(): ComponentDefValueType {
    val byte = get()
    return when (byte) {
        in 0x73.toByte() ..0x7f.toByte(),
        0x64.toByte() -> {
            position(position() - 1)
            ComponentPrimitiveValueType(componentPrimitiveType()!!)
        }
        0x65.toByte() -> {
            val isNotNull = isNull()
            if (isNotNull) ComponentFutureType(componentValueType()) else ComponentFutureType()
        }
        0x66.toByte() -> {
            val isNotNull = isNull()
            if (isNotNull) ComponentStreamType(componentValueType()) else ComponentStreamType()
        }
        0x67.toByte() -> {
            ComponentArrayType(componentValueType(), uint32())
        }
        0x68.toByte() -> {
            ComponentBorrowType(uint32())
        }
        0x69.toByte() -> {
            ComponentOwnType(uint32())
        }
        0x6a.toByte() -> {
            val result = if (isNull())
                componentValueType()
            else
                null
            val error = if (isNull())
                componentValueType()
            else
                null
            ComponentResultType(result, error)
        }
        0x6b.toByte() -> {
            ComponentOptionType(componentValueType())
        }
        0x6d.toByte() -> {
            ComponentEnumType(mutableListOf<String>().apply {
                consumeArray {
                    add(consumeString())
                }
            })
        }
        0x6e.toByte() -> {
            ComponentFlagType(mutableListOf<String>().apply {
                consumeArray {
                    add(consumeString())
                }
            })
        }
        0x6f.toByte() -> {
            ComponentTupleType(mutableListOf<ComponentValueType>().apply {
                consumeArray {
                    add(componentValueType())
                }
            })
        }
        0x70.toByte() -> {
            ComponentListType(componentValueType())
        }
        0x71.toByte() -> {
            val variants = mutableListOf<CaseLabelValueType>()
            consumeArray {
                val labelLength = uint32()
                val array = ByteArray(labelLength.toInt())
                get(array, 0, labelLength.toInt())
                val labelType = consumeString()
                val isNotNull = isNull()
                val caseType = if (isNotNull) CaseLabelValueType(labelType, componentValueType()) else CaseLabelValueType(labelType)
                variants.add(caseType)
            }
            ComponentVariantType(variants)
        }
        0x72.toByte() -> {
            val flags = mutableListOf<LabelValueType>()
            consumeArray {
                flags.add(labelValueType())
            }
            ComponentRecordType(flags)
        }
        else -> TODO()
    }
}

private fun CommonByteBuffer.isNull(): Boolean = get() == 0x01.toByte()

fun CommonByteBuffer.componentValueType(): ComponentValueType {
    return componentPrimitiveType()?.let { ComponentPrimitiveValueType(it) } ?: run {
        position(position() - 1)
        ComponentValueIdxType(uint32())
    }
}

fun CommonByteBuffer.componentPrimitiveType(): ComponentPrimitiveType? {
    return when (get()) {
        0x64.toByte() -> ComponentPrimitiveErrorContext
        0x73.toByte() -> ComponentPrimitiveString
        0x74.toByte() -> ComponentPrimitiveCharType
        0x75.toByte() -> ComponentPrimitiveF64Type
        0x76.toByte() -> ComponentPrimitiveF32Type
        0x77.toByte() -> ComponentPrimitiveU64Type
        0x78.toByte() -> ComponentPrimitiveS64Type
        0x79.toByte() -> ComponentPrimitiveU32Type
        0x7a.toByte() -> ComponentPrimitiveS32Type
        0x7b.toByte() -> ComponentPrimitiveU16Type
        0x7c.toByte() -> ComponentPrimitiveS16Type
        0x7d.toByte() -> ComponentPrimitiveU8Type
        0x7e.toByte() -> ComponentPrimitiveS8Type
        0x7f.toByte() -> ComponentPrimitiveBoolType
        else -> null
    }
}

fun CommonByteBuffer.componentDeclaration(): ComponentDeclaration {
    return when(get()) {
        0x03.toByte() -> {
            ComponentImportDeclaration(importDeclaration())
        }
        else -> {
            position(position() - 1)
            ComponentInstanceDeclaration(instanceDeclaration())
        }
    }
}

fun CommonByteBuffer.importDeclaration(): ImportDeclaration {
    return when (get()) {
        0x00.toByte() -> ImportDeclaration(consumeString(), externDescription())
        0x01.toByte() -> ImportDeclaration(consumeString(), externDescription(), consumeString())
        else -> TODO()
    }
}

fun CommonByteBuffer.instanceDeclaration(): InstanceDeclaration {
    val type = get()
    return when (type) {
        0x00.toByte() -> {
            componentCoreType()
        }
        0x01.toByte() -> {
            componentDefType()
        }
        0x02.toByte() -> {
            componentAlias()
        }
        0x04.toByte() -> {
            exportDeclaration()
        }
        else -> TODO("type of instance declaration isn't known: 0x${type.toByte().toHexString()}")
    }
}

fun CommonByteBuffer.exportDeclaration(): ExportDeclaration {
    return when (get()) {
        0x00.toByte() -> ExportDeclaration(consumeString(), externDescription())
        0x01.toByte() -> ExportDeclaration(consumeString(), externDescription(), consumeString())
        else -> TODO()
    }
}

fun CommonByteBuffer.componentType(): ComponentType {
    val list = mutableListOf<ComponentDeclaration>()
    consumeArray {
        list.add(componentDeclaration())
    }
    return ComponentType(list)
}

fun CommonByteBuffer.componentInstantType(): ComponentInstanceType  {
    val list = mutableListOf<InstanceDeclaration>()
    consumeArray {
        list.add(instanceDeclaration())
    }
    return ComponentInstanceType(list)
}

fun CommonByteBuffer.componentResourceType(isAsync: Boolean): ComponentResourceType {
    return if (isAsync) {
        val funcIdx = uint32()
        val isNotNull = get() == 0x01.toByte()
        val callbackIdx = if (isNotNull)
            uint32()
        else
            null
        ComponentAsyncFuncResourceType(funcIdx, callbackIdx)
    } else {
        val funcIdx = uint32()
        ComponentSyncFuncResourceType(funcIdx)
    }
}

fun CommonByteBuffer.externDescription(): ComponentExternDescription {
   return when(get()) {
       0x00.toByte() -> {
           if (get() != 0x11.toByte())
               TODO()
           val type = uint32()
           ComponentExternCoreType(type)
       }
       0x01.toByte() -> {
           val type = uint32()
           ComponentExternType(type)
       }
       0x02.toByte() -> {
           ComponentExternValueBound(valueBound())
       }
       0x03.toByte() -> {
           ComponentExternTypeBound(typeBound())
       }
       0x04.toByte() -> {
           ComponentExternComponent(uint32())
       }
       0x05.toByte() -> {
           ComponentExternInstance(uint32())
       }
       else -> TODO()
   }
}

fun CommonByteBuffer.typeBound(): TypeBound {
    return when(val bound = get()) {
        0x00.toByte() -> TypeBoundType(uint32())
        0x01.toByte() -> TypeBoundSubResource()
        else -> error("Invalid type bound discriminant: 0x${bound.toString(16)}")
    }
}

fun CommonByteBuffer.valueBound(): ValueBound {
    return when(get()) {
        0x00.toByte() -> ValueBoundType(uint32())
        0x01.toByte() -> ValueBoundValue(componentValueType())
        else -> TODO()
    }
}

fun CommonByteBuffer.componentFunctionType(isAsync: Boolean): ComponentRoutineType {
    val params = mutableListOf<LabelValueType>()
    consumeArray {
        params.add(labelValueType())
    }
    val v = get()
    val results = when (v) {
        0x00.toByte() -> componentValueType()
        0x01.toByte() -> ComponentVoidType.also { get() /* read 0x00 */ }
        else -> TODO()
    }
    return if(isAsync) {
        ComponentAsyncFunctionType(params, results)
    } else {
        ComponentFunctionType(params, results)
    }
}

fun CommonByteBuffer.labelValueType(): LabelValueType {
    return LabelValueType(consumeString(), componentValueType())
}
