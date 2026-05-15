package org.jetbrains.hexana.disassembly.llvmobjdump

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.BasicBlockCandidateBuilder

object LlvmObjdumpNativeDisassemblyBuilder {
    /**
     * Promotes a parsed [LlvmObjdumpDisassembly] into the typed
     * [LlvmObjdumpDisassemblyResult] consumed by downstream code:
     *
     *  - rendered text → mnemonic, operandsText, assemblyText, raw bytes
     *  - control-flow → isBlockTerminator (mnemonic-based predicate),
     *    branchTargets (jump-only VA targets parsed out of operands), and
     *    callTargets (call-only VA targets), all populated from the same
     *    operand-text scan
     *  - per-function blocking → [BasicBlockCandidateBuilder] runs over the
     *    decoded instructions
     *
     * Sections / symbols / file offsets / relocations / source lines are not
     * applied here — see [LlvmObjdumpValidInstruction] KDoc for why.
     */
    fun fromLlvmObjdump(
        disassembly: LlvmObjdumpDisassembly,
        arch: Arch,
    ): LlvmObjdumpDisassemblyResult {
        val functions = disassembly.functions.map { function ->
            val instructions = function.instructions.map { it.decode(arch) }
            LlvmObjdumpDecodedFunction(
                name = function.name,
                entry = function.address,
                instructions = instructions,
                basicBlocks = BasicBlockCandidateBuilder.fromInstructions(instructions),
            )
        }
        return LlvmObjdumpDisassemblyResult(functions = functions)
    }
}

private fun LlvmObjdumpInstruction.decode(arch: Arch): LlvmObjdumpValidInstruction {
    val controlFlow = controlFlowKind()
    val target = extractDirectTarget()
    val branchTargets = if (controlFlow == ControlFlowKind.Jump && target != null) listOf(target) else emptyList()
    val callTargets = if (controlFlow == ControlFlowKind.Call && target != null) listOf(target) else emptyList()
    val isTerminator = controlFlow == ControlFlowKind.Jump ||
        mnemonic.normalizedMnemonic() in NON_JUMP_TERMINATOR_MNEMONICS

    return LlvmObjdumpValidInstruction(
        address = address,
        byteRange = 0..(bytes.size - 1).coerceAtLeast(-1),
        arch = arch,
        isBlockTerminator = isTerminator,
        branchTargets = branchTargets,
        mnemonic = mnemonic,
        operandsText = operands,
        assemblyText = assembly,
        bytes = bytes,
        callTargets = callTargets,
    )
}

private enum class ControlFlowKind { Call, Jump }

private fun LlvmObjdumpInstruction.controlFlowKind(): ControlFlowKind? {
    val normalized = mnemonic.normalizedMnemonic()
    return when {
        normalized == "jal" || normalized == "jalr" ->
            if (operands.hasRiscVZeroDestination()) ControlFlowKind.Jump else ControlFlowKind.Call
        normalized in CALL_MNEMONICS -> ControlFlowKind.Call
        normalized in JUMP_MNEMONICS || normalized.startsWith("b.") -> ControlFlowKind.Jump
        else -> null
    }
}

private fun LlvmObjdumpInstruction.extractDirectTarget(): ULong? {
    if (operands.isIndirectControlFlowOperand()) return null
    val match = TARGET_OPERAND.find(operands) ?: return null
    return match.groupValues[1].toULongOrNull(16)
}

private val TARGET_OPERAND = Regex("""(?:^|[\s,])\$?0x([0-9a-fA-F]+)(?:\s+<([^>]+)>)?""")

private fun String.normalizedMnemonic(): String =
    lowercase().substringBefore('\t').substringBefore(' ')

private fun String.hasRiscVZeroDestination(): Boolean {
    val destination = substringBefore(',').trim()
    return destination == "zero" || destination == "x0"
}

private fun String.isIndirectControlFlowOperand(): Boolean {
    val operand = substringBefore('<').trim()
    return operand.startsWith("*") ||
        '[' in operand ||
        ']' in operand ||
        '(' in operand ||
        ')' in operand
}

private val CALL_MNEMONICS = setOf(
    "call", "callq", "calll",
    "bl", "blx", "blr",
)

private val JUMP_MNEMONICS = setOf(
    "ja", "jae", "jb", "jbe", "jc", "jcxz",
    "je", "jecxz", "jg", "jge", "jl", "jle",
    "jmp", "jmpq", "jna", "jnae", "jnb", "jnbe",
    "jnc", "jne", "jng", "jnge", "jnl", "jnle",
    "jno", "jnp", "jns", "jnz", "jo", "jp",
    "jpe", "jpo", "jrcxz", "js", "jz", "j", "jr",
    "loop", "loope", "loopne", "loopnz", "loopz",
    "b", "bal", "bcc", "bcs", "beq", "bge", "bgt",
    "bhi", "bhs", "ble", "blo", "bls", "blt",
    "bltu", "bgeu", "bmi", "bne", "bpl", "br",
    "bx", "bxj", "bvc", "bvs", "cbnz", "cbz",
    "tbz", "tbnz",
)

/** Terminators that are NOT jumps (no branch target) — ret, ud2, hlt etc. */
private val NON_JUMP_TERMINATOR_MNEMONICS = setOf(
    "ret", "retq", "retn", "retl", "retab",
    "iret", "iretd", "iretq",
    "ud2", "hlt",
)
