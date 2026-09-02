package org.jetbrains.hexana.dex

/**
 * Decodes a `code_item`'s `insns[]` byte sequence into [DexInstruction]s.
 *
 * The stream is a sequence of little-endian 16-bit code units. Every instruction
 * has a fixed size given by [DexOpcode.format] except the three payload
 * pseudo-instructions, which are sized from their own contents; they are decoded
 * in place so the stream stays in sync with the executable instructions that
 * branch to them.
 *
 * Like `JavaBytecodeDecoder`, malformed input throws rather than being silently
 * skipped: a reserved opcode or a truncated instruction means decoding has
 * fallen off the rails, and the caller should degrade that one method instead of
 * rendering nonsense.
 *
 * Reference: https://source.android.com/docs/core/runtime/dalvik-bytecode
 */
object DexBytecodeDecoder {

    /** High byte of the first code unit of each payload pseudo-instruction. */
    private const val PAYLOAD_PACKED_SWITCH = 1
    private const val PAYLOAD_SPARSE_SWITCH = 2
    private const val PAYLOAD_FILL_ARRAY_DATA = 3

    fun decode(insns: ByteArray): List<DexInstruction> {
        require(insns.size % 2 == 0) { "insns[] size ${insns.size} is not a whole number of code units" }
        val units = insns.size / 2
        val out = ArrayList<DexInstruction>()
        var address = 0
        while (address < units) {
            val instruction = decodeAt(insns, units, address)
            out.add(instruction)
            address += instruction.codeUnits
        }
        return out
    }

    private fun decodeAt(insns: ByteArray, units: Int, address: Int): DexInstruction {
        val u0 = unit(insns, units, address, address)
        val code = u0 and 0xFF
        val opcode = DexOpcode.forCode(code)
            ?: error("unknown Dalvik opcode 0x${hex2(code)} at code-unit address $address")
        if (opcode == DexOpcode.NOP && (u0 shr 8) != 0) {
            return decodePayload(insns, units, address, u0 shr 8)
        }
        if (opcode.isUnused) {
            error("reserved Dalvik opcode 0x${hex2(code)} at code-unit address $address")
        }

        val size = opcode.format.codeUnits
        // Read the whole instruction up front so a truncated tail fails with a
        // clear message instead of an IndexOutOfBounds from a nibble helper.
        val u = IntArray(size) { unit(insns, units, address + it, address) }
        val nibbleA = (u0 shr 8) and 0xF
        val nibbleB = (u0 shr 12) and 0xF
        val aa = (u0 shr 8) and 0xFF

        val operands = when (opcode.format) {
            DexFormat.F10X -> DexOperands()
            DexFormat.F12X -> DexOperands(registers = listOf(nibbleA, nibbleB))
            DexFormat.F11N -> DexOperands(registers = listOf(nibbleA), literal = signExtend(nibbleB, 4))
            DexFormat.F11X -> DexOperands(registers = listOf(aa))
            DexFormat.F10T -> DexOperands(branchTarget = address + signExtend(aa, 8).toInt())
            DexFormat.F20T -> DexOperands(branchTarget = address + signExtend(u[1], 16).toInt())
            DexFormat.F22X -> DexOperands(registers = listOf(aa, u[1]))
            DexFormat.F21T -> DexOperands(
                registers = listOf(aa),
                branchTarget = address + signExtend(u[1], 16).toInt(),
            )
            DexFormat.F21S -> DexOperands(registers = listOf(aa), literal = signExtend(u[1], 16))
            // const/high16 fills the high half of an int, const-wide/high16 the
            // high quarter of a long; both are stored as the 16 significant bits.
            DexFormat.F21H -> DexOperands(
                registers = listOf(aa),
                literal = signExtend(u[1], 16) shl if (opcode == DexOpcode.CONST_WIDE_HIGH16) 48 else 16,
            )
            DexFormat.F21C -> DexOperands(registers = listOf(aa), index = u[1])
            DexFormat.F23X -> DexOperands(registers = listOf(aa, u[1] and 0xFF, (u[1] shr 8) and 0xFF))
            DexFormat.F22B -> DexOperands(
                registers = listOf(aa, u[1] and 0xFF),
                literal = signExtend((u[1] shr 8) and 0xFF, 8),
            )
            DexFormat.F22T -> DexOperands(
                registers = listOf(nibbleA, nibbleB),
                branchTarget = address + signExtend(u[1], 16).toInt(),
            )
            DexFormat.F22S -> DexOperands(
                registers = listOf(nibbleA, nibbleB),
                literal = signExtend(u[1], 16),
            )
            DexFormat.F22C -> DexOperands(registers = listOf(nibbleA, nibbleB), index = u[1])
            DexFormat.F30T -> DexOperands(branchTarget = address + int32(u[1], u[2]))
            DexFormat.F32X -> DexOperands(registers = listOf(u[1], u[2]))
            DexFormat.F31I -> DexOperands(registers = listOf(aa), literal = int32(u[1], u[2]).toLong())
            DexFormat.F31T -> DexOperands(registers = listOf(aa), branchTarget = address + int32(u[1], u[2]))
            DexFormat.F31C -> DexOperands(registers = listOf(aa), index = int32(u[1], u[2]))
            DexFormat.F35C -> DexOperands(registers = argRegisters(nibbleB, nibbleA, u[2]), index = u[1])
            DexFormat.F3RC -> DexOperands(
                registers = rangeRegisters(aa, u[2]),
                index = u[1],
                isRange = true,
            )
            DexFormat.F45CC -> DexOperands(
                registers = argRegisters(nibbleB, nibbleA, u[2]),
                index = u[1],
                secondaryIndex = u[3],
            )
            DexFormat.F4RCC -> DexOperands(
                registers = rangeRegisters(aa, u[2]),
                index = u[1],
                secondaryIndex = u[3],
                isRange = true,
            )
            DexFormat.F51L -> DexOperands(registers = listOf(aa), literal = int64(u[1], u[2], u[3], u[4]))
        }
        return DexInstruction(address, opcode, size, operands)
    }

    /**
     * `35c` / `45cc` argument registers: the count is the `A` nibble, the
     * registers are `C, D, E, F` packed into the third code unit with `G` — the
     * `A` nibble of the first unit — coming last.
     */
    private fun argRegisters(count: Int, g: Int, packed: Int): List<Int> {
        val all = listOf(
            packed and 0xF,
            (packed shr 4) and 0xF,
            (packed shr 8) and 0xF,
            (packed shr 12) and 0xF,
            g,
        )
        return all.take(count.coerceIn(0, all.size))
    }

    /** `3rc` / `4rcc` register range: [count] registers starting at [first]. */
    private fun rangeRegisters(count: Int, first: Int): List<Int> =
        if (count <= 0) emptyList() else List(count) { first + it }

    private fun decodePayload(insns: ByteArray, units: Int, address: Int, ident: Int): DexInstruction {
        return when (ident) {
            PAYLOAD_PACKED_SWITCH -> {
                val size = unit(insns, units, address + 1, address)
                val firstKey = int32(
                    unit(insns, units, address + 2, address),
                    unit(insns, units, address + 3, address),
                )
                val targets = List(size) {
                    int32(
                        unit(insns, units, address + 4 + it * 2, address),
                        unit(insns, units, address + 5 + it * 2, address),
                    )
                }
                payloadInstruction(address, size * 2 + 4, DexPayload.PackedSwitch(firstKey, targets))
            }
            PAYLOAD_SPARSE_SWITCH -> {
                val size = unit(insns, units, address + 1, address)
                val keys = List(size) {
                    int32(
                        unit(insns, units, address + 2 + it * 2, address),
                        unit(insns, units, address + 3 + it * 2, address),
                    )
                }
                val targetsBase = address + 2 + size * 2
                val targets = List(size) {
                    int32(
                        unit(insns, units, targetsBase + it * 2, address),
                        unit(insns, units, targetsBase + 1 + it * 2, address),
                    )
                }
                payloadInstruction(address, size * 4 + 2, DexPayload.SparseSwitch(keys, targets))
            }
            PAYLOAD_FILL_ARRAY_DATA -> {
                val elementWidth = unit(insns, units, address + 1, address)
                val size = int32(
                    unit(insns, units, address + 2, address),
                    unit(insns, units, address + 3, address),
                )
                require(size >= 0 && elementWidth >= 0) {
                    "fill-array-data-payload at $address has size $size width $elementWidth"
                }
                val byteCount = size * elementWidth
                val dataStart = (address + 4) * 2
                require(dataStart + byteCount <= insns.size) {
                    "fill-array-data-payload at $address needs $byteCount bytes past the end of insns[]"
                }
                val data = insns.copyOfRange(dataStart, dataStart + byteCount)
                // The data is padded to a whole number of code units.
                val codeUnits = (byteCount + 1) / 2 + 4
                payloadInstruction(
                    address,
                    codeUnits,
                    DexPayload.FillArrayData(elementWidth, size, data),
                )
            }
            else -> error("unknown payload identifier 0x${hex2(ident)} at code-unit address $address")
        }
    }

    private fun payloadInstruction(address: Int, codeUnits: Int, payload: DexPayload): DexInstruction =
        DexInstruction(address, DexOpcode.NOP, codeUnits, DexOperands(payload = payload))

    /** Reads one little-endian code unit, reporting [instructionStart] on truncation. */
    private fun unit(insns: ByteArray, units: Int, at: Int, instructionStart: Int): Int {
        if (at >= units) {
            error("instruction at code-unit address $instructionStart runs past the end of insns[]")
        }
        val lo = insns[at * 2].toInt() and 0xFF
        val hi = insns[at * 2 + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    private fun int32(lo: Int, hi: Int): Int = lo or (hi shl 16)

    private fun int64(u1: Int, u2: Int, u3: Int, u4: Int): Long =
        (u1.toLong() and 0xFFFF) or
            ((u2.toLong() and 0xFFFF) shl 16) or
            ((u3.toLong() and 0xFFFF) shl 32) or
            ((u4.toLong() and 0xFFFF) shl 48)

    /** Sign-extends the low [bits] of [value] to a [Long]. */
    private fun signExtend(value: Int, bits: Int): Long {
        val shift = 64 - bits
        return (value.toLong() shl shift) shr shift
    }

    private fun hex2(value: Int): String = value.toString(16).padStart(2, '0')
}
