package org.jetbrains.hexana.shared

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider

/**
 * The instruction set a native binary's code should be decoded as, from the ELF machine,
 * PE/COFF machine or Mach-O CPU type. `null` when the header names an architecture the
 * disassemblers do not support, or the container has no single architecture (an archive,
 * a fat Mach-O).
 *
 * A 32-bit ARM ELF built for an M-profile core is Thumb throughout; elsewhere Thumb is
 * per function (`NativeFunctionRecord.isThumb`) and the caller applies it.
 */
fun nativeDisassemblyArch(provider: NativeBinaryProvider): Arch? =
    when (val format = provider.nativeFormat) {
        is NativeBinaryFormat.Elf -> archFromElfMachine(format.machine, format.wordSize, (provider as? ElfBinaryProvider)?.armProfile)
        is NativeBinaryFormat.Pe -> archFromPeMachine(format.machine)
        is NativeBinaryFormat.Coff -> archFromPeMachine(format.machine)
        is NativeBinaryFormat.MachO -> archFromMachOCpuType(format.cpuType)
        is NativeBinaryFormat.Archive,
        is NativeBinaryFormat.FatMachO,
        -> null
    }

private fun archFromElfMachine(machine: Int?, wordSize: NativeBinaryWordSize, armProfile: Char?): Arch? =
    when (machine) {
        0x03 -> Arch.X86
        0x28 -> if (armProfile == 'M') Arch.THUMB else Arch.ARM
        0x3E -> Arch.X86_64
        0xB7 -> Arch.ARM64
        0xF3 -> if (wordSize == NativeBinaryWordSize.Bits32) Arch.RISC_V_32 else Arch.RISC_V_64
        else -> null
    }

private fun archFromPeMachine(machine: Int?): Arch? =
    when (machine) {
        0x014C -> Arch.X86
        0x01C0, 0x01C4 -> Arch.ARM
        0x8664 -> Arch.X86_64
        0xAA64 -> Arch.ARM64
        0x5032 -> Arch.RISC_V_32
        0x5064 -> Arch.RISC_V_64
        else -> null
    }

private fun archFromMachOCpuType(cpuType: Int?): Arch? =
    when (cpuType) {
        0x00000007 -> Arch.X86
        0x01000007 -> Arch.X86_64
        0x0000000C -> Arch.ARM
        0x0100000C -> Arch.ARM64
        0x01000018 -> Arch.RISC_V_64
        else -> null
    }
