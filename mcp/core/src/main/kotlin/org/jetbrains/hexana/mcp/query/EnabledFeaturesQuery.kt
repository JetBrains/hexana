package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.features.EnabledFeatureSourcesResult
import org.jetbrains.hexana.mcp.model.features.EnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.types
import org.jetbrains.hexana.mcp.watString
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.AtomicOpCode
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.StandardTypesParser
import org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActive
import org.jetbrains.hexana.wasm.sections.WasmDataRecordActiveWithMemoryIndex
import org.jetbrains.hexana.wasm.sections.WasmFixedI64Limits
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmOpenI64Limits
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmReferenceType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType

fun WasmBinaryProvider.readEnabledFeatures(): ListEnabledFeaturesResult {
    val wasmModule = requireNotNull(wasmModule)
    val detector = EnabledFeatureDetector()

    wasmModule.customSections
        .map { it.rawName }
        .filter { it == "producers" || it == "target_features" }
        .forEach(detector::recordCustomSection)

    bytes.types(wasmModule).forEach { detector.recordType(it) }
    imports.forEach { import ->
        when (val externalType = import.type) {
            is WasmGlobalType -> detector.recordValueType(externalType.valueType, "Import")
            is WasmTableExternalType -> detector.recordReferenceType(externalType.referenceType, "Import")
            is WasmMemType -> detector.recordMemoryLimit(externalType.limits, "Import")
            else -> Unit
        }
    }

    wasmModule.globalSection?.let { section ->
        with(section) {
            bytes.scan().forEach { global ->
                detector.recordValueType(global.type.valueType, "Global")
                detector.recordConstExpression("Global", global.expression)
            }
        }
    }
    wasmModule.tableSection?.let { section ->
        with(section) {
            bytes.scanTable().forEach { table ->
                detector.recordReferenceType(table.tableType.referenceType, "Table")
                detector.recordConstExpression("Table", table.expression.orEmpty())
            }
        }
    }
    wasmModule.memorySection?.let { section ->
        with(section) {
            bytes.scan().forEach { memory -> detector.recordMemoryLimit(memory.limits, "Memory") }
        }
    }
    wasmModule.dataCountSection?.let { detector.recordBulkMemory("DataCount") }
    wasmModule.dataSection?.let { section ->
        with(section) {
            bytes.scan().forEach { data ->
                when (data) {
                    is WasmDataRecordActive -> detector.recordConstExpression("Data", data.expression)
                    is WasmDataRecordActiveWithMemoryIndex -> detector.recordConstExpression("Data", data.expression)
                    else -> Unit
                }
            }
        }
    }
    wasmModule.elementSection?.let { section ->
        detector.recordSection("Element")
        with(section) {
            bytes.scanElements().forEach { segment ->
                when (segment) {
                    is FuncIdxWasmElementSegment.ActiveDefault ->
                        detector.recordConstExpression("Element", segment.offsetExpr)
                    is FuncIdxWasmElementSegment.ActiveExplicit ->
                        detector.recordConstExpression("Element", segment.offsetExpr)
                    is ExprWasmElementSegment.ActiveDefault -> {
                        detector.recordConstExpression("Element", segment.offsetExpr)
                        segment.exprs.forEach { detector.recordConstExpression("Element", it) }
                    }
                    is ExprWasmElementSegment.ActiveExplicit -> {
                        detector.recordReferenceType(segment.refType, "Element")
                        detector.recordConstExpression("Element", segment.offsetExpr)
                        segment.exprs.forEach { detector.recordConstExpression("Element", it) }
                    }
                    is ExprWasmElementSegment.Passive -> {
                        detector.recordReferenceType(segment.refType, "Element")
                        segment.exprs.forEach { detector.recordConstExpression("Element", it) }
                    }
                    is ExprWasmElementSegment.Declare -> {
                        detector.recordReferenceType(segment.refType, "Element")
                        segment.exprs.forEach { detector.recordConstExpression("Element", it) }
                    }
                    else -> Unit
                }
            }
        }
    }

    codeRecords.forEach { code ->
        code.locals.orEmpty().forEach { detector.recordValueType(it.type, "Code") }
        detector.recordOpcodes(code.opCodes)
    }

    return detector.toResult()
}

private class EnabledFeatureDetector {
    private var referenceTypes = false
    private var bulkMemory = false
    private var multiValue = false
    private var signExtension = false
    private var simd = false
    private var threads = false
    private var memory64 = false
    private var gcTypes = false
    private var subtyping = false
    private var functionReferences = false
    private var extendedConst = false

    private val sections = linkedSetOf<String>()
    private val instructions = linkedSetOf<String>()
    private val customSections = linkedSetOf<String>()

    fun recordType(type: WasmTypeRecord) {
        recordSection("Type")
        when (type) {
            is WasmArrayTypeRecord -> {
                recordGcTypes("Type")
                recordValueType(type.fieldType.storageType, "Type")
            }
            is WasmStructTypeRecord -> {
                recordGcTypes("Type")
                if (type.superTypes.isNotEmpty()) {
                    subtyping = true
                    recordSection("Type")
                }
                type.fields.forEach { recordValueType(it.storageType, "Type") }
            }
            is WasmRecursiveTypeRecord -> {
                recordGcTypes("Type")
                type.entries.forEach(::recordType)
            }
            is WasmSectionFunctionTypeRecord -> {
                if (type.retTypes.size > 1) {
                    multiValue = true
                    recordSection("Type")
                }
                type.params.forEach { recordValueType(it, "Type") }
                type.retTypes.forEach { recordValueType(it, "Type") }
            }
        }
    }

    fun recordValueType(type: WasmValueType<*>, section: String) {
        when (type) {
            is WasmRefType -> recordReferenceType(type.code, section)
            is WasmVecType -> {
                simd = true
                recordSection(section)
            }
            is WasmPactTypeI8,
            is WasmPactTypeI16 -> recordGcTypes(section)
            else -> Unit
        }
    }

    fun recordReferenceType(type: WasmReferenceType, section: String) {
        referenceTypes = true
        recordSection(section)
        val name = type.heapType::class.simpleName.orEmpty()
        if (name !in setOf("WasmFuncHeapType", "WasmExternRefHeapType")) {
            recordGcTypes(section)
        }
    }

    fun recordMemoryLimit(limits: org.jetbrains.hexana.wasm.sections.WasmLimits<*>, section: String) {
        if (limits is WasmOpenI64Limits || limits is WasmFixedI64Limits) {
            memory64 = true
            recordSection(section)
        }
    }

    fun recordBulkMemory(section: String) {
        bulkMemory = true
        recordSection(section)
    }

    fun recordCustomSection(name: String) {
        customSections += name
    }

    fun recordSection(name: String) {
        sections += name
    }

    fun recordOpcodes(opcodes: List<OpCode>) {
        opcodes.forEach(::recordOpcode)
    }

    fun recordConstExpression(section: String, opcodes: List<OpCode>) {
        val extendedInstructions = opcodes.filterNot(::isMvpConstExpressionOpcode)
        if (extendedInstructions.isNotEmpty()) {
            extendedConst = true
            recordSection(section)
            extendedInstructions.forEach { recordInstruction(it) }
        }
        recordOpcodes(opcodes)
    }

    fun toResult(): ListEnabledFeaturesResult {
        return ListEnabledFeaturesResult(
            features = EnabledFeaturesResult(
                referenceTypes = referenceTypes,
                bulkMemory = bulkMemory,
                multiValue = multiValue,
                signExtension = signExtension,
                simd = simd,
                threads = threads,
                memory64 = memory64,
                gcTypes = gcTypes,
                subtyping = subtyping,
                functionReferences = functionReferences,
                extendedConst = extendedConst
            ),
            sources = EnabledFeatureSourcesResult(
                sections = sections.toList(),
                instructions = instructions.toList(),
                customSections = customSections.toList()
            )
        )
    }

    private fun recordOpcode(opcode: OpCode) {
        when (opcode) {
            is VectorOpCode -> {
                simd = true
                recordInstruction(opcode)
            }
            is AtomicOpCode -> {
                threads = true
                recordInstruction(opcode)
            }
            is OpCodes.Memory.Init,
            is OpCodes.Memory.Copy,
            is OpCodes.Memory.Fill,
            is OpCodes.Memory.Data.Drop,
            is Table.Init,
            is Table.Copy,
            is Table.Fill,
            is Element.Drop -> {
                bulkMemory = true
                recordInstruction(opcode)
            }
            I32.Extend8S,
            I32.Extend16S,
            I64.Extend8S,
            I64.Extend16S,
            I64.Extend32S -> {
                signExtension = true
                recordInstruction(opcode)
            }
            is OpCodes.Ref.Func,
            is OpCodes.Flow.CallRef,
            is OpCodes.Flow.ReturnCallRef -> {
                functionReferences = true
                referenceTypes = true
                recordInstruction(opcode)
            }
            is OpCodes.Ref.Null,
            OpCodes.Ref.IsNull,
            OpCodes.Ref.Eq,
            OpCodes.Ref.AsNonNull,
            is OpCodes.Flow.BrOnNull,
            is OpCodes.Flow.BrOnNonNull -> {
                referenceTypes = true
                recordInstruction(opcode)
            }
            is OpCodes.Flow.TryTable -> {
                recordBlockType(opcode.blockType)
                recordOpcodes(opcode.code)
            }
            is OpCodes.Flow.Block -> {
                recordBlockType(opcode.type)
                recordOpcodes(opcode.value)
            }
        }
        if (opcode::class.qualifiedName.orEmpty().contains(".OpCodes.GC.")) {
            recordGcTypes("Code")
            recordInstruction(opcode)
        }
    }

    private fun recordBlockType(blockType: StandardTypesParser.WasmBlockType?) {
        if (blockType is WasmValueType<*>) {
            recordValueType(blockType, "Code")
        }
    }

    private fun recordGcTypes(section: String) {
        gcTypes = true
        recordSection(section)
    }

    private fun recordInstruction(opcode: OpCode) {
        instructions += opcode.instructionName()
    }
}

private fun OpCode.instructionName(): String {
    return watString()
        .toString()
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        .orEmpty()
        .trimEnd('{')
        .ifBlank { this::class.simpleName.orEmpty() }
}

private fun isMvpConstExpressionOpcode(opcode: OpCode): Boolean {
    return when (opcode) {
        is I32.Const,
        is I64.Const,
        is F32.Const,
        is F64.Const,
        is OpCodes.Global.Get,
        is OpCodes.Ref.Null,
        is OpCodes.Ref.Func,
        OpCodes.Flow.Terminal -> true
        else -> false
    }
}
