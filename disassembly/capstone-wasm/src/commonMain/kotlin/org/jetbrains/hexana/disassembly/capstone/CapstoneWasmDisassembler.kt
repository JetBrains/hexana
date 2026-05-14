package org.jetbrains.hexana.disassembly.capstone

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblyFormatter
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.disassembly.core.Disassembler
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Capstone-shaped [Disassembler] backed by the `wasm-modules/disassembler` WASM
 * blob (yaxpeax under the hood). One instance owns a single WASM module; not
 * thread-safe. Construct one per worker.
 */
class CapstoneWasmDisassembler : Disassembler {
    private val module: CapstoneWasmModule by lazy { loadCapstoneWasmModule() }

    override fun CommonByteBuffer.disassemble(
        baseAddress: ULong,
        arch: Arch,
    ): Sequence<DecodedInstruction> {
        val byteCount = limit()
        if (byteCount == 0) return emptySequence()
        val snapshot = ByteArray(byteCount).also { dst -> for (i in 0 until byteCount) dst[i] = this[i] }
        val decodeExport = decodeExportFor(arch)
        return sequence {
            val bufPtr = module.call("alloc", longArrayOf(byteCount.toLong()))[0].toInt()
            module.writeMemory(bufPtr, snapshot)
            try {
                var offset = 0
                while (offset < byteCount) {
                    val resultPtr = module.call(
                        decodeExport,
                        longArrayOf(
                            bufPtr.toLong(),
                            byteCount.toLong(),
                            offset.toLong(),
                            baseAddress.toLong(),
                        ),
                    )[0].toInt()
                    if (resultPtr == 0) break
                    val item = readResult(resultPtr, offset, arch)
                    module.call("free_result", longArrayOf(resultPtr.toLong()))
                    yield(item)
                    val advance = item.size
                    if (advance <= 0) break
                    offset += advance
                }
            } finally {
                module.call("dealloc", longArrayOf(bufPtr.toLong(), byteCount.toLong()))
            }
        }
    }

    override fun formatter(arch: Arch, syntax: AssemblySyntax): AssemblyFormatter =
        CapstoneWasmFormatter(arch, syntax)

    private fun decodeExportFor(arch: Arch): String = when (arch) {
        Arch.X86 -> "decode_x86"
        Arch.X86_64 -> "decode_x86_64"
        Arch.ARM -> "decode_arm"
        Arch.ARM64 -> "decode_arm64"
        Arch.RISC_V_32 -> "decode_riscv32"
        Arch.RISC_V_64 -> "decode_riscv64"
    }

    private fun readResult(resultPtr: Int, bufferOffset: Int, arch: Arch): DecodedInstruction {
        // 48-byte header layout — must match wasm-modules/disassembler/src/lib.rs.
        val header = module.readMemory(resultPtr, 48)
        val kind = header.readU32LE(4)
        val size = header.readU32LE(8)
        val isTerminator = header.readU32LE(12) != 0
        val address = header.readU64LE(16).toULong()
        val branchCount = header.readU32LE(24)
        val branchOffset = header.readU32LE(28)
        val mnemonicLen = header.readU32LE(32)
        val mnemonicOffset = header.readU32LE(36)
        val operandsLen = header.readU32LE(40)
        val operandsOffset = header.readU32LE(44)

        val byteRange = bufferOffset..(bufferOffset + size - 1)

        return when (kind) {
            KIND_VALID -> {
                val branchTargets = if (branchCount == 0) {
                    emptyList()
                } else {
                    val raw = module.readMemory(resultPtr + branchOffset, branchCount * 8)
                    List(branchCount) { i -> raw.readU64LE(i * 8).toULong() }
                }
                val mnemonic = if (mnemonicLen == 0) {
                    ""
                } else {
                    module.readMemory(resultPtr + mnemonicOffset, mnemonicLen).decodeToString()
                }
                val operands = if (operandsLen == 0) {
                    ""
                } else {
                    module.readMemory(resultPtr + operandsOffset, operandsLen).decodeToString()
                }
                CapstoneWasmValidInstruction(
                    address = address,
                    byteRange = byteRange,
                    arch = arch,
                    isBlockTerminator = isTerminator,
                    branchTargets = branchTargets,
                    renderedMnemonic = mnemonic,
                    renderedOperands = operands,
                )
            }
            KIND_UNKNOWN -> CapstoneWasmUnknownInstruction(address, byteRange)
            else -> error("unknown decode kind: $kind")
        }
    }

    private companion object {
        const val KIND_VALID = 0
        const val KIND_UNKNOWN = 1
    }
}

private fun ByteArray.readU32LE(at: Int): Int =
    (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        ((this[at + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.readU64LE(at: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((this[at + i].toLong() and 0xFF) shl (i * 8))
    }
    return result
}
