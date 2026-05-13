package org.jetbrains.hexana.disassembly.llvmobjdump

data class LlvmObjdumpDisassembly(
    val functions: List<LlvmObjdumpFunction>,
) {
    val instructions: List<LlvmObjdumpInstruction>
        get() = functions.flatMap { it.instructions }
}

data class LlvmObjdumpFunction(
    val name: String,
    val address: ULong,
    val sectionName: String?,
    val instructions: List<LlvmObjdumpInstruction>,
)

data class LlvmObjdumpInstruction(
    val address: ULong,
    val sectionName: String?,
    val bytes: List<Int>,
    val assembly: String,
    val mnemonic: String,
    val operands: String,
    val functionName: String?,
    val rawLine: String,
)

object LlvmObjdumpDisassemblyParser {
    fun parse(text: String): LlvmObjdumpDisassembly {
        val functions = mutableListOf<MutableFunction>()
        var currentFunction: MutableFunction? = null
        var currentSectionName: String? = null

        for (line in text.lineSequence()) {
            val sectionMatch = SECTION_HEADER.matchEntire(line)
            if (sectionMatch != null) {
                currentSectionName = sectionMatch.groupValues[1].takeIf { it.isNotBlank() }
                currentFunction = null
                continue
            }

            val functionMatch = FUNCTION_HEADER.matchEntire(line)
            if (functionMatch != null) {
                val symbolName = functionMatch.groupValues[2]
                if (currentFunction == null || !symbolName.isLocalLabel()) {
                    currentFunction = MutableFunction(
                        name = symbolName,
                        address = functionMatch.groupValues[1].toULong(16),
                        sectionName = currentSectionName,
                    )
                    functions.add(currentFunction)
                }
                continue
            }

            val instructionMatch = INSTRUCTION_LINE.matchEntire(line) ?: continue
            val instructionAddress = instructionMatch.groupValues[1].toULongOrNull(16) ?: continue
            val parsedBody = parseInstructionBody(instructionMatch.groupValues[2])
            if (parsedBody.assembly.isEmpty()) continue

            if (currentFunction == null) {
                currentFunction = MutableFunction(
                    name = UNKNOWN_FUNCTION_NAME,
                    address = instructionAddress,
                    sectionName = currentSectionName,
                )
                functions.add(currentFunction)
            }

            val instruction = LlvmObjdumpInstruction(
                address = instructionAddress,
                sectionName = currentFunction?.sectionName ?: currentSectionName,
                bytes = parsedBody.bytes,
                assembly = parsedBody.assembly,
                mnemonic = parsedBody.mnemonic,
                operands = parsedBody.operands,
                functionName = currentFunction?.name,
                rawLine = line,
            )

            currentFunction.instructions.add(instruction)
        }

        return LlvmObjdumpDisassembly(
            functions = functions.map { it.toImmutable() },
        )
    }
}

private data class MutableFunction(
    val name: String,
    val address: ULong,
    val sectionName: String?,
    val instructions: MutableList<LlvmObjdumpInstruction> = mutableListOf(),
) {
    fun toImmutable(): LlvmObjdumpFunction = LlvmObjdumpFunction(name, address, sectionName, instructions.toList())
}

private data class ParsedInstructionBody(
    val bytes: List<Int>,
    val assembly: String,
    val mnemonic: String,
    val operands: String,
)

private val SECTION_HEADER = Regex("""^\s*Disassembly of section ([^:]+):\s*$""")
private val FUNCTION_HEADER = Regex("""^\s*([0-9a-fA-F]+)\s+<([^>]+)>:\s*$""")
private val INSTRUCTION_LINE = Regex("""^\s*([0-9a-fA-F]+):\s*(.*)$""")
private val WHITESPACE = Regex("""\s+""")

private fun parseInstructionBody(body: String): ParsedInstructionBody {
    val tabSeparated = body.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
    if (tabSeparated.size >= 2 && tabSeparated[0].isByteColumn()) {
        return parsedInstruction(
            bytes = tabSeparated[0].parseByteColumn(),
            assembly = tabSeparated.drop(1).joinToString(" ").trim(),
        )
    }

    val tokens = body.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    val byteTokens = tokens.takeWhile { it.isInstructionBytesToken() }
    val assembly = if (byteTokens.isNotEmpty()) {
        tokens.drop(byteTokens.size).joinToString(" ")
    } else {
        body.trim()
    }

    return parsedInstruction(
        bytes = byteTokens.flatMap { it.parseInstructionBytesToken() },
        assembly = assembly,
    )
}

private fun parsedInstruction(bytes: List<Int>, assembly: String): ParsedInstructionBody {
    val trimmedAssembly = assembly.trim()
    val mnemonic = trimmedAssembly.substringBefore(' ', missingDelimiterValue = trimmedAssembly)
    val operands = trimmedAssembly.removePrefix(mnemonic).trim()
    return ParsedInstructionBody(
        bytes = bytes,
        assembly = trimmedAssembly,
        mnemonic = mnemonic,
        operands = operands,
    )
}

private fun String.isByteColumn(): Boolean =
    split(WHITESPACE).filter { it.isNotEmpty() }.all { it.isInstructionBytesToken() }

private fun String.parseByteColumn(): List<Int> =
    split(WHITESPACE).filter { it.isNotEmpty() }.flatMap { it.parseInstructionBytesToken() }

private fun String.isInstructionBytesToken(): Boolean =
    length in 2..16 &&
            length % 2 == 0 &&
            all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun String.parseInstructionBytesToken(): List<Int> =
    chunked(2)
        .let { chunks ->
            if (length == FIXED_WIDTH_INSTRUCTION_WORD_HEX_LENGTH) {
                chunks.reversed()
            } else {
                chunks
            }
        }
        .map { it.toInt(16) }

private fun String.isLocalLabel(): Boolean =
    isNotEmpty() &&
            (startsWith(".") ||
                    startsWith("$") ||
                    startsWith("?L") ||
                    startsWith("Ltmp") ||
                    startsWith("LBB"))

private const val FIXED_WIDTH_INSTRUCTION_WORD_HEX_LENGTH = 8
private const val UNKNOWN_FUNCTION_NAME = "<unknown>"
