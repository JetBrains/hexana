package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.componentCoreInstance
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeString

class WasmCoreInstanceSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("Instance", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
) {
    companion object {
        const val SECTION_ID: Byte = 2
    }
    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.parseCoreInstances(): List<InstanceCoreExpression> {
        position(contentOffset)
        val instances = mutableListOf<InstanceCoreExpression>()
        consumeArray {
            instances.add(componentCoreInstance())
        }
        return instances
    }
}


data class InstanceCoreInlineExportDescriptor(
    val name: String,
    val sortIndex: ComponentCoreSortIndex,
)

data class InstanceInlineExportDescriptor(
    val name: String,
    val sortIndex: ComponentSortIndex,
)



sealed class InstanceCoreExpression
class ModuleInstanceCoreExpression(
    val moduleIndex: UInt,
    val instantiateArgs: List<InstantiateArg>
): InstanceCoreExpression()

class InlineInstanceCoreExpression(val e: List<InlineInstanceCoreInlineExpression>): InstanceCoreExpression()

sealed class InlineInstanceCoreInlineExpression
class FuncInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()
class TableInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()
class MemoryInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()
class GlobalInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()
class TypeInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()
class ModuleInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()
class InstanceInlineCoreExpression(val name: String, val index: UInt): InlineInstanceCoreInlineExpression()

data class InstantiateArg(val name: String, val instanceIndex: UInt)

object ComponentCoreInstance {

    fun CommonByteBuffer.componentCoreInstance(): InstanceCoreExpression = when (get()) {
        0.toByte() -> {
            ModuleInstanceCoreExpression(uint32(), consumeInstantiateArgs())
        }

        1.toByte() -> {
            val args = mutableListOf<InlineInstanceCoreInlineExpression>()
            consumeArray {
                val (name, sort) = instanceCoreInlineExport()
                when (sort.sort) {
                    ComponentCoreSort.Func -> {
                        args.add(FuncInlineCoreExpression(name, sort.index))
                    }

                    ComponentCoreSort.Table -> {
                        args.add(TableInlineCoreExpression(name, sort.index))
                    }

                    ComponentCoreSort.Memory-> {
                        args.add(MemoryInlineCoreExpression(name, sort.index))
                    }

                    ComponentCoreSort.Global -> {
                        args.add(GlobalInlineCoreExpression(name, sort.index))
                    }

                    ComponentCoreSort.Type -> {
                        args.add(TypeInlineCoreExpression(name, sort.index))
                    }

                    ComponentCoreSort.Module -> {
                        args.add(ModuleInlineCoreExpression(name, sort.index))
                    }

                    ComponentCoreSort.Instance -> {
                        args.add(InstanceInlineCoreExpression(name, sort.index))
                    }
                }
            }
            InlineInstanceCoreExpression(args)
        }

        else -> {
            TODO()
        }
    }

    fun CommonByteBuffer.instanceCoreInlineExport(): InstanceCoreInlineExportDescriptor {
        return InstanceCoreInlineExportDescriptor(consumeString(), componentCoreSortIndex())
    }


    fun CommonByteBuffer.consumeInstantiateArgs(): MutableList<InstantiateArg> {
        val instantiateArgs = mutableListOf<InstantiateArg>()
        consumeArray {
            val name = consumeString()
            get()
            val instanceIndex = uint32()
            instantiateArgs.add(InstantiateArg(name, instanceIndex))
        }
        return instantiateArgs
    }

    fun CommonByteBuffer.componentCoreSort(): ComponentCoreSort {
        val byte = get()
        return when (byte) {
            0x00.toByte() -> ComponentCoreSort.Func
            0x01.toByte() -> ComponentCoreSort.Table
            0x02.toByte() -> ComponentCoreSort.Memory
            0x03.toByte() -> ComponentCoreSort.Global
            0x10.toByte() -> ComponentCoreSort.Type
            0x11.toByte() -> ComponentCoreSort.Module
            0x12.toByte() -> ComponentCoreSort.Instance
            else -> throw IllegalArgumentException("Invalid component core sort: ${byte.toHexString()}")
        }
    }

    fun CommonByteBuffer.componentCoreSortIndex(): ComponentCoreSortIndex {
        return ComponentCoreSortIndex(componentCoreSort(), uint32())
    }

    fun CommonByteBuffer.componentSort(): ComponentSort {
        val byte = get()
        return when (byte) {
            0x00.toByte() -> ComponentSortWithCoreSort(componentCoreSort())
            0x01.toByte() -> ComponentSort.Func
            0x02.toByte() -> ComponentSort.Value
            0x03.toByte() -> ComponentSort.Type
            0x04.toByte() -> ComponentSort.Component
            0x05.toByte() -> ComponentSort.Instance
            else -> throw IllegalArgumentException("Invalid component sort: ${byte.toHexString()}")
        }
    }

    fun CommonByteBuffer.instanceInlineExport(): InstanceInlineExportDescriptor {
        return InstanceInlineExportDescriptor(consumeString(), componentSortWithIndex())
    }


    fun CommonByteBuffer.componentSortWithIndex(): ComponentSortIndex {
        return ComponentSortIndex(componentSort(), uint32())
    }
}

data class ComponentCoreSortIndex(val sort: ComponentCoreSort, val index: UInt)
sealed class ComponentCoreSort {
    object Func: ComponentCoreSort()
    object Table: ComponentCoreSort()
    object Memory: ComponentCoreSort()
    object Global: ComponentCoreSort()
    object Type: ComponentCoreSort()
    object Module: ComponentCoreSort()
    object Instance: ComponentCoreSort()
}

sealed class ComponentSort {
    object Func: ComponentSort()
    object Value: ComponentSort()
    object Type: ComponentSort()
    object Component: ComponentSort()
    object Instance: ComponentSort()
}
class ComponentSortWithCoreSort(val cs: ComponentCoreSort): ComponentSort()
data class ComponentSortIndex(val sort: ComponentSort, val index: UInt)
