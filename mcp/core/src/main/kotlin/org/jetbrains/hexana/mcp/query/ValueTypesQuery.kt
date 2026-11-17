package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.types.ListValueTypesResult
import org.jetbrains.hexana.mcp.model.types.ReferenceValueTypeCountResult
import org.jetbrains.hexana.mcp.model.types.ReferenceValueTypeResult
import org.jetbrains.hexana.mcp.model.types.ValueTypeCategoryCountsResult
import org.jetbrains.hexana.mcp.model.types.ValueTypeCountResult
import org.jetbrains.hexana.mcp.model.types.ValueTypeCountsResult
import org.jetbrains.hexana.mcp.model.types.ValueTypeSummaryResult
import org.jetbrains.hexana.mcp.model.types.ValueTypesResult
import org.jetbrains.hexana.mcp.toHeapTypeName
import org.jetbrains.hexana.mcp.toNullableSignature
import org.jetbrains.hexana.mcp.toSignature
import org.jetbrains.hexana.mcp.types
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode
import org.jetbrains.hexana.wasm.sections.StandardTypesParser
import org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmFieldType
import org.jetbrains.hexana.wasm.sections.WasmFixedI64Limits
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmLimits
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNonNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmOpenI64Limits
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI16
import org.jetbrains.hexana.wasm.sections.WasmPactTypeI8
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmReferenceHeapType
import org.jetbrains.hexana.wasm.sections.WasmReferenceType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType

fun WasmBinaryProvider.readValueTypes(): ListValueTypesResult {
    val wasmModule = requireNotNull(wasmModule)
    val collector = ValueTypeCollector()

    bytes.types(wasmModule).forEach { collector.recordType(it) }
    imports.forEach { import ->
        when (val externalType = import.type) {
            is WasmGlobalType -> collector.recordValueType(externalType.valueType)
            is WasmTableExternalType -> collector.recordReferenceType(externalType.referenceType)
            is WasmMemType -> collector.recordLimits(externalType.limits)
            else -> Unit
        }
    }
    wasmModule.globalSection?.let { section ->
        with(section) {
            bytes.scan().forEach { global ->
                collector.recordValueType(global.type.valueType)
                collector.recordOpcodes(global.expression)
            }
        }
    }
    wasmModule.tableSection?.let { section ->
        with(section) {
            bytes.scanTable().forEach { table ->
                collector.recordReferenceType(table.tableType.referenceType)
                collector.recordOpcodes(table.expression.orEmpty())
            }
        }
    }
    wasmModule.memorySection?.let { section ->
        with(section) {
            bytes.scan().forEach { memory -> collector.recordLimits(memory.limits) }
        }
    }
    codeRecords.forEach { code ->
        code.locals.orEmpty().forEach { local ->
            collector.recordValueType(local.type, count = local.local)
        }
        collector.recordOpcodes(code.opCodes)
    }

    return collector.toResult()
}

private class ValueTypeCollector {
    private val counts = mutableMapOf<ValueTypeKey, Int>()
    private var hasI64Limits = false

    fun recordType(type: WasmTypeRecord) {
        when (type) {
            is WasmArrayTypeRecord -> recordFieldType(type.fieldType)
            is WasmStructTypeRecord -> type.fields.forEach(::recordFieldType)
            is WasmRecursiveTypeRecord -> type.entries.forEach(::recordType)
            is WasmSectionFunctionTypeRecord -> {
                type.params.forEach(::recordValueType)
                type.retTypes.forEach(::recordValueType)
            }
        }
    }

    fun recordValueType(valueType: WasmValueType<*>, count: Int = 1) {
        if (count <= 0) return
        counts.merge(valueType.toValueTypeKey(), count, Int::plus)
    }

    fun recordReferenceType(referenceType: WasmReferenceType, count: Int = 1) {
        if (count <= 0) return
        counts.merge(referenceType.toValueTypeKey(), count, Int::plus)
    }

    fun recordLimits(limits: WasmLimits<*>) {
        if (limits is WasmOpenI64Limits || limits is WasmFixedI64Limits) {
            hasI64Limits = true
        }
    }

    fun recordOpcodes(opcodes: List<OpCode>) {
        opcodes.forEach(::recordOpcode)
    }

    fun toResult(): ListValueTypesResult {
        val scalarCounts = countsFor(Category.SCALAR, SCALAR_ORDER)
        val vectorCounts = countsFor(Category.VECTOR, VECTOR_ORDER)
        val packedCounts = countsFor(Category.PACKED, PACKED_ORDER)
        val referenceCounts = referenceCounts()
        val hasPacked = packedCounts.isNotEmpty()
        val hasSIMD = vectorCounts.isNotEmpty()
        val hasReferenceTypes = referenceCounts.isNotEmpty()
        val hasGC = hasPacked || referenceCounts.any { it.refKind !in MVP_REFERENCE_KINDS }

        return ListValueTypesResult(
            valueTypes = ValueTypesResult(
                scalar = scalarCounts.map { it.type },
                vector = vectorCounts.map { it.type },
                packed = packedCounts.map { it.type },
                reference = referenceCounts.map {
                    ReferenceValueTypeResult(
                        type = it.type,
                        refKind = it.refKind,
                        nullable = it.nullable
                    )
                }
            ),
            counts = ValueTypeCountsResult(
                valueTypes = ValueTypeCategoryCountsResult(
                    scalar = scalarCounts,
                    vector = vectorCounts,
                    packed = packedCounts,
                    reference = referenceCounts
                )
            ),
            summary = ValueTypeSummaryResult(
                hasGC = hasGC,
                hasPacked = hasPacked,
                hasSIMD = hasSIMD,
                hasReferenceTypes = hasReferenceTypes,
                pureMVP = !hasPacked && !hasSIMD && !hasReferenceTypes && !hasGC && !hasI64Limits
            )
        )
    }

    private fun recordFieldType(fieldType: WasmFieldType) {
        recordValueType(fieldType.storageType)
    }

    private fun recordOpcode(opcode: OpCode) {
        when (opcode) {
            is VectorOpCode -> recordValueType(WasmVecType)
            is OpCodes.ParametricInstructions.SelectWithType -> opcode.value.forEach(::recordValueType)
            is OpCodes.Flow.TryTable -> {
                recordBlockType(opcode.blockType)
                recordOpcodes(opcode.code)
            }
            is OpCodes.Flow.Block -> {
                recordBlockType(opcode.type)
                recordOpcodes(opcode.value)
            }
            is OpCodes.Ref.Null -> counts.merge(opcode.value.toNullableValueTypeKey(), 1, Int::plus)
        }
    }

    private fun recordBlockType(blockType: StandardTypesParser.WasmBlockType?) {
        when (blockType) {
            is WasmValueType<*> -> recordValueType(blockType)
            is StandardTypesParser.WasmBlockType.Empty,
            is StandardTypesParser.WasmBlockType.S33,
            null -> Unit
            else -> Unit
        }
    }

    private fun countsFor(category: Category, order: List<String>): List<ValueTypeCountResult> {
        return counts.entries
            .filter { it.key.category == category }
            .sortedWith(compareBy({ order.indexOfOrMax(it.key.type) }, { it.key.type }))
            .map { ValueTypeCountResult(type = it.key.type, count = it.value) }
    }

    private fun referenceCounts(): List<ReferenceValueTypeCountResult> {
        return counts.entries
            .mapNotNull { (key, count) ->
                if (key.category != Category.REFERENCE) {
                    null
                } else {
                    ReferenceValueTypeCountResult(
                        type = key.type,
                        refKind = requireNotNull(key.refKind),
                        nullable = requireNotNull(key.nullable),
                        count = count
                    )
                }
            }
            .sortedWith(
                compareBy<ReferenceValueTypeCountResult>(
                    { REFERENCE_ORDER.indexOfOrMax(it.refKind) },
                    { it.refKind },
                    { it.nullable },
                    { it.type }
                )
            )
    }
}

private fun WasmValueType<*>.toValueTypeKey(): ValueTypeKey {
    return when (this) {
        is WasmI32Type -> ValueTypeKey(Category.SCALAR, "i32")
        is WasmI64Type -> ValueTypeKey(Category.SCALAR, "i64")
        is WasmF32Type -> ValueTypeKey(Category.SCALAR, "f32")
        is WasmF64Type -> ValueTypeKey(Category.SCALAR, "f64")
        is WasmVecType -> ValueTypeKey(Category.VECTOR, "v128")
        is WasmPactTypeI8 -> ValueTypeKey(Category.PACKED, "i8")
        is WasmPactTypeI16 -> ValueTypeKey(Category.PACKED, "i16")
        is WasmRefType -> code.toValueTypeKey()
    }
}

private fun WasmReferenceType.toValueTypeKey(): ValueTypeKey {
    val nullable = when (this) {
        is WasmNonNullableReferenceType -> false
        is WasmNullableReferenceType,
        is WasmReferenceHeapType -> true
    }
    return ValueTypeKey(
        category = Category.REFERENCE,
        type = toSignature(),
        refKind = heapType.toHeapTypeName(),
        nullable = nullable
    )
}

private fun WasmHeapType.toNullableValueTypeKey(): ValueTypeKey {
    return ValueTypeKey(
        category = Category.REFERENCE,
        type = toNullableSignature(),
        refKind = toHeapTypeName(),
        nullable = true
    )
}

private fun List<String>.indexOfOrMax(value: String): Int {
    val index = indexOf(value)
    return if (index == -1) Int.MAX_VALUE else index
}

private data class ValueTypeKey(
    val category: Category,
    val type: String,
    val refKind: String? = null,
    val nullable: Boolean? = null
)

private enum class Category {
    SCALAR,
    VECTOR,
    PACKED,
    REFERENCE
}

private val SCALAR_ORDER = listOf("i32", "i64", "f32", "f64")
private val VECTOR_ORDER = listOf("v128")
private val PACKED_ORDER = listOf("i8", "i16")
private val REFERENCE_ORDER = listOf(
    "func",
    "extern",
    "any",
    "eq",
    "struct",
    "array",
    "i31",
    "none",
    "nofunc",
    "noextern",
    "noexn"
)
private val MVP_REFERENCE_KINDS = setOf("func", "extern")
