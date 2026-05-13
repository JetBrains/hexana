package org.jetbrains.hexana.disassembly.core

data class NativeDisassemblyUnit(
    val functions: List<NativeFunction>,
    val confidence: NativeDisassemblyConfidence,
) {
    val instructions: List<NativeInstruction>
        get() = functions.flatMap { it.instructions }
}

data class NativeFunction(
    val name: String,
    val entry: NativeAddress,
    val instructions: List<NativeInstruction>,
    val basicBlocks: List<NativeBasicBlockCandidate>,
    val confidence: NativeDisassemblyConfidence,
)

data class NativeBasicBlockCandidate(
    val id: String,
    val start: NativeAddress,
    val endInclusive: NativeAddress,
    val instructions: List<NativeInstruction>,
    val outgoingXrefs: List<NativeXref>,
)

data class NativeInstruction(
    val address: NativeAddress,
    val sectionName: String?,
    val bytes: List<Int>,
    val mnemonic: String,
    val operandsText: String,
    val assemblyText: String,
    val symbol: String?,
    val sourceLocation: DisassemblySourceLocation?,
    val relocation: NativeRelocationAnnotation?,
    val xrefs: List<NativeXref>,
    val confidence: NativeDisassemblyConfidence,
)

data class NativeAddress(
    val virtualAddress: ULong,
    val fileOffset: Long?,
)

data class DisassemblySourceLocation(
    val filePath: String,
    val line: Int,
    val column: Int? = null,
)

data class NativeRelocationAnnotation(
    val type: String,
    val symbolName: String?,
)

data class NativeXref(
    val kind: NativeXrefKind,
    val target: NativeAddress?,
    val symbolName: String?,
)

enum class NativeXrefKind {
    Call,
    Jump,
}

enum class NativeDisassemblyConfidence {
    BackendProvided,
    Partial,
    Unknown,
}

object NativeBasicBlockCandidateBuilder {
    fun fromInstructions(instructions: List<NativeInstruction>): List<NativeBasicBlockCandidate> {
        if (instructions.isEmpty()) return emptyList()

        val blockStarts = linkedSetOf(instructions.first().address.virtualAddress)
        instructions.forEachIndexed { index, instruction ->
            instruction.xrefs
                .filter { it.kind == NativeXrefKind.Jump }
                .mapNotNull { it.target?.virtualAddress }
                .forEach { blockStarts.add(it) }

            if (instruction.isBlockTerminator()) {
                instructions.getOrNull(index + 1)?.let { blockStarts.add(it.address.virtualAddress) }
            }
        }

        val blocks = mutableListOf<MutableList<NativeInstruction>>()
        var currentBlock = mutableListOf<NativeInstruction>()

        for (instruction in instructions) {
            if (currentBlock.isNotEmpty() && instruction.address.virtualAddress in blockStarts) {
                blocks.add(currentBlock)
                currentBlock = mutableListOf()
            }
            currentBlock.add(instruction)
            if (instruction.isBlockTerminator()) {
                blocks.add(currentBlock)
                currentBlock = mutableListOf()
            }
        }
        if (currentBlock.isNotEmpty()) {
            blocks.add(currentBlock)
        }

        return blocks.mapIndexed { index, block ->
            NativeBasicBlockCandidate(
                id = "b$index",
                start = block.first().address,
                endInclusive = block.last().address,
                instructions = block,
                outgoingXrefs = block.last().xrefs.filter { it.kind == NativeXrefKind.Jump },
            )
        }
    }
}

private fun NativeInstruction.isBlockTerminator(): Boolean {
    val normalizedMnemonic = mnemonic.normalizedMnemonic()
    return xrefs.any { it.kind == NativeXrefKind.Jump } ||
            normalizedMnemonic in BLOCK_TERMINATOR_MNEMONICS ||
            isRiscVZeroRegisterJump(normalizedMnemonic) ||
            normalizedMnemonic.startsWith("b.")
}

private fun NativeInstruction.isRiscVZeroRegisterJump(normalizedMnemonic: String): Boolean =
    normalizedMnemonic in RISCV_LINK_JUMP_MNEMONICS && operandsText.hasRiscVZeroDestination()

private fun String.normalizedMnemonic(): String =
    lowercase().substringBefore('\t').substringBefore(' ')

private fun String.hasRiscVZeroDestination(): Boolean {
    val destination = substringBefore(',').trim()
    return destination == "zero" || destination == "x0"
}

private val BLOCK_TERMINATOR_MNEMONICS = setOf(
    "ja",
    "jae",
    "jb",
    "jbe",
    "jc",
    "jcxz",
    "je",
    "jecxz",
    "jg",
    "jge",
    "jl",
    "jle",
    "jmp",
    "jmpq",
    "jna",
    "jnae",
    "jnb",
    "jnbe",
    "jnc",
    "jne",
    "jng",
    "jnge",
    "jnl",
    "jnle",
    "jno",
    "jnp",
    "jns",
    "jnz",
    "jo",
    "jp",
    "jpe",
    "jpo",
    "jrcxz",
    "js",
    "jz",
    "j",
    "jr",
    "loop",
    "loope",
    "loopne",
    "loopnz",
    "loopz",
    "ret",
    "retq",
    "retn",
    "retl",
    "b",
    "bal",
    "bcc",
    "bcs",
    "beq",
    "bge",
    "bgt",
    "bhi",
    "bhs",
    "ble",
    "blo",
    "bls",
    "blt",
    "bltu",
    "bgeu",
    "bmi",
    "bne",
    "bpl",
    "br",
    "bx",
    "bxj",
    "bvc",
    "bvs",
    "cbnz",
    "cbz",
    "retab",
    "tbz",
    "tbnz",
)

private val RISCV_LINK_JUMP_MNEMONICS = setOf(
    "jal",
    "jalr",
)
