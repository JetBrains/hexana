package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.components.*

internal val WIT_INTERFACE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9-]*:[a-zA-Z][a-zA-Z0-9-]*/([a-zA-Z][a-zA-Z0-9-]*)(@.+)?$")

private val WASI_CLI_RUN_PATTERN = Regex("^wasi:cli/run(@.+)?$")

fun looksLikeWitInterfaceExport(name: String): Boolean = WIT_INTERFACE_PATTERN.matches(name)

fun extractNameFromWitExport(name: String): String? =
    WIT_INTERFACE_PATTERN.matchEntire(name)?.groupValues?.get(1)

fun isWasiCliRunExportName(name: String): Boolean =
    WASI_CLI_RUN_PATTERN.matches(name)

fun isWasiCliRunExport(export: WasmComponentExport): Boolean =
    WASI_CLI_RUN_PATTERN.matches(export.exportName)

fun hasWasiCliRunExport(provider: WasmComponentBinaryProvider): Boolean =
    provider.exports.any { isWasiCliRunExport(it) }

fun isComponentFunctionExport(export: WasmComponentExport, provider: WasmComponentBinaryProvider): Boolean {
    val sort = export.sortIndex.sort
    if (sort is ComponentSort.Func ||
        (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Func)) {
        return true
    }
    if (sort is ComponentSort.Instance) {
        val instance = provider.instances.getOrNull(export.sortIndex.index.toInt())
        if (instance is InlineInstanceExpression) {
            return instance.e.any { it is FuncInstanceInlineExport }
        }
        if (instance is ComponentInstanceExpression) {
            val componentExports = provider.nestedComponentExports(instance.index.toInt())
            return componentExports.any {
                it.sortIndex.sort is ComponentSort.Func ||
                    (it.sortIndex.sort is ComponentSortWithCoreSort && (it.sortIndex.sort as ComponentSortWithCoreSort).cs is ComponentCoreSort.Func)
            }
        }
        if (instance == null && looksLikeWitInterfaceExport(export.exportName)) {
            return true
        }
    }
    if (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Instance) {
        val coreInstance = provider.coreInstances.getOrNull(export.sortIndex.index.toInt())
        if (coreInstance is InlineInstanceCoreExpression) {
            return coreInstance.e.any { it is FuncInlineCoreExpression }
        }
    }
    return false
}

fun resolveInvokeName(export: WasmComponentExport, provider: WasmComponentBinaryProvider): String {
    val sort = export.sortIndex.sort
    if (sort is ComponentSort.Instance) {
        val instance = provider.instances.getOrNull(export.sortIndex.index.toInt())
        if (instance is InlineInstanceExpression) {
            val funcExport = instance.e.filterIsInstance<FuncInstanceInlineExport>().firstOrNull()
            if (funcExport != null) return funcExport.name
        }
        if (instance is ComponentInstanceExpression) {
            val componentExports = provider.nestedComponentExports(instance.index.toInt())
            val funcExport = componentExports.firstOrNull {
                it.sortIndex.sort is ComponentSort.Func ||
                    (it.sortIndex.sort is ComponentSortWithCoreSort && (it.sortIndex.sort as ComponentSortWithCoreSort).cs is ComponentCoreSort.Func)
            }
            if (funcExport != null) return funcExport.exportName
        }
        val witName = extractNameFromWitExport(export.exportName)
        if (witName != null) return witName
    }
    if (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Instance) {
        val coreInstance = provider.coreInstances.getOrNull(export.sortIndex.index.toInt())
        if (coreInstance is InlineInstanceCoreExpression) {
            val funcExport = coreInstance.e.filterIsInstance<FuncInlineCoreExpression>().firstOrNull()
            if (funcExport != null) return funcExport.name
        }
    }
    return export.exportName
}

fun resolveComponentValueType(type: ComponentValueType, types: List<ComponentDefType>): ComponentDefType? {
    if (type is ComponentValueIdxType) return types.getOrNull(type.idx.toInt())
    return null
}

fun componentTypeName(type: ComponentValueType, types: List<ComponentDefType> = emptyList()): String = when (type) {
    is ComponentPrimitiveValueType -> when (type.type) {
        is ComponentPrimitiveBoolType -> "bool"
        is ComponentPrimitiveU8Type -> "u8"
        is ComponentPrimitiveS8Type -> "s8"
        is ComponentPrimitiveU16Type -> "u16"
        is ComponentPrimitiveS16Type -> "s16"
        is ComponentPrimitiveU32Type -> "u32"
        is ComponentPrimitiveS32Type -> "s32"
        is ComponentPrimitiveU64Type -> "u64"
        is ComponentPrimitiveS64Type -> "s64"
        is ComponentPrimitiveF32Type -> "f32"
        is ComponentPrimitiveF64Type -> "f64"
        is ComponentPrimitiveCharType -> "char"
        is ComponentPrimitiveString -> "string"
        else -> type.type.toString()
    }
    is ComponentValueIdxType -> {
        when (val resolved = types.getOrNull(type.idx.toInt())) {
            is ComponentEnumType -> "enum{${resolved.entries.joinToString(", ")}}"
            null -> "type(${type.idx})"
            else -> "type(${type.idx})"
        }
    }
}

fun formatComponentSignature(funcType: ComponentRoutineType, types: List<ComponentDefType> = emptyList()): String {
    val params = funcType.params.joinToString(", ") { "${it.label}: ${componentTypeName(it.value, types)}" }
    val result = when (val ret = funcType.ret) {
        is ComponentValueType -> componentTypeName(ret, types)
        is ComponentVoidType -> "void"
        else -> ret.toString()
    }
    return "($params) -> $result"
}

fun isTrivialComponentType(type: ComponentValueType, types: List<ComponentDefType> = emptyList()): Boolean {
    if (type is ComponentPrimitiveValueType) {
        return when (type.type) {
            is ComponentPrimitiveBoolType,
            is ComponentPrimitiveU8Type, is ComponentPrimitiveS8Type,
            is ComponentPrimitiveU16Type, is ComponentPrimitiveS16Type,
            is ComponentPrimitiveU32Type, is ComponentPrimitiveS32Type,
            is ComponentPrimitiveU64Type, is ComponentPrimitiveS64Type,
            is ComponentPrimitiveF32Type, is ComponentPrimitiveF64Type,
            is ComponentPrimitiveCharType, is ComponentPrimitiveString -> true
            else -> false
        }
    }
    if (type is ComponentValueIdxType) {
        val resolved = types.getOrNull(type.idx.toInt())
        if (resolved is ComponentEnumType) return true
    }
    return false
}

fun resolveComponentFunctionType(
    export: WasmComponentExport,
    provider: WasmComponentBinaryProvider,
): ComponentRoutineType? {
    val ed = export.externDescription
    if (ed is ComponentExternType) {
        val type = provider.types.getOrNull(ed.idx.toInt())
        if (type is ComponentRoutineType) return type
    }
    if (ed is ComponentExternInstance) {
        val instanceType = provider.types.getOrNull(ed.idx.toInt())
        if (instanceType is ComponentInstanceType) {
            val funcDecl = instanceType.cd.filterIsInstance<ExportDeclaration>()
                .firstOrNull { it.ed is ComponentExternType }
            if (funcDecl != null) {
                val funcType = provider.types.getOrNull((funcDecl.ed as ComponentExternType).idx.toInt())
                if (funcType is ComponentRoutineType) return funcType
            }
        }
    }
    val sort = export.sortIndex.sort
    if (sort is ComponentSort.Instance) {
        val instance = provider.instances.getOrNull(export.sortIndex.index.toInt())
        if (instance is ComponentInstanceExpression) {
            val nestedExports = provider.nestedComponentExports(instance.index.toInt())
            val nestedTypes = provider.nestedComponentTypes(instance.index.toInt())
            val funcExport = nestedExports.firstOrNull {
                it.sortIndex.sort is ComponentSort.Func ||
                    (it.sortIndex.sort is ComponentSortWithCoreSort && (it.sortIndex.sort as ComponentSortWithCoreSort).cs is ComponentCoreSort.Func)
            }
            if (funcExport != null) {
                val nestedEd = funcExport.externDescription
                if (nestedEd is ComponentExternType) {
                    val type = nestedTypes.getOrNull(nestedEd.idx.toInt())
                    if (type is ComponentRoutineType) return type
                }
                val fallbackType = nestedTypes.filterIsInstance<ComponentRoutineType>().firstOrNull()
                if (fallbackType != null) return fallbackType
            }
        }
        if (instance == null && looksLikeWitInterfaceExport(export.exportName)) {
            val routineType = provider.types.filterIsInstance<ComponentRoutineType>().firstOrNull()
            if (routineType != null) return routineType
        }
    }
    return null
}
