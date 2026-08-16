package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.format.WatSymbolNames
import org.jetbrains.hexana.providers.format.watFor
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import org.jetbrains.hexana.wasm.sections.StandardTypesParser
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry

/** A symbolic operand-stack slot: wasm value type plus where the value came from. */
data class WasmSymbolicStackSlot(
    /** Wasm value type: "i32", "i64", "f32", "f64", "v128", "ref", or "?" in unreachable code. */
    val type: String,
    /** Human-readable origin, e.g. "local 1 (v)", "const 16", "i32.add @0x52", "call make @0x341f". */
    val provenance: String,
)

/** Result of [WasmStackShapeAnalyzer.stackAt]. */
sealed class WasmStackShapeResult {
    /** [slots] bottom-to-top, the state BEFORE the instruction at the queried offset executes. */
    class Ok(val slots: List<WasmSymbolicStackSlot>) : WasmStackShapeResult()

    /** The stack could not be reconstructed; [reason] is a short user-facing explanation. */
    class Unavailable(val reason: String) : WasmStackShapeResult()
}

/**
 * Statically reconstructs the wasm operand stack at an instruction offset by
 * abstractly interpreting the enclosing function from its entry — wasm
 * validation's type algorithm, augmented with per-slot provenance.
 *
 * This is the "symbolic operand-stack" for runtimes that cannot expose the
 * real one (Cranelift compiles the stack machine away): the SHAPE (depth and
 * types) is exact by wasm's validation guarantees; provenance names the
 * producing instruction, local, or constant. Only constants carry values.
 *
 * Instruction offsets: value-carrying opcodes record their byte offset while
 * zero-operand opcodes are shared singletons without one, so offsets for
 * singletons are deduced backwards (each plain singleton is one byte) from
 * the next offset-bearing instruction, anchored at the function's closing
 * `end`. Modules using SIMD/atomic/GC singletons in the queried function make
 * the reconstruction bail rather than risk misaligned offsets, as does any
 * unsupported opcode ([WasmStackShapeResult.Unavailable] with the reason).
 */
class WasmStackShapeAnalyzer private constructor(
    private val buffer: CommonByteBuffer,
    private val codeOffsets: List<CodeEntryOffset>,
    private val funcTypes: List<WasmSectionFunctionTypeRecord?>,
    private val definedFuncTypeIndices: List<Int>,
    private val importedFuncTypeIndices: List<Int>,
    private val globalTypes: List<WasmGlobalType>,
    private val funcNames: Map<Int, String>?,
) {

    companion object {
        /**
         * Indexes the module's sections; returns null when [wasmBuffer] is not
         * a parseable core module with code. The buffer is read via duplicates
         * only (the caller's cursor is untouched) and kept for lazy per-pause
         * function-body parsing, so a memory-mapped buffer stays zero-copy.
         */
        fun create(wasmBuffer: CommonByteBuffer): WasmStackShapeAnalyzer? {
            return try {
                val buf = wasmBuffer.duplicate()
                val result = WasmParser().parse(buf.duplicate()) as? WasmParseSuccess ?: return null
                val module = result.wasmModule as? WasmModule ?: return null
                val codeSection = module.codeSection ?: return null
                val offsets = with(codeSection) { buf.duplicate().scanOffsets() }

                val types = module.typesSection?.let { s -> with(s) { buf.duplicate().parseTypes() } }.orEmpty()
                    .flatMap { if (it is WasmRecursiveTypeRecord) it.entries else listOf(it) }
                    .map { it as? WasmSectionFunctionTypeRecord }

                val imports = module.importSection?.let { s -> with(s) { buf.duplicate().getImportRecords() } }.orEmpty()
                val importedFuncs = imports.mapNotNull { (it.type as? WasmTypeIndexExternalType)?.index?.toInt() }
                val importedGlobals = imports.mapNotNull { it.type as? WasmGlobalType }

                val defined = module.functionSection?.let { s -> with(s) { buf.duplicate().parseFunctions() } }.orEmpty()
                val definedGlobals = module.globalSection?.let { s -> with(s) { buf.duplicate().scan() } }.orEmpty().map { it.type }

                val names = with(module) {
                    getCustomSectionByName("name")?.let { section -> with(section) { buf.duplicate().parseAsNameSection() } }
                }?.functionSubsection?.namemap

                WasmStackShapeAnalyzer(
                    buffer = buf,
                    codeOffsets = offsets,
                    funcTypes = types,
                    definedFuncTypeIndices = defined,
                    importedFuncTypeIndices = importedFuncs,
                    globalTypes = importedGlobals + definedGlobals,
                    funcNames = names,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Reconstructs the operand stack right before the instruction at
     * [moduleAbsoluteOffset] executes. [localName] optionally decorates
     * local-sourced slots with a source-level name (e.g. from DWARF).
     */
    fun stackAt(moduleAbsoluteOffset: Int, localName: (Int) -> String? = { null }): WasmStackShapeResult {
        val entryIdx = findCodeEntry(moduleAbsoluteOffset)
            ?: return WasmStackShapeResult.Unavailable("offset is not inside a function body")
        val entry = codeOffsets[entryIdx]

        val record = try {
            buffer.duplicate().parseCodeEntry(entry.offset)
        } catch (_: Exception) {
            return WasmStackShapeResult.Unavailable("function body failed to parse")
        }

        val typeIdx = definedFuncTypeIndices.getOrNull(entryIdx)
            ?: return WasmStackShapeResult.Unavailable("function signature not found")
        val signature = funcTypes.getOrNull(typeIdx) ?: return WasmStackShapeResult.Unavailable("function type not found")
        val localTypes: List<ValKind> = signature.params.map { kindOf(it) } +
            record.locals.flatMap { decl -> List(decl.local) { kindOf(decl.type) } }

        val flat = mutableListOf<FlatInstr>()
        try {
            flatten(record.opCodes, flat)
        } catch (e: UnsupportedBody) {
            return WasmStackShapeResult.Unavailable(e.reason)
        }
        if (!deduceOffsets(flat, entry.offset + entry.size)) {
            return WasmStackShapeResult.Unavailable("instruction offsets could not be reconstructed")
        }

        return interpret(flat, moduleAbsoluteOffset, entry.offset + entry.size - 1, signature, localTypes, localName)
    }

    // ── Module indexing helpers ─────────────────────────────────────────

    private fun findCodeEntry(offset: Int): Int? {
        var lo = 0
        var hi = codeOffsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = codeOffsets[mid]
            when {
                offset < e.offset -> hi = mid - 1
                offset >= e.offset + e.size -> lo = mid + 1
                else -> return mid
            }
        }
        return null
    }

    private fun functionSignature(funcIdx: Int): WasmSectionFunctionTypeRecord? {
        val typeIdx = if (funcIdx < importedFuncTypeIndices.size) {
            importedFuncTypeIndices[funcIdx]
        } else {
            definedFuncTypeIndices.getOrNull(funcIdx - importedFuncTypeIndices.size) ?: return null
        }
        return funcTypes.getOrNull(typeIdx)
    }

    // ── Flattening + offset deduction ───────────────────────────────────

    private class UnsupportedBody(val reason: String) : Exception()

    /** One linearized instruction; block boundaries become explicit markers. */
    private class FlatInstr(
        val op: OpCode?,
        val marker: Marker,
        /** Byte length used for backward offset deduction; null when the real offset is carried by [op]. */
        val byteLen: Int?,
        var offset: Int = -1,
    ) {
        enum class Marker { PLAIN, BLOCK_START, ELSE, END }
    }

    private fun flatten(ops: List<OpCode>, out: MutableList<FlatInstr>) {
        for (op in ops) {
            when (op) {
                OpCodes.Flow.Terminal -> {
                    // The block/function-closing `end`; block ends are emitted as
                    // explicit END markers, so a stray Terminal is only valid at
                    // the very tail of a body where it is simply skipped.
                }
                is OpCodes.Flow.TryTable -> throw UnsupportedBody("try_table is not supported")
                is OpCodes.Flow.Else -> throw UnsupportedBody("malformed body: else outside if")
                is OpCodes.Flow.If -> {
                    out += FlatInstr(op, FlatInstr.Marker.BLOCK_START, byteLen = null)
                    val elseIdx = op.value.indexOfFirst { it is OpCodes.Flow.Else }
                    if (elseIdx >= 0) {
                        flatten(op.value.subList(0, elseIdx), out)
                        val elseOp = op.value[elseIdx] as OpCodes.Flow.Else
                        out += FlatInstr(elseOp, FlatInstr.Marker.ELSE, byteLen = 1)
                        flatten(elseOp.value, out)
                    } else {
                        flatten(op.value, out)
                    }
                    out += FlatInstr(op, FlatInstr.Marker.END, byteLen = 1)
                }
                is OpCodes.Flow.Block -> { // also Loop (subclass)
                    out += FlatInstr(op, FlatInstr.Marker.BLOCK_START, byteLen = null)
                    flatten(op.value, out)
                    out += FlatInstr(op, FlatInstr.Marker.END, byteLen = 1)
                }
                else -> out += FlatInstr(op, FlatInstr.Marker.PLAIN, byteLen = singletonByteLen(op))
            }
        }
    }

    /** Delegates to [WasmInstrOffsets.singletonByteLen]. */
    private fun singletonByteLen(op: OpCode): Int? = WasmInstrOffsets.singletonByteLen(op)

    /**
     * Backward pass assigning offsets to singletons; returns false when the result is
     * inconsistent. Bridges [FlatInstr] to [FlattenedInstr] for the shared algorithm.
     */
    private fun deduceOffsets(flat: List<FlatInstr>, entryEnd: Int): Boolean {
        val bridged = flat.map { f ->
            FlattenedInstr(
                isEndMarker = f.marker == FlatInstr.Marker.END,
                byteLen = f.byteLen,
                recordedOffset = (f.op as? OpCodeWithOffset)?.offset?.takeIf { it > 0 } ?: -1,
            )
        }
        if (!WasmInstrOffsets.deduceOffsets(bridged, entryEnd)) return false
        for (i in flat.indices) flat[i].offset = bridged[i].offset
        return true
    }

    // ── Abstract interpretation ─────────────────────────────────────────

    private enum class ValKind(val display: String) {
        I32("i32"), I64("i64"), F32("f32"), F64("f64"), V128("v128"), REF("ref"), UNKNOWN("?")
    }

    private fun kindOf(type: WasmValueType<*>?): ValKind = when (type) {
        WasmI32Type -> ValKind.I32
        WasmI64Type -> ValKind.I64
        WasmF32Type -> ValKind.F32
        WasmF64Type -> ValKind.F64
        WasmVecType -> ValKind.V128
        is WasmRefType -> ValKind.REF
        else -> ValKind.UNKNOWN
    }

    private class Slot(val kind: ValKind, val prov: String, var localRef: Int? = null, var globalRef: Int? = null)

    private class Frame(
        val startKinds: List<ValKind>,
        val endKinds: List<ValKind>,
        val height: Int,
        val label: String,
        var unreachable: Boolean = false,
    )

    private class Interp(val localTypes: List<ValKind>, val localName: (Int) -> String?) {
        val stack = mutableListOf<Slot>()
        val ctrl = mutableListOf<Frame>()

        fun push(slot: Slot) = stack.add(slot)

        fun pop(): Slot {
            val frame = ctrl.last()
            if (stack.size <= frame.height) {
                if (frame.unreachable) return Slot(ValKind.UNKNOWN, "?")
                throw UnsupportedBody("stack underflow (malformed body)")
            }
            return stack.removeAt(stack.size - 1)
        }

        fun invalidateLocal(index: Int) = stack.forEach { if (it.localRef == index) it.localRef = null }
        fun invalidateGlobals() = stack.forEach { it.globalRef = null }
        fun invalidateGlobal(index: Int) = stack.forEach { if (it.globalRef == index) it.globalRef = null }

        fun localSlot(index: Int): Slot {
            val name = localName(index)
            val prov = if (name != null) "local $index ($name)" else "local $index"
            return Slot(localTypes.getOrElse(index) { ValKind.UNKNOWN }, prov, localRef = index)
        }
    }

    private fun blockSignature(type: StandardTypesParser.WasmBlockType?): Pair<List<ValKind>, List<ValKind>> = when (type) {
        null, StandardTypesParser.WasmBlockType.Empty -> emptyList<ValKind>() to emptyList()
        // NOTE: the parser currently decodes the 0x40 "empty" block type as a
        // WasmRefType (see ControlInstructionTest); real single-ref-result
        // blocks are rare enough that treating ref-typed blocks as empty is
        // the lesser error until the parser is fixed.
        is WasmRefType -> emptyList<ValKind>() to emptyList()
        is WasmValueType<*> -> emptyList<ValKind>() to listOf(kindOf(type))
        is StandardTypesParser.WasmBlockType.S33 -> {
            val sig = funcTypes.getOrNull(type.value.toInt())
            if (sig == null) emptyList<ValKind>() to emptyList()
            else sig.params.map { kindOf(it) } to sig.retTypes.map { kindOf(it) }
        }
        else -> emptyList<ValKind>() to emptyList()
    }

    private fun interpret(
        flat: List<FlatInstr>,
        target: Int,
        closingEnd: Int,
        signature: WasmSectionFunctionTypeRecord,
        localTypes: List<ValKind>,
        localName: (Int) -> String?,
    ): WasmStackShapeResult {
        val interp = Interp(localTypes, localName)
        interp.ctrl.add(Frame(emptyList(), signature.retTypes.map { kindOf(it) }, 0, "function"))

        fun snapshot(): WasmStackShapeResult =
            if (interp.ctrl.any { it.unreachable }) {
                WasmStackShapeResult.Unavailable("pause offset is in statically unreachable code")
            } else {
                WasmStackShapeResult.Ok(interp.stack.map { WasmSymbolicStackSlot(it.kind.display, it.prov) })
            }

        try {
            for (f in flat) {
                if (f.offset == target) return snapshot()
                step(f, interp)
            }
        } catch (e: UnsupportedBody) {
            return WasmStackShapeResult.Unavailable(e.reason)
        }
        // The function's closing `end` byte is a valid pause target (the
        // return value(s) are on the stack); it is not part of the flat list.
        if (target == closingEnd) return snapshot()
        return WasmStackShapeResult.Unavailable("offset does not match a decoded instruction")
    }

    private fun step(f: FlatInstr, interp: Interp) {
        val at = if (f.offset >= 0) "@0x${f.offset.toString(16)}" else ""
        when (f.marker) {
            FlatInstr.Marker.BLOCK_START -> {
                val block = f.op as OpCodes.Flow.Block
                val (params, results) = blockSignature(block.type)
                val label = when (block) {
                    is OpCodes.Flow.If -> "if"
                    is OpCodes.Flow.Loop -> "loop"
                    else -> "block"
                }
                if (block is OpCodes.Flow.If) interp.pop() // condition
                val popped = ArrayDeque<Slot>()
                repeat(params.size) { popped.addFirst(interp.pop()) }
                interp.ctrl.add(Frame(params, results, interp.stack.size, "$label $at"))
                popped.forEach { interp.push(it) } // block params flow through, provenance intact
            }
            FlatInstr.Marker.ELSE -> {
                val frame = interp.ctrl.last()
                while (interp.stack.size > frame.height) interp.stack.removeAt(interp.stack.size - 1)
                frame.startKinds.forEach { interp.push(Slot(it, "param of ${frame.label}")) }
                frame.unreachable = false
            }
            FlatInstr.Marker.END -> {
                val frame = interp.ctrl.removeAt(interp.ctrl.size - 1)
                if (frame.unreachable) {
                    while (interp.stack.size > frame.height) interp.stack.removeAt(interp.stack.size - 1)
                    frame.endKinds.forEach { interp.push(Slot(it, "result of ${frame.label}")) }
                } else {
                    // The top |endKinds| slots ARE the results; they survive with provenance intact.
                    val results = ArrayDeque<Slot>()
                    repeat(frame.endKinds.size) { results.addFirst(interp.pop()) }
                    while (interp.stack.size > frame.height) interp.stack.removeAt(interp.stack.size - 1)
                    results.forEach { interp.push(it) }
                }
            }
            FlatInstr.Marker.PLAIN -> stepPlain(f.op!!, at, interp)
        }
    }

    private fun stepPlain(op: OpCode, at: String, interp: Interp) {
        when (op) {
            is OpCodes.Local.Get -> interp.push(interp.localSlot(op.value.toInt()))
            is OpCodes.Local.Set -> {
                interp.pop()
                interp.invalidateLocal(op.value.toInt())
            }
            is OpCodes.Local.Tee -> {
                val idx = op.value.toInt()
                val v = interp.pop()
                interp.invalidateLocal(idx)
                v.localRef = idx // the local now holds this very value
                interp.push(v)
            }
            is OpCodes.Global.Get -> {
                val idx = op.value.toInt()
                val kind = kindOf(globalTypes.getOrNull(idx)?.valueType)
                interp.push(Slot(kind, "global $idx", globalRef = idx))
            }
            is OpCodes.Global.Set -> {
                interp.pop()
                interp.invalidateGlobal(op.value.toInt())
            }
            is I32.Const -> interp.push(Slot(ValKind.I32, "const ${op.value}"))
            is I64.Const -> interp.push(Slot(ValKind.I64, "const ${op.value}"))
            is F32.Const -> interp.push(Slot(ValKind.F32, "const ${op.value}"))
            is F64.Const -> interp.push(Slot(ValKind.F64, "const ${op.value}"))
            is OpCodes.Flow.Call -> applyCall(op.value.toInt(), at, interp, tail = false)
            is OpCodes.Flow.ReturnCall -> applyCall(op.value.toInt(), at, interp, tail = true)
            is OpCodes.Flow.CallIndirect -> {
                interp.pop() // table element index
                applySignature(funcTypes.getOrNull(op.value0), "call_indirect $at", at, interp, tail = false)
            }
            is OpCodes.Flow.ReturnCallIndirect -> {
                interp.pop()
                applySignature(funcTypes.getOrNull(op.value0.toInt()), "return_call_indirect $at", at, interp, tail = true)
            }
            is OpCodes.Flow.Br -> interp.ctrl.last().unreachable = true
            is OpCodes.Flow.BrIf -> interp.pop()
            is OpCodes.Flow.BrTable -> {
                interp.pop()
                interp.ctrl.last().unreachable = true
            }
            OpCodes.Flow.Return -> interp.ctrl.last().unreachable = true
            OpCodes.ParametricInstructions.Unreachable -> interp.ctrl.last().unreachable = true
            OpCodes.ParametricInstructions.Nop -> {}
            OpCodes.ParametricInstructions.Drop -> interp.pop()
            OpCodes.ParametricInstructions.Select -> {
                interp.pop() // condition
                val b = interp.pop()
                val a = interp.pop()
                val kind = if (a.kind != ValKind.UNKNOWN) a.kind else b.kind
                interp.push(Slot(kind, "select $at"))
            }
            is OpCodes.ParametricInstructions.SelectWithType -> {
                interp.pop()
                interp.pop()
                interp.pop()
                interp.push(Slot(kindOf(op.value.firstOrNull()), "select $at"))
            }
            else -> stepByMnemonic(op, at, interp)
        }
    }

    private fun applyCall(funcIdx: Int, at: String, interp: Interp, tail: Boolean) {
        val name = funcNames?.get(funcIdx) ?: "func $funcIdx"
        applySignature(functionSignature(funcIdx), "call $name $at", at, interp, tail)
    }

    private fun applySignature(
        sig: WasmSectionFunctionTypeRecord?,
        prov: String,
        at: String,
        interp: Interp,
        tail: Boolean,
    ) {
        if (sig == null) throw UnsupportedBody("callee type not found $at")
        repeat(sig.params.size) { interp.pop() }
        // Any call may write globals; global-sourced slots no longer mirror them.
        interp.invalidateGlobals()
        if (tail) {
            interp.ctrl.last().unreachable = true
        } else {
            sig.retTypes.forEach { interp.push(Slot(kindOf(it), prov)) }
        }
    }

    // ── Mnemonic-driven effects for the numeric/memory singleton families ──

    private val trivialSymbols = WatSymbolNames { null }

    private fun stepPlainKind(prefix: String): ValKind = when (prefix) {
        "i32" -> ValKind.I32
        "i64" -> ValKind.I64
        "f32" -> ValKind.F32
        "f64" -> ValKind.F64
        else -> ValKind.UNKNOWN
    }

    private fun stepByMnemonic(op: OpCode, at: String, interp: Interp) {
        val mnemonic = watFor(op, trivialSymbols).substringBefore(' ')
        val prefix = mnemonic.substringBefore('.')
        val name = mnemonic.substringAfter('.', missingDelimiterValue = "")
        val kind = stepPlainKind(prefix)

        fun result(k: ValKind) = interp.push(Slot(k, "$mnemonic $at"))

        when {
            kind != ValKind.UNKNOWN && name in Mnemonics.BINARY_OPS -> {
                interp.pop(); interp.pop(); result(kind)
            }
            kind != ValKind.UNKNOWN && name in Mnemonics.COMPARE_OPS -> {
                interp.pop(); interp.pop(); result(ValKind.I32)
            }
            kind != ValKind.UNKNOWN && name == "eqz" -> {
                interp.pop(); result(ValKind.I32)
            }
            kind != ValKind.UNKNOWN && name in Mnemonics.UNARY_OPS -> {
                interp.pop(); result(kind)
            }
            kind != ValKind.UNKNOWN && Mnemonics.CONVERSION_OPS.any { name.startsWith(it) } -> {
                interp.pop(); result(kind)
            }
            kind != ValKind.UNKNOWN && name.startsWith("load") -> {
                interp.pop(); result(kind)
            }
            kind != ValKind.UNKNOWN && name.startsWith("store") -> {
                interp.pop(); interp.pop()
            }
            mnemonic == "memory.size" -> result(ValKind.I32)
            mnemonic == "memory.grow" -> {
                interp.pop(); result(ValKind.I32)
            }
            mnemonic == "memory.copy" || mnemonic == "memory.fill" || mnemonic == "memory.init" -> {
                interp.pop(); interp.pop(); interp.pop()
            }
            mnemonic == "data.drop" || mnemonic == "elem.drop" -> {}
            mnemonic == "ref.null" || mnemonic == "ref.func" -> result(ValKind.REF)
            mnemonic == "ref.is_null" -> {
                interp.pop(); result(ValKind.I32)
            }
            else -> throw UnsupportedBody("unsupported instruction '$mnemonic' $at")
        }
    }

    private object Mnemonics {
        val BINARY_OPS = setOf(
            "add", "sub", "mul", "div", "div_s", "div_u", "rem_s", "rem_u",
            "and", "or", "xor", "shl", "shr_s", "shr_u", "rotl", "rotr",
            "min", "max", "copysign",
        )
        val COMPARE_OPS = setOf(
            "eq", "ne", "lt", "lt_s", "lt_u", "gt", "gt_s", "gt_u",
            "le", "le_s", "le_u", "ge", "ge_s", "ge_u",
        )
        val UNARY_OPS = setOf(
            "clz", "ctz", "popcnt", "abs", "neg", "ceil", "floor", "nearest", "sqrt",
            "extend8_s", "extend16_s", "extend32_s",
        )
        // Prefix match: the destination type is the mnemonic's own prefix, one operand popped.
        val CONVERSION_OPS = setOf(
            "wrap_", "extend_", "trunc", "convert_", "demote_", "promote_", "reinterpret_",
        )
    }
}
