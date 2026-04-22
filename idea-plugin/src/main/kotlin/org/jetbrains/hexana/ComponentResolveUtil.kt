package org.jetbrains.hexana

import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.shared.resolveInvokeName as sharedResolveInvokeName
import org.jetbrains.hexana.shared.resolveComponentValueType as sharedResolveComponentValueType
import org.jetbrains.hexana.shared.componentTypeName as sharedComponentTypeName
import org.jetbrains.hexana.shared.formatComponentSignature as sharedFormatComponentSignature
import org.jetbrains.hexana.shared.isTrivialComponentType as sharedIsTrivialComponentType
import org.jetbrains.hexana.shared.looksLikeWitInterfaceExport as sharedLooksLikeWitInterfaceExport
import org.jetbrains.hexana.shared.extractNameFromWitExport as sharedExtractNameFromWitExport
import org.jetbrains.hexana.shared.isWasiCliRunExport as sharedIsWasiCliRunExport
import org.jetbrains.hexana.shared.hasWasiCliRunExport as sharedHasWasiCliRunExport
import org.jetbrains.hexana.shared.isComponentFunctionExport as sharedIsComponentFunctionExport
import org.jetbrains.hexana.shared.resolveComponentFunctionType as sharedResolveComponentFunctionType

fun resolveInvokeName(export: WasmComponentExport, provider: WasmComponentBinaryProvider): String =
    sharedResolveInvokeName(export, provider)

fun resolveComponentValueType(type: ComponentValueType, types: List<ComponentDefType>): ComponentDefType? =
    sharedResolveComponentValueType(type, types)

fun componentTypeName(type: ComponentValueType, types: List<ComponentDefType> = emptyList()): String =
    sharedComponentTypeName(type, types)

fun formatComponentSignature(funcType: ComponentRoutineType, types: List<ComponentDefType> = emptyList()): String =
    sharedFormatComponentSignature(funcType, types)

fun isTrivialComponentType(type: ComponentValueType, types: List<ComponentDefType> = emptyList()): Boolean =
    sharedIsTrivialComponentType(type, types)

internal fun looksLikeWitInterfaceExport(name: String): Boolean = sharedLooksLikeWitInterfaceExport(name)

internal fun extractNameFromWitExport(name: String): String? = sharedExtractNameFromWitExport(name)

fun isWasiCliRunExport(export: WasmComponentExport): Boolean = sharedIsWasiCliRunExport(export)

fun hasWasiCliRunExport(provider: WasmComponentBinaryProvider): Boolean = sharedHasWasiCliRunExport(provider)

fun isComponentFunctionExport(export: WasmComponentExport, provider: WasmComponentBinaryProvider): Boolean =
    sharedIsComponentFunctionExport(export, provider)

fun resolveComponentFunctionType(
    export: WasmComponentExport,
    provider: WasmComponentBinaryProvider,
): ComponentRoutineType? = sharedResolveComponentFunctionType(export, provider)
