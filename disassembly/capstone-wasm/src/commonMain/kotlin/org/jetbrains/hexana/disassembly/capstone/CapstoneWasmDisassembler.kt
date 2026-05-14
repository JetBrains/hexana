package org.jetbrains.hexana.disassembly.capstone

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblyFormatter
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.disassembly.core.Disassembler
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Capstone-backed [Disassembler] running a WASM build of libcapstone.
 *
 * The platform glue (loading the WASM blob, calling its exports, reading and
 * writing its linear memory) is the [CapstoneWasmModule] `expect`/`actual`
 * abstraction. On the JVM it is a Chicory wrapper; on `js` and `wasmJs` it
 * directly drives the same `capstone-wasm` JS package via the host's WebAssembly
 * APIs. Capstone API binding (`cs_open`, `cs_disasm`, `cs_close`, …) and proxy
 * construction live in [commonMain] on top of that abstraction.
 *
 * Skeleton: backend not yet wired. All entry points throw [NotImplementedError].
 */
class CapstoneWasmDisassembler : Disassembler {
    override fun CommonByteBuffer.disassemble(
        baseAddress: ULong,
        arch: Arch,
    ): Sequence<DecodedInstruction> =
        TODO("capstone-wasm binding not yet implemented")

    override fun formatter(arch: Arch, syntax: AssemblySyntax): AssemblyFormatter =
        TODO("capstone-wasm formatter not yet implemented")
}
