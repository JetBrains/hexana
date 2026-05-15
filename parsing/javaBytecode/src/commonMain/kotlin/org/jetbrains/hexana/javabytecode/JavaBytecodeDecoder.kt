package org.jetbrains.hexana.javabytecode

/**
 * Decodes a raw `code[]` byte sequence (JVMS §4.7.3) into a list of
 * [JavaBytecodeInstruction]s. Variable-length opcodes (wide, tableswitch,
 * lookupswitch) are handled inline; unknown opcode bytes throw rather than
 * being silently skipped so callers know decoding has fallen off the rails.
 */
object JavaBytecodeDecoder {
    fun decode(code: ByteArray): List<JavaBytecodeInstruction> {
        val out = ArrayList<JavaBytecodeInstruction>()
        var pos = 0
        while (pos < code.size) {
            val start = pos
            val byte = code[pos].toInt() and 0xFF
            val op = JavaOpcode.forCode(byte) ?: error(
                "unknown JVM opcode 0x${byte.toString(16).padStart(2, '0')} at bytecode offset $start",
            )
            pos++
            val (operands, advance) = decodeOperands(op, code, pos, start)
            pos += advance
            out.add(JavaBytecodeInstruction(start, op, pos - start, operands))
        }
        return out
    }

    private fun decodeOperands(
        op: JavaOpcode,
        code: ByteArray,
        afterOpcodePos: Int,
        instructionStart: Int,
    ): Pair<JavaBytecodeOperands, Int> = when (op.operandKind) {
        JavaOperandKind.None -> JavaBytecodeOperands.None to 0
        JavaOperandKind.ByteSigned -> JavaBytecodeOperands.Byte1(code[afterOpcodePos].toInt()) to 1
        JavaOperandKind.ByteUnsigned -> JavaBytecodeOperands.Byte1(code[afterOpcodePos].toInt() and 0xFF) to 1
        JavaOperandKind.ShortSigned -> JavaBytecodeOperands.Short1(readI2(code, afterOpcodePos)) to 2
        JavaOperandKind.ShortUnsigned -> JavaBytecodeOperands.Short1(readU2(code, afterOpcodePos)) to 2
        JavaOperandKind.IntSigned -> JavaBytecodeOperands.Wide1(readI4(code, afterOpcodePos)) to 4
        JavaOperandKind.IInc -> {
            val varIndex = code[afterOpcodePos].toInt() and 0xFF
            val delta = code[afterOpcodePos + 1].toInt()
            JavaBytecodeOperands.IInc(varIndex, delta) to 2
        }
        JavaOperandKind.InvokeInterface -> {
            val cpIndex = readU2(code, afterOpcodePos)
            val count = code[afterOpcodePos + 2].toInt() and 0xFF
            // afterOpcodePos + 3 is the required zero byte; ignored.
            JavaBytecodeOperands.InvokeInterface(cpIndex, count) to 4
        }
        JavaOperandKind.InvokeDynamic -> {
            val cpIndex = readU2(code, afterOpcodePos)
            // afterOpcodePos + 2 / +3 must be zero; ignored.
            JavaBytecodeOperands.InvokeDynamic(cpIndex) to 4
        }
        JavaOperandKind.MultiANewArray -> {
            val cpIndex = readU2(code, afterOpcodePos)
            val dims = code[afterOpcodePos + 2].toInt() and 0xFF
            JavaBytecodeOperands.MultiANewArray(cpIndex, dims) to 3
        }
        JavaOperandKind.TableSwitch -> decodeTableSwitch(code, afterOpcodePos, instructionStart)
        JavaOperandKind.LookupSwitch -> decodeLookupSwitch(code, afterOpcodePos, instructionStart)
        JavaOperandKind.Wide -> decodeWide(code, afterOpcodePos)
        JavaOperandKind.Reserved -> JavaBytecodeOperands.None to 0
    }

    private fun decodeTableSwitch(code: ByteArray, after: Int, start: Int): Pair<JavaBytecodeOperands, Int> {
        // Skip 0..3 padding bytes so the next read is 4-byte-aligned relative
        // to the START of the enclosing code[] (not the start of the file).
        val padding = (4 - ((start + 1) % 4)) % 4
        var p = after + padding
        val defaultOffset = readI4(code, p); p += 4
        val low = readI4(code, p); p += 4
        val high = readI4(code, p); p += 4
        val count = high - low + 1
        require(count >= 0) { "tableswitch high($high) < low($low)" }
        val offsets = ArrayList<Int>(count)
        repeat(count) {
            offsets.add(readI4(code, p)); p += 4
        }
        return JavaBytecodeOperands.TableSwitch(defaultOffset, low, high, offsets) to (p - after)
    }

    private fun decodeLookupSwitch(code: ByteArray, after: Int, start: Int): Pair<JavaBytecodeOperands, Int> {
        val padding = (4 - ((start + 1) % 4)) % 4
        var p = after + padding
        val defaultOffset = readI4(code, p); p += 4
        val npairs = readI4(code, p); p += 4
        require(npairs >= 0) { "lookupswitch npairs($npairs) < 0" }
        val pairs = ArrayList<Pair<Int, Int>>(npairs)
        repeat(npairs) {
            val match = readI4(code, p); p += 4
            val offset = readI4(code, p); p += 4
            pairs.add(match to offset)
        }
        return JavaBytecodeOperands.LookupSwitch(defaultOffset, pairs) to (p - after)
    }

    private fun decodeWide(code: ByteArray, after: Int): Pair<JavaBytecodeOperands, Int> {
        val innerOpcode = code[after].toInt() and 0xFF
        val varIndex = readU2(code, after + 1)
        return if (innerOpcode == JavaOpcode.IINC.code) {
            val delta = readI2(code, after + 3)
            JavaBytecodeOperands.Wide(innerOpcode, varIndex, constant = delta) to 5
        } else {
            JavaBytecodeOperands.Wide(innerOpcode, varIndex) to 3
        }
    }
}

private fun readU2(code: ByteArray, at: Int): Int =
    ((code[at].toInt() and 0xFF) shl 8) or (code[at + 1].toInt() and 0xFF)

private fun readI2(code: ByteArray, at: Int): Int =
    (((code[at].toInt() shl 8) or (code[at + 1].toInt() and 0xFF)).toShort()).toInt()

private fun readI4(code: ByteArray, at: Int): Int =
    ((code[at].toInt() and 0xFF) shl 24) or
        ((code[at + 1].toInt() and 0xFF) shl 16) or
        ((code[at + 2].toInt() and 0xFF) shl 8) or
        (code[at + 3].toInt() and 0xFF)
