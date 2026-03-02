package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.consumeInstantiateArgs
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.instanceInlineExport
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray

class WasmInstanceSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) : WasmSection(
    "Instance", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
) {
    companion object {
        const val SECTION_ID: Byte = 5
    }

    constructor(s: WasmSectionUnresolved) : this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.parseInstances(): List<InstanceExpression> {
        position(contentOffset)
        val instanceExpressions = mutableListOf<InstanceExpression>()
        consumeArray {
            when (get()) {
                0x00.toByte() -> {
                    instanceExpressions.add(ComponentInstanceExpression(uint32(), consumeInstantiateArgs()))
                }

                0x01.toByte() -> {
                    val args = mutableListOf<InstanceInlineExport>()
                    consumeArray {
                        val (name, sort) = instanceInlineExport()
                        val expression = when (sort.sort) {
                            is ComponentSortWithCoreSort -> {
                                CoreInstanceInlineExport(name, sort.sort.cs, sort.index)
                            }

                            ComponentSort.Func -> {
                                FuncInstanceInlineExport(name, sort.index)
                            }

                            ComponentSort.Value -> {
                                ValueInstanceInlineExport(name, sort.index)
                            }

                            ComponentSort.Type -> {
                                TypeInstanceInlineExport(name, sort.index)
                            }

                            ComponentSort.Component -> {
                                ComponentInstanceInlineExport(name, sort.index)
                            }

                            ComponentSort.Instance -> {
                                InstanceInlineExportExpression(name, sort.index)
                            }
                        }

                        args.add(expression)
                    }
                    instanceExpressions.add(InlineInstanceExpression(args))
                }
            }
        }
        return instanceExpressions
    }
}


sealed class InstanceExpression
class ComponentInstanceExpression(val index: UInt, val args: List<InstantiateArg>) : InstanceExpression()
class InlineInstanceExpression(val e: List<InstanceInlineExport>) : InstanceExpression()

sealed class InstanceInlineExport
class CoreInstanceInlineExport(val name: String, val cs: ComponentCoreSort, val index: UInt) : InstanceInlineExport()
class FuncInstanceInlineExport(val name: String, val index: UInt) : InstanceInlineExport()
class ValueInstanceInlineExport(val name: String, val index: UInt) : InstanceInlineExport()
class TypeInstanceInlineExport(val name: String, val index: UInt) : InstanceInlineExport()
class ComponentInstanceInlineExport(val name: String, val index: UInt) : InstanceInlineExport()
class InstanceInlineExportExpression(val name: String, val index: UInt) : InstanceInlineExport()