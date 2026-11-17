package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.Alias.componentCoreAlias
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.recType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.subtype
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.consumeArrayPartial
import org.jetbrains.hexana.wasm.sections.exportRecord
import org.jetbrains.hexana.wasm.sections.importRecord

class WasmComponentCoreTypeSection(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection("CoreTypes", sectionSize,
    sectionOffset,
    contentSize,
    contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 3
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scanTypes(): List<WasmComponentCoreType> {
        val types = mutableListOf<WasmComponentCoreType>()
        position(contentOffset)
        consumeArray(contentOffset + contentSize) {
            types.add(componentCoreType())
        }

        return types
    }

    /** Returns the decoded core-type prefix and the first unreadable entry offset. */
    fun CommonByteBuffer.scanTypesPartial(): Pair<List<WasmComponentCoreType>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) { componentCoreType() }
            .let { it.values to it.failureOffset }
    }
}

fun CommonByteBuffer.componentCoreType(): WasmComponentCoreType {
    val offset = position()
    val byte = get()
    return when(byte) {
        0x50.toByte() -> componentCoreModuleType()
        0x00.toByte() -> {
            if (get() == 0x50.toByte()) {
                subtype(position())
            }
            else throw IllegalArgumentException("Invalid component core type: ${byte.toHexString()}")
        }
        else ->recType()
    }
}

fun CommonByteBuffer.componentCoreModuleType(): WasmComponentCoreModuleType {
    val offset = position()
    val byte = get()
    return when(byte) {
        0x50.toByte() -> {
            val byte = get()
            val list = mutableListOf<ComponentCoreModuleDeclaration>()
            consumeArray {
                val declaration = when (byte) {
                    0x00.toByte() -> ComponentCoreModuleImportDeclaration(importRecord())
                    0x01.toByte() -> ComponentCoreModuleTypeDeclaration(componentCoreType())
                    0x02.toByte() -> ComponentCoreModuleAliasDeclaration(componentCoreAlias())
                    0x03.toByte() -> ComponentCoreModuleExportDeclaration(exportRecord())
                    else -> throw IllegalArgumentException("Invalid component core module type: ${byte.toHexString()}")
                }

                list.add(declaration)
            }
            WasmComponentCoreModuleType(list)
        }
        else -> throw IllegalArgumentException("Invalid component core module type: ${byte.toHexString()}")
    }
}



interface WasmComponentCoreType: InstanceDeclaration

sealed class ComponentCoreModuleDeclaration
class ComponentCoreModuleImportDeclaration(val i: WasmImportRecord): ComponentCoreModuleDeclaration()
class ComponentCoreModuleTypeDeclaration(val t: WasmComponentCoreType): ComponentCoreModuleDeclaration()
class ComponentCoreModuleAliasDeclaration(val a: ComponentCoreAliasDef): ComponentCoreModuleDeclaration()
class ComponentCoreModuleExportDeclaration(val e: WasmExportRecordEntry): ComponentCoreModuleDeclaration()

class WasmComponentCoreModuleType(val list: List<ComponentCoreModuleDeclaration>) : WasmComponentCoreType
sealed class ComponentCoreTypeDef: InstanceDeclaration
