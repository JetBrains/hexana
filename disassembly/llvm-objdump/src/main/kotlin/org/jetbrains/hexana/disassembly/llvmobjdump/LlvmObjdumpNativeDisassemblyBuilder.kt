package org.jetbrains.hexana.disassembly.llvmobjdump

import org.jetbrains.hexana.disassembly.core.DisassemblySourceLocation
import org.jetbrains.hexana.disassembly.core.NativeAddress
import org.jetbrains.hexana.disassembly.core.NativeBasicBlockCandidateBuilder
import org.jetbrains.hexana.disassembly.core.NativeDisassemblyConfidence
import org.jetbrains.hexana.disassembly.core.NativeDisassemblyUnit
import org.jetbrains.hexana.disassembly.core.NativeFunction
import org.jetbrains.hexana.disassembly.core.NativeInstruction
import org.jetbrains.hexana.disassembly.core.NativeRelocationAnnotation
import org.jetbrains.hexana.disassembly.core.NativeXref
import org.jetbrains.hexana.disassembly.core.NativeXrefKind

object LlvmObjdumpNativeDisassemblyBuilder {
    fun fromLlvmObjdump(
        disassembly: LlvmObjdumpDisassembly,
        mapVirtualAddressToFileOffset: (ULong) -> Long? = { null },
        mapSectionAddressToFileOffset: (address: ULong, sectionName: String?) -> Long? = { address, _ ->
            mapVirtualAddressToFileOffset(address)
        },
        findSectionName: (ULong) -> String? = { null },
        findRelocation: (instructionAddress: ULong, instructionSize: Int, sectionName: String?) -> NativeRelocationAnnotation? = { _, _, _ ->
            null
        },
        findSourceLocation: (ULong) -> DisassemblySourceLocation? = { null },
    ): NativeDisassemblyUnit {
        val functions = disassembly.functions.map { function ->
            val instructions = function.instructions.map { instruction ->
                val sectionName = instruction.sectionName ?: findSectionName(instruction.address)
                val relocation = findRelocation(instruction.address, instruction.bytes.size, sectionName)
                NativeInstruction(
                    address = instruction.address.toNativeAddress { address ->
                        mapSectionAddressToFileOffset(address, sectionName)
                    },
                    sectionName = sectionName,
                    bytes = instruction.bytes,
                    mnemonic = instruction.mnemonic,
                    operandsText = instruction.operands,
                    assemblyText = instruction.assembly,
                    symbol = instruction.functionName,
                    sourceLocation = findSourceLocation(instruction.address),
                    relocation = relocation,
                    xrefs = instruction.extractXrefs(relocation) { targetAddress ->
                        val targetSectionName = findSectionName(targetAddress) ?: sectionName
                        mapSectionAddressToFileOffset(targetAddress, targetSectionName)
                    },
                    confidence = NativeDisassemblyConfidence.BackendProvided,
                )
            }
            val entrySectionName = function.sectionName ?: instructions.firstOrNull()?.sectionName
            NativeFunction(
                name = function.name,
                entry = function.address.toNativeAddress { address ->
                    mapSectionAddressToFileOffset(address, entrySectionName)
                },
                instructions = instructions,
                basicBlocks = NativeBasicBlockCandidateBuilder.fromInstructions(instructions),
                confidence = NativeDisassemblyConfidence.BackendProvided,
            )
        }

        return NativeDisassemblyUnit(
            functions = functions,
            confidence = if (functions.isEmpty()) {
                NativeDisassemblyConfidence.Unknown
            } else {
                NativeDisassemblyConfidence.BackendProvided
            },
        )
    }
}

private fun ULong.toNativeAddress(mapVirtualAddressToFileOffset: (ULong) -> Long?): NativeAddress =
    NativeAddress(
        virtualAddress = this,
        fileOffset = mapVirtualAddressToFileOffset(this),
    )

private val TARGET_OPERAND = Regex("""(?:^|[\s,])\$?0x([0-9a-fA-F]+)(?:\s+<([^>]+)>)?""")

private data class ParsedControlFlowTarget(
    val target: NativeAddress?,
    val symbolName: String?,
)

private fun LlvmObjdumpInstruction.extractXrefs(
    relocation: NativeRelocationAnnotation?,
    mapVirtualAddressToFileOffset: (ULong) -> Long?,
): List<NativeXref> {
    val kind = controlFlowKind() ?: return emptyList()
    val parsedTarget = extractDirectTarget(mapVirtualAddressToFileOffset)

    if (relocation != null) {
        return listOf(
            NativeXref(
                kind = kind,
                target = if (parsedTarget != null && !parsedTarget.isRelocationPlaceholderFor(functionName)) {
                    parsedTarget.target
                } else {
                    null
                },
                symbolName = relocation.symbolName ?: parsedTarget?.symbolName,
            )
        )
    }

    return parsedTarget?.let {
        listOf(
            NativeXref(
                kind = kind,
                target = it.target,
                symbolName = it.symbolName,
            )
        )
    } ?: emptyList()
}

private fun LlvmObjdumpInstruction.controlFlowKind(): NativeXrefKind? {
    val normalizedMnemonic = mnemonic.lowercase()
    return when {
        normalizedMnemonic == "jal" || normalizedMnemonic == "jalr" -> {
            if (operands.hasRiscVZeroDestination()) NativeXrefKind.Jump else NativeXrefKind.Call
        }
        normalizedMnemonic in CALL_MNEMONICS -> NativeXrefKind.Call
        normalizedMnemonic in JUMP_MNEMONICS || normalizedMnemonic.startsWith("b.") -> NativeXrefKind.Jump
        else -> null
    }
}

private fun LlvmObjdumpInstruction.extractDirectTarget(
    mapVirtualAddressToFileOffset: (ULong) -> Long?,
): ParsedControlFlowTarget? {
    if (operands.isIndirectControlFlowOperand()) return null

    val match = TARGET_OPERAND.find(operands) ?: return null
    val targetAddress = match.groupValues[1].toULongOrNull(16)
        ?.toNativeAddress(mapVirtualAddressToFileOffset)
    val symbolName = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }

    return ParsedControlFlowTarget(
        target = targetAddress,
        symbolName = symbolName,
    )
}

private fun ParsedControlFlowTarget.isRelocationPlaceholderFor(functionName: String?): Boolean =
    symbolName == null || (functionName != null && symbolName.startsWith("$functionName+"))

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
    "call",
    "callq",
    "calll",
    "bl",
    "blx",
    "blr",
)

private val JUMP_MNEMONICS = setOf(
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
    "tbz",
    "tbnz",
)
