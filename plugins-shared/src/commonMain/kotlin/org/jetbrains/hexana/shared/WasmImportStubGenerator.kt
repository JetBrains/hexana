package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.*

/**
 * Generates minimal binary WebAssembly stub modules that export symbols
 * matching the imports of a given Wasm module. Each stub module is named
 * after the import module name so that runtimes can resolve imports by
 * module name.
 *
 * Function stubs are no-ops that return default zero values.
 * Memory stubs export a single-page memory.
 * Table stubs export a minimal funcref table.
 * Global stubs export a zero-valued global of the correct type.
 * Tag stubs export a tag with the matching type signature.
 */
object WasmImportStubGenerator {

    data class ImportModuleStub(val moduleName: String, val wasmBytes: ByteArray)

    /**
     * Checks whether the given Wasm binary has any imports at all.
     */
    fun hasImports(wasmBuffer: CommonByteBuffer): Boolean {
        val provider = WasmBinaryProvider(wasmBuffer.duplicate())
        if (!provider.canProvideWasmModule()) return false
        return provider.imports.isNotEmpty()
    }

    /**
     * Checks whether the given Wasm binary has any non-function imports
     * (memory, table, global, or tag). These require stub modules for
     * wasmtime's `--preload` since `-W unknown-imports-default` only
     * handles function imports.
     */
    fun hasNonFunctionImports(wasmBuffer: CommonByteBuffer): Boolean {
        val provider = WasmBinaryProvider(wasmBuffer.duplicate())
        if (!provider.canProvideWasmModule()) return false
        return provider.imports.any { it.type !is WasmTypeIndexExternalType }
    }

    /**
     * Generates stub modules for import modules that contain at least one
     * non-function import (memory, table, global, or tag).
     *
     * Each generated stub includes **all** imports from qualifying modules
     * (both function and non-function). This is necessary because
     * wasmtime's `--preload` makes the preloaded module the sole provider
     * for its namespace — if the stub only exported the non-function
     * symbols, `-W unknown-imports-default` would try to re-define the
     * same names as function traps, causing a "defined twice" linker error.
     */
    fun generateNonFunctionStubs(wasmBuffer: CommonByteBuffer): List<ImportModuleStub> {
        val provider = WasmBinaryProvider(wasmBuffer.duplicate())
        if (!provider.canProvideWasmModule()) return emptyList()

        val imports = provider.imports
        val nonFuncImports = imports.filter { it.type !is WasmTypeIndexExternalType }
        if (nonFuncImports.isEmpty()) return emptyList()

        val types = provider.wasmModule?.typesSection?.let { typeSection ->
            with(typeSection) {
                wasmBuffer.duplicate().parseTypes()
            }
        } ?: emptyList()

        // Collect the module names that have at least one non-function import.
        val modulesWithNonFuncImports = nonFuncImports.map { it.prefix }.toSet()

        // Generate full stubs (all import types) for those modules so that
        // the preloaded module completely owns the namespace.
        val byModule = imports.filter { it.prefix in modulesWithNonFuncImports }.groupBy { it.prefix }

        return byModule.map { (moduleName, moduleImports) ->
            ImportModuleStub(moduleName, buildStubModule(moduleImports, types))
        }
    }

    /**
     * Parses the given Wasm binary for imports and generates one stub module
     * per distinct import module name.
     */
    fun generateStubs(wasmBuffer: CommonByteBuffer): List<ImportModuleStub> {
        val provider = WasmBinaryProvider(wasmBuffer.duplicate())
        if (!provider.canProvideWasmModule()) return emptyList()

        val imports = provider.imports
        if (imports.isEmpty()) return emptyList()

        val types = provider.wasmModule?.typesSection?.let { typeSection ->
            with(typeSection) {
                wasmBuffer.duplicate().parseTypes()
            }
        } ?: emptyList()

        val byModule = imports.groupBy { it.prefix }

        return byModule.map { (moduleName, moduleImports) ->
            ImportModuleStub(moduleName, buildStubModule(moduleImports, types))
        }
    }

    /**
     * Returns the distinct import module names from a core Wasm binary.
     * Useful for determining which modules need to be preloaded.
     */
    fun importModuleNames(wasmBuffer: CommonByteBuffer): List<String> {
        val provider = WasmBinaryProvider(wasmBuffer.duplicate())
        if (!provider.canProvideWasmModule()) return emptyList()
        return provider.imports.map { it.prefix }.distinct()
    }

    private fun buildStubModule(
        imports: List<WasmImportRecord>,
        types: List<WasmTypeRecord>,
    ): ByteArray {
        // Deduplicate imports by name, keeping the first occurrence of each
        // export name. Wasm modules can legally import the same module::name
        // pair multiple times, but the generated stub must not export the
        // same name twice — runtimes (notably wasmtime) reject that.
        val deduplicatedImports = imports.distinctBy { it.name }

        val buf = WasmBinaryWriter()

        val funcTypes = mutableListOf<WasmSectionFunctionTypeRecord>()
        val funcTypeIndexMap = mutableMapOf<Int, Int>()

        for (imp in deduplicatedImports) {
            when (val t = imp.type) {
                is WasmTypeIndexExternalType -> {
                    val origIndex = t.index.toInt()
                    if (origIndex !in funcTypeIndexMap) {
                        val typeRecord = types.getOrNull(origIndex)
                        val funcType = typeRecord as? WasmSectionFunctionTypeRecord
                            ?: WasmSectionFunctionTypeRecord(0, 0, emptyList(), emptyList())
                        funcTypeIndexMap[origIndex] = funcTypes.size
                        funcTypes.add(funcType)
                    }
                }
                is WasmTagType -> {
                    val origIndex = t.index.index.toInt()
                    if (origIndex !in funcTypeIndexMap) {
                        val typeRecord = types.getOrNull(origIndex)
                        val funcType = typeRecord as? WasmSectionFunctionTypeRecord
                            ?: WasmSectionFunctionTypeRecord(0, 0, emptyList(), emptyList())
                        funcTypeIndexMap[origIndex] = funcTypes.size
                        funcTypes.add(funcType)
                    }
                }
                else -> {}
            }
        }

        val funcImports = mutableListOf<Pair<WasmImportRecord, Int>>()
        val memImports = mutableListOf<WasmImportRecord>()
        val tableImports = mutableListOf<WasmImportRecord>()
        val globalImports = mutableListOf<Pair<WasmImportRecord, WasmGlobalType>>()
        val tagImports = mutableListOf<Pair<WasmImportRecord, Int>>()

        for (imp in deduplicatedImports) {
            when (val t = imp.type) {
                is WasmTypeIndexExternalType -> {
                    funcImports.add(imp to funcTypeIndexMap[t.index.toInt()]!!)
                }
                is WasmMemType -> memImports.add(imp)
                is WasmTableExternalType -> tableImports.add(imp)
                is WasmGlobalType -> globalImports.add(imp to t)
                is WasmTagType -> {
                    tagImports.add(imp to funcTypeIndexMap[t.index.index.toInt()]!!)
                }
            }
        }

        // Wasm magic + version
        buf.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D)) // \0asm
        buf.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00)) // version 1

        // Section 1: Type section
        if (funcTypes.isNotEmpty()) {
            val typeSection = WasmBinaryWriter()
            typeSection.writeULEB128(funcTypes.size)
            for (ft in funcTypes) {
                typeSection.writeByte(0x60) // func type
                typeSection.writeULEB128(ft.params.size)
                for (p in ft.params) {
                    typeSection.writeByte(valueTypeByte(p))
                }
                typeSection.writeULEB128(ft.retTypes.size)
                for (r in ft.retTypes) {
                    typeSection.writeByte(valueTypeByte(r))
                }
            }
            buf.writeSection(1, typeSection.toByteArray())
        }

        // Section 3: Function section (function index -> type index)
        if (funcImports.isNotEmpty()) {
            val funcSection = WasmBinaryWriter()
            funcSection.writeULEB128(funcImports.size)
            for ((_, typeIdx) in funcImports) {
                funcSection.writeULEB128(typeIdx)
            }
            buf.writeSection(3, funcSection.toByteArray())
        }

        // Section 4: Table section
        if (tableImports.isNotEmpty()) {
            val tableSection = WasmBinaryWriter()
            tableSection.writeULEB128(tableImports.size)
            for (imp in tableImports) {
                val t = imp.type as WasmTableExternalType
                tableSection.writeByte(refTypeByte(t.referenceType))
                writeLimits(tableSection, t.limits)
            }
            buf.writeSection(4, tableSection.toByteArray())
        }

        // Section 5: Memory section
        if (memImports.isNotEmpty()) {
            val memSection = WasmBinaryWriter()
            memSection.writeULEB128(memImports.size)
            for (imp in memImports) {
                val m = imp.type as WasmMemType
                writeLimits(memSection, m.limits)
            }
            buf.writeSection(5, memSection.toByteArray())
        }

        // Section 6: Global section
        if (globalImports.isNotEmpty()) {
            val globalSection = WasmBinaryWriter()
            globalSection.writeULEB128(globalImports.size)
            for ((_, gt) in globalImports) {
                globalSection.writeByte(valueTypeByte(gt.valueType))
                globalSection.writeByte(if (gt.mutable) 0x01 else 0x00)
                writeDefaultInitExpr(globalSection, gt.valueType)
            }
            buf.writeSection(6, globalSection.toByteArray())
        }

        // Section 13: Tag section
        if (tagImports.isNotEmpty()) {
            val tagSection = WasmBinaryWriter()
            tagSection.writeULEB128(tagImports.size)
            for ((_, typeIdx) in tagImports) {
                tagSection.writeByte(0x00) // exception kind
                tagSection.writeULEB128(typeIdx)
            }
            buf.writeSection(13, tagSection.toByteArray())
        }

        // Section 7: Export section
        val exportSection = WasmBinaryWriter()
        val totalExports = funcImports.size + memImports.size + tableImports.size + globalImports.size + tagImports.size
        exportSection.writeULEB128(totalExports)

        var funcIdx = 0
        var memIdx = 0
        var tableIdx = 0
        var globalIdx = 0
        var tagIdx = 0

        for (imp in deduplicatedImports) {
            when (imp.type) {
                is WasmTypeIndexExternalType -> {
                    exportSection.writeName(imp.name)
                    exportSection.writeByte(0x00) // func export
                    exportSection.writeULEB128(funcIdx++)
                }
                is WasmMemType -> {
                    exportSection.writeName(imp.name)
                    exportSection.writeByte(0x02) // memory export
                    exportSection.writeULEB128(memIdx++)
                }
                is WasmTableExternalType -> {
                    exportSection.writeName(imp.name)
                    exportSection.writeByte(0x01) // table export
                    exportSection.writeULEB128(tableIdx++)
                }
                is WasmGlobalType -> {
                    exportSection.writeName(imp.name)
                    exportSection.writeByte(0x03) // global export
                    exportSection.writeULEB128(globalIdx++)
                }
                is WasmTagType -> {
                    exportSection.writeName(imp.name)
                    exportSection.writeByte(0x04) // tag export
                    exportSection.writeULEB128(tagIdx++)
                }
            }
        }
        buf.writeSection(7, exportSection.toByteArray())

        // Section 10: Code section (function bodies)
        if (funcImports.isNotEmpty()) {
            val codeSection = WasmBinaryWriter()
            codeSection.writeULEB128(funcImports.size)
            for ((_, typeIdx) in funcImports) {
                val ft = funcTypes[typeIdx]
                val body = WasmBinaryWriter()
                body.writeULEB128(0) // no locals
                for (retType in ft.retTypes) {
                    writeDefaultValue(body, retType)
                }
                body.writeByte(0x0B) // end

                codeSection.writeULEB128(body.size())
                codeSection.writeBytes(body.toByteArray())
            }
            buf.writeSection(10, codeSection.toByteArray())
        }

        return buf.toByteArray()
    }

    private fun valueTypeByte(type: WasmValueType<*>): Int = when (type) {
        is WasmI32Type -> 0x7F
        is WasmI64Type -> 0x7E
        is WasmF32Type -> 0x7D
        is WasmF64Type -> 0x7C
        is WasmVecType -> 0x7B
        is WasmRefType -> refTypeByte(type.code)
        else -> 0x7F // default to i32
    }

    private fun refTypeByte(refType: WasmReferenceType): Int = when (refType) {
        is WasmNullableReferenceType -> when (refType.heapType) {
            is WasmFuncHeapType -> 0x70
            is WasmExternRefHeapType -> 0x6F
            else -> 0x70
        }
        is WasmNonNullableReferenceType -> when (refType.heapType) {
            is WasmFuncHeapType -> 0x70
            is WasmExternRefHeapType -> 0x6F
            else -> 0x70
        }
        else -> 0x70
    }

    private fun writeLimits(writer: WasmBinaryWriter, limits: WasmLimits<*>) {
        when (limits) {
            is WasmOpenI32Limits -> {
                writer.writeByte(0x00)
                writer.writeULEB128(limits.min)
            }
            is WasmFixedI32Limits -> {
                writer.writeByte(if (limits.shared) 0x03 else 0x01)
                writer.writeULEB128(limits.min)
                writer.writeULEB128(limits.max ?: limits.min)
            }
            is WasmOpenI64Limits -> {
                writer.writeByte(0x04)
                writer.writeULEB128(limits.min.toInt())
            }
            is WasmFixedI64Limits -> {
                writer.writeByte(0x05)
                writer.writeULEB128(limits.min.toInt())
                writer.writeULEB128((limits.max ?: limits.min).toInt())
            }
        }
    }

    private fun writeDefaultInitExpr(writer: WasmBinaryWriter, type: WasmValueType<*>) {
        writeDefaultValue(writer, type)
        writer.writeByte(0x0B) // end
    }

    private fun writeDefaultValue(writer: WasmBinaryWriter, type: WasmValueType<*>) {
        when (type) {
            is WasmI32Type -> {
                writer.writeByte(0x41) // i32.const
                writer.writeULEB128(0)
            }
            is WasmI64Type -> {
                writer.writeByte(0x42) // i64.const
                writer.writeULEB128(0)
            }
            is WasmF32Type -> {
                writer.writeByte(0x43) // f32.const
                writer.writeBytes(byteArrayOf(0, 0, 0, 0))
            }
            is WasmF64Type -> {
                writer.writeByte(0x44) // f64.const
                writer.writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
            }
            is WasmRefType -> {
                writer.writeByte(0xD0) // ref.null
                writer.writeByte(refTypeByte(type.code))
            }
            else -> {
                writer.writeByte(0x41) // i32.const as fallback
                writer.writeULEB128(0)
            }
        }
    }
}
