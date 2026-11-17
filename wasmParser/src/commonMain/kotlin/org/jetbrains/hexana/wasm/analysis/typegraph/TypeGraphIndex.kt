package org.jetbrains.hexana.wasm.analysis.typegraph

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.opcodes.HeapType
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.OpCodeGCBreak
import org.jetbrains.hexana.wasm.opcodes.OpCodeGCSrcDst
import org.jetbrains.hexana.wasm.opcodes.OpCodeGCStruct
import org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmExactHeapType
import org.jetbrains.hexana.wasm.sections.WasmFieldType
import org.jetbrains.hexana.wasm.sections.WasmIndexHeapType
import org.jetbrains.hexana.wasm.sections.WasmNonNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmValueType

enum class TypeGraphNodeKind { STRUCT, ARRAY, FUNC }

/**
 * Reverse index of the WasmGC type hierarchy for a single module.
 *
 * All per-type arrays are sorted and deduplicated.
 * Type indices follow the same assignment as [org.jetbrains.hexana.wasm.analysis.dispatch.DispatchCandidatesBuilder]:
 * rec-group entries occupy consecutive indices; non-rec types each occupy one index.
 */
class TypeGraphIndex(
    val typeCount: Int,
    private val kinds: ByteArray,
    private val recGroupOf: IntArray,
    val recGroups: List<IntRange>,
    private val superTypesOf: Array<IntArray>,
    private val subtypesOf: Array<IntArray>,
    private val fieldRefsOf: Array<IntArray>,
    private val referencedByTypes: Array<IntArray>,
    private val referencedByFuncs: Array<IntArray>,
) {
    /** True when any STRUCT/ARRAY record exists, or any rec group, or any non-empty supertype list. */
    val hasGcTypes: Boolean by lazy {
        recGroups.isNotEmpty() ||
            (0 until typeCount).any { i -> kinds[i] != TypeGraphNodeKind.FUNC.ordinal.toByte() } ||
            (0 until typeCount).any { i -> superTypesOf[i].isNotEmpty() }
    }

    fun kind(typeIdx: Int): TypeGraphNodeKind = TypeGraphNodeKind.entries[kinds[typeIdx].toInt()]

    /** Returns the rec-group index of this type, or -1 if it belongs to no rec group. */
    fun recGroup(typeIdx: Int): Int = recGroupOf[typeIdx]

    fun superTypes(typeIdx: Int): IntArray = superTypesOf[typeIdx]
    fun directSubtypes(typeIdx: Int): IntArray = subtypesOf[typeIdx]

    /** Outgoing field-reference edges: struct fields, array element, func params+results. */
    fun fieldRefs(typeIdx: Int): IntArray = fieldRefsOf[typeIdx]

    /** Types that reference this type via fields (inverse of fieldRefs). */
    fun referencingTypes(typeIdx: Int): IntArray = referencedByTypes[typeIdx]

    /** Global function indices whose bodies reference this type via GC opcodes. */
    fun referencingFuncs(typeIdx: Int): IntArray = referencedByFuncs[typeIdx]
}

/**
 * Builds a [TypeGraphIndex] for one module in two passes: type-section pass and code pass.
 */
class TypeGraphIndexBuilder(
    private val buffer: CommonByteBuffer,
    private val module: WasmModule,
) {
    fun build(): TypeGraphIndex {
        val typeRecords = module.typesSection?.let { with(it) { buffer.parseTypes() } } ?: emptyList()

        // --- Type pass: assign type indices, collect kinds, rec groups, superTypes, fieldRefs ---

        // Flatten type records to (typeIdx, record) pairs, tracking rec groups.
        data class TypeEntry(val typeIdx: Int, val record: WasmTypeRecord, val recGroupIdx: Int)

        val entries = ArrayList<TypeEntry>(typeRecords.size * 2)
        val recGroups = ArrayList<IntRange>()
        var typeIdx = 0

        for (topRecord in typeRecords) {
            when (topRecord) {
                is WasmRecursiveTypeRecord -> {
                    if (topRecord.entries.size >= 2) {
                        val start = typeIdx
                        val groupIdx = recGroups.size
                        for (entry in topRecord.entries) {
                            entries.add(TypeEntry(typeIdx, entry, groupIdx))
                            typeIdx++
                        }
                        recGroups.add(start until typeIdx)
                    } else {
                        // Single-entry rec group: treat as non-rec
                        for (entry in topRecord.entries) {
                            entries.add(TypeEntry(typeIdx, entry, -1))
                            typeIdx++
                        }
                    }
                }
                else -> {
                    entries.add(TypeEntry(typeIdx, topRecord, -1))
                    typeIdx++
                }
            }
        }

        val totalTypes = typeIdx
        val kinds = ByteArray(totalTypes)
        val recGroupOf = IntArray(totalTypes) { -1 }
        val superTypesRaw = Array(totalTypes) { emptyList<UInt>() }
        // mutable sets for field refs
        val fieldRefSets = Array(totalTypes) { HashSet<Int>() }

        for (e in entries) {
            val idx = e.typeIdx
            if (e.recGroupIdx >= 0) recGroupOf[idx] = e.recGroupIdx
            when (val rec = e.record) {
                is WasmStructTypeRecord -> {
                    kinds[idx] = TypeGraphNodeKind.STRUCT.ordinal.toByte()
                    superTypesRaw[idx] = rec.superTypes
                    for (field in rec.fields) {
                        extractHeapTypeRef(field)?.let { ref ->
                            if (ref < totalTypes) fieldRefSets[idx].add(ref)
                        }
                    }
                }
                is WasmArrayTypeRecord -> {
                    kinds[idx] = TypeGraphNodeKind.ARRAY.ordinal.toByte()
                    superTypesRaw[idx] = rec.superTypes
                    extractHeapTypeRef(rec.fieldType)?.let { ref ->
                        if (ref < totalTypes) fieldRefSets[idx].add(ref)
                    }
                }
                is WasmSectionFunctionTypeRecord -> {
                    kinds[idx] = TypeGraphNodeKind.FUNC.ordinal.toByte()
                    superTypesRaw[idx] = rec.superTypes
                    for (param in rec.params) {
                        refFromValueType(param, totalTypes)?.let { fieldRefSets[idx].add(it) }
                    }
                    for (ret in rec.retTypes) {
                        refFromValueType(ret, totalTypes)?.let { fieldRefSets[idx].add(it) }
                    }
                }
                is WasmRecursiveTypeRecord -> {
                    // Nested rec groups not supported by spec; skip
                    kinds[idx] = TypeGraphNodeKind.FUNC.ordinal.toByte()
                }
            }
        }

        // Build superTypesOf (validated, bounds-guarded)
        val superTypesOf = Array(totalTypes) { i ->
            val raw = superTypesRaw[i]
            if (raw.isEmpty()) IntArray(0)
            else {
                val arr = raw.mapNotNull { u ->
                    val v = u.toInt()
                    if (v >= 0 && v < totalTypes) v else null
                }.toIntArray()
                arr.sort()
                arr
            }
        }

        // Build subtypesOf (invert superTypesOf)
        val subtypeMutableSets = Array(totalTypes) { HashSet<Int>() }
        for (i in 0 until totalTypes) {
            for (sup in superTypesOf[i]) {
                subtypeMutableSets[sup].add(i)
            }
        }
        val subtypesOf = Array(totalTypes) { i ->
            subtypeMutableSets[i].toIntArray().also { it.sort() }
        }

        // Freeze fieldRefs
        val fieldRefsOf = Array(totalTypes) { i ->
            fieldRefSets[i].toIntArray().also { it.sort() }
        }

        // Build referencedByTypes (invert fieldRefs)
        val refByTypeSets = Array(totalTypes) { HashSet<Int>() }
        for (i in 0 until totalTypes) {
            for (ref in fieldRefsOf[i]) {
                refByTypeSets[ref].add(i)
            }
        }
        val referencedByTypes = Array(totalTypes) { i ->
            refByTypeSets[i].toIntArray().also { it.sort() }
        }

        // --- Code pass: collect referencedByFuncs ---
        val refByFuncSets = Array(totalTypes) { HashSet<Int>() }

        val importFuncCount = run {
            val importRecords = module.importSection?.let { with(it) { buffer.getImportRecords() } } ?: emptyList()
            importRecords.count { it.type is WasmTypeIndexExternalType }
        }

        module.codeSection?.let { section ->
            val codeRecords = with(section) { buffer.scan() }
            for ((codeIdx, codeRecord) in codeRecords.withIndex()) {
                val funcIdx = importFuncCount + codeIdx
                val seen = HashSet<Int>()
                collectTypeRefsFromOpcodes(codeRecord.opCodes, seen, totalTypes)
                for (typeRef in seen) {
                    refByFuncSets[typeRef].add(funcIdx)
                }
            }
        }

        val referencedByFuncs = Array(totalTypes) { i ->
            refByFuncSets[i].toIntArray().also { it.sort() }
        }

        return TypeGraphIndex(
            typeCount = totalTypes,
            kinds = kinds,
            recGroupOf = recGroupOf,
            recGroups = recGroups,
            superTypesOf = superTypesOf,
            subtypesOf = subtypesOf,
            fieldRefsOf = fieldRefsOf,
            referencedByTypes = referencedByTypes,
            referencedByFuncs = referencedByFuncs,
        )
    }

    // --- Helpers ---

    /** Returns the type index referenced by a field's storage type, or null if not a ref to a type index. */
    private fun extractHeapTypeRef(field: WasmFieldType): Int? = refFromValueType(field.storageType, Int.MAX_VALUE)

    /** Returns the type index referenced by a value type (via WasmRefType wrapper), or null. */
    private fun refFromValueType(vt: WasmValueType<*>, totalTypes: Int): Int? {
        val refType = (vt as? WasmRefType)?.code ?: return null
        val ht = when (refType) {
            is WasmNonNullableReferenceType -> refType.heapType
            is WasmNullableReferenceType -> refType.heapType
            else -> null
        } ?: return null
        val v = when (ht) {
            is WasmIndexHeapType -> ht.type.toInt()
            is WasmExactHeapType -> ht.type.toInt()
            else -> null
        } ?: return null
        return if (v >= 0 && v < totalTypes) v else null
    }

    private fun heapTypeToTypeIdx(ht: HeapType, totalTypes: Int): Int? {
        // HeapType.type < 0 means abstract heap type; >= 0 is a type index
        val v = ht.type
        return if (v >= 0 && v < totalTypes) v else null
    }

    private fun collectTypeRefsFromOpcodes(opcodes: List<OpCode>, sink: HashSet<Int>, totalTypes: Int) {
        for (op in opcodes) {
            when (op) {
                // struct.new, struct.new_default: value = typeIndex
                is OpCodes.GC.StructNew -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.StructNewDefault -> addIfValid(op.value.toInt(), sink, totalTypes)

                // struct.get, struct.get_s, struct.get_u, struct.set: typeIndex field
                is OpCodeGCStruct -> addIfValid(op.typeIndex.toInt(), sink, totalTypes)

                // array.new, array.new_default, array.new_fixed, array.get, array.get_s, array.get_u,
                // array.set, array.fill: value = typeIndex (all are OpCodeWithValue<UInt>)
                is OpCodes.GC.ArrayNew -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.ArrayNewDefault -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.ArrayNewFixed -> addIfValid(op.value0.toInt(), sink, totalTypes)
                is OpCodes.GC.ArrayGet -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.ArrayGetSigned -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.ArrayGetUnsigned -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.ArraySet -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.GC.ArrayFill -> addIfValid(op.value.toInt(), sink, totalTypes)

                // array.new_data, array.copy, array.init_data, array.init_elem: both type indices
                is OpCodeGCSrcDst -> {
                    addIfValid(op.typeIndexDst.toInt(), sink, totalTypes)
                    addIfValid(op.typeIndexSrc.toInt(), sink, totalTypes)
                }

                // array.new_element: typeIndex = value
                is OpCodes.GC.ArrayNewElement -> addIfValid(op.value.toInt(), sink, totalTypes)

                // ref.test, ref.test null, ref.cast, ref.cast null: HeapType value
                is OpCodes.GC.RefTest -> heapTypeToTypeIdx(op.value, totalTypes)?.let { sink.add(it) }
                is OpCodes.GC.RefTestNullable -> heapTypeToTypeIdx(op.value, totalTypes)?.let { sink.add(it) }
                is OpCodes.GC.RefCast -> heapTypeToTypeIdx(op.value, totalTypes)?.let { sink.add(it) }
                is OpCodes.GC.RefCastNullable -> heapTypeToTypeIdx(op.value, totalTypes)?.let { sink.add(it) }

                // br_on_cast, br_on_cast_fail: srcType and dstType
                is OpCodeGCBreak -> {
                    heapTypeToTypeIdx(op.srcType, totalTypes)?.let { sink.add(it) }
                    heapTypeToTypeIdx(op.dstType, totalTypes)?.let { sink.add(it) }
                }

                // call_ref, return_call_ref: value = typeIndex
                is OpCodes.Flow.CallRef -> addIfValid(op.value.toInt(), sink, totalTypes)
                is OpCodes.Flow.ReturnCallRef -> addIfValid(op.value.toInt(), sink, totalTypes)

                // Recurse into nested bodies
                is OpCodes.Flow.Block -> collectTypeRefsFromOpcodes(op.value, sink, totalTypes)
                is OpCodes.Flow.Loop -> collectTypeRefsFromOpcodes(op.value, sink, totalTypes)
                is OpCodes.Flow.If -> collectTypeRefsFromOpcodes(op.value, sink, totalTypes)
                is OpCodes.Flow.Else -> collectTypeRefsFromOpcodes(op.value, sink, totalTypes)
                is OpCodes.Flow.TryTable -> collectTypeRefsFromOpcodes(op.code, sink, totalTypes)

                else -> {}
            }
        }
    }

    private fun addIfValid(typeIdx: Int, sink: HashSet<Int>, totalTypes: Int) {
        if (typeIdx >= 0 && typeIdx < totalTypes) sink.add(typeIdx)
    }
}
