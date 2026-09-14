package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.componentCoreSort
import org.jetbrains.hexana.wasm.components.ComponentCoreInstance.componentSort
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeString
import org.jetbrains.hexana.wasm.sections.consumeArrayPartial
import org.jetbrains.hexana.wasm.sections.consumeUnsignedUInt

class WasmAliasSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("Alias", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset
) {
    companion object {
        const val SECTION_ID: Byte = 6
    }
    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scanAliases(): List<ComponentAliasDef> {
        position(contentOffset)
        val aliases = mutableListOf<ComponentAliasDef>()
        consumeArray(contentOffset + contentSize) {
            aliases.add(Alias.run { componentAlias() })
        }
        return aliases
    }

    /** Returns the decoded alias prefix and the first unreadable entry offset. */
    fun CommonByteBuffer.scanAliasesPartial(): Pair<List<ComponentAliasDef>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) { Alias.run { componentAlias() } }.let { it.values to it.failureOffset }
    }
}


sealed class ComponentAliasDef: InstanceDeclaration

object Alias {
    fun CommonByteBuffer.componentAlias(): ComponentAlias {
        return ComponentAlias(componentSort(), aliasTarget())
    }

    fun CommonByteBuffer.componentCoreAlias(): ComponentCoreAliasDef {
        return ComponentCoreAliasDef(componentCoreSort(), aliasCoreTarget())
    }

    fun CommonByteBuffer.aliasTarget(): AliasTarget {
        return when (val byte = get()) {
            0x00.toByte() -> AliasTarget.Export(consumeUnsignedUInt(), consumeString())
            0x01.toByte() -> AliasTarget.Core(consumeUnsignedUInt(), consumeString())
            0x02.toByte() -> AliasTarget.Outer(consumeUnsignedUInt(), consumeUnsignedUInt())
            else -> throw IllegalArgumentException("Invalid alias target type: ${byte.toHexString()}")
        }
    }
    fun CommonByteBuffer.aliasCoreTarget(): AliasTarget {
        return when (val byte = get()) {
            0x01.toByte() -> AliasTarget.CoreOuter(consumeUnsignedUInt(), consumeUnsignedUInt())
            else -> throw IllegalArgumentException("Invalid alias target type: ${byte.toHexString()}")
        }
    }
}

class ComponentAlias( val componentSort: ComponentSort, val aliasTarget: AliasTarget ): ComponentAliasDef()
class ComponentCoreAliasDef(val componentCoreSort: ComponentCoreSort, val aliasCoreTarget: AliasTarget): ComponentAliasDef()


sealed class AliasTarget {
    data class Export(val index: UInt, val name: String): AliasTarget()
    data class Core(val index: UInt, val name: String): AliasTarget()
    data class Outer(val index: UInt, val outerIndex: UInt): AliasTarget()
    data class CoreOuter(val index: UInt, val outerIndex: UInt): AliasTarget()
}
