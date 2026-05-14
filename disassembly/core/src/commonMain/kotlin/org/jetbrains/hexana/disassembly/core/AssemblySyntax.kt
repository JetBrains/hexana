package org.jetbrains.hexana.disassembly.core

/**
 * Textual assembly syntax used when rendering a [DecodedInstruction.Valid] through
 * an [AssemblyFormatter]. The same opcode and operand set has multiple legitimate
 * projections; this is the choice of projection, not of architecture.
 */
enum class AssemblySyntax {
    /** x86 / x86_64 AT&T (GNU `as` default). */
    AT_T,

    /** x86 / x86_64 Intel / MASM / Microsoft. */
    INTEL,

    /** ARM / AArch64 GNU `as`. */
    ARM_GAS,

    /** ARM vendor / "ARM syntax". */
    ARM_VENDOR,

    /** RISC-V GNU `as`. */
    RISC_V_GAS,
}
