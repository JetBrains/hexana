package org.jetbrains.hexana.disassembly.core

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Decodes a contiguous byte window into a stream of [DecodedInstruction]s.
 *
 * The disassemble entry point is a member extension on [CommonByteBuffer]: call
 * it inside `with(disassembler) { buffer.disassemble(...) }` (or
 * `disassembler.run { buffer.disassemble(...) }`). The buffer is the subject of
 * decoding; the disassembler is the scope.
 *
 * The returned [Sequence] is lazy: instructions are proxies that defer the
 * backend decode call until a property is read. Iteration over the same byte
 * snapshot is repeatable; mutations to the underlying buffer between iterations
 * may yield different results.
 *
 * The disassembler knows only `(bytes, baseAddress, arch)`. It does not consult
 * relocations, symbol tables, debug info, or any host-supplied annotations —
 * those are applied by the listing layer downstream.
 */
interface Disassembler {
    fun CommonByteBuffer.disassemble(
        baseAddress: ULong,
        arch: Arch,
    ): Sequence<DecodedInstruction>

    /**
     * Returns a formatter that renders instructions produced by this
     * disassembler. The formatter is only guaranteed to handle instructions
     * from its originating backend; instances from a different [Disassembler]
     * are not supported.
     */
    fun formatter(arch: Arch, syntax: AssemblySyntax): AssemblyFormatter
}
