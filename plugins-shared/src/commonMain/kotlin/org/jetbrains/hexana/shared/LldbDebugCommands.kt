package org.jetbrains.hexana.shared

/**
 * Shared LLDB debug command constants and helpers used by both
 * `idea-plugin` (raw LLDB stdin/stdout) and `vscode-plugin`
 * (lldb-dap with `stopCommands` / `initCommands`).
 *
 * These constants centralise the platform-specific knowledge about
 * Wasmtime JIT trap instructions and LLDB JIT loader configuration
 * so the workarounds are not duplicated across plugins.
 */

// ── Trap-advance commands ───────────────────────────────────────────

/**
 * LLDB Python command that advances the program counter past the
 * current trap instruction emitted by Wasmtime JIT.
 *
 * Wasmtime JIT emits trap instructions at breakpoint sites:
 * - `brk` (4 bytes) on ARM64/AArch64
 * - `ud2` (2 bytes) on x86_64
 *
 * When LLDB stops on such a trap, the PC still points at the trap
 * instruction. A plain `continue` would re-execute the same trap,
 * causing an infinite loop. This command reads the target triple
 * to determine the instruction size and advances the PC accordingly.
 *
 * Used by `idea-plugin` via [LldbCommunicator.sendCommand] and by
 * `vscode-plugin` via lldb-dap `stopCommands`.
 */
const val LLDB_ADVANCE_PC_PAST_TRAP_CMD: String =
    "script lldb.frame.SetPC(lldb.frame.GetPC() + " +
        "(4 if 'arm' in lldb.target.GetTriple() or 'aarch64' in lldb.target.GetTriple() else 2))"

/**
 * LLDB Python command for use in lldb-dap `stopCommands` that
 * conditionally advances the PC past a trap instruction only when
 * the current instruction is actually a trap.
 *
 * On ARM64, checks for `brk` encoding: `(inst & 0xFFE0001F) == 0xD4200000`.
 * On x86_64, checks for `ud2` encoding: first two bytes are `0x0F 0x0B`.
 *
 * The `?` prefix (added by the caller) silences lldb-dap console
 * output on success.
 *
 * This is more conservative than [LLDB_ADVANCE_PC_PAST_TRAP_CMD]
 * because `stopCommands` run on every stop (including steps), so
 * we must verify the instruction is actually a trap before advancing.
 */
const val LLDB_DAP_TRAP_ADVANCE_STOP_CMD: String =
    "script import lldb; t = lldb.debugger.GetSelectedTarget(); p = t.GetProcess(); " +
        "f = p.GetSelectedThread().GetSelectedFrame(); err = lldb.SBError(); pc = f.GetPC(); " +
        "triple = t.GetTriple(); is_arm = 'arm' in triple or 'aarch64' in triple; " +
        "inst = p.ReadUnsignedFromMemory(pc, 4, err) if is_arm else p.ReadUnsignedFromMemory(pc, 2, err); " +
        "is_trap = (err.Success() and ((inst & 0xFFE0001F) == 0xD4200000)) if is_arm else " +
        "(err.Success() and inst == 0x0B0F); " +
        "f.SetPC(pc + (4 if is_arm else 2)) if is_trap else None"

// ── JIT loader configuration ────────────────────────────────────────

/**
 * LLDB command that enables the GDB JIT loader plugin.
 *
 * On macOS (and Linux), this allows LLDB to automatically discover
 * symbols in JIT-compiled code via the GDB JIT interface that
 * Wasmtime uses to register debug info.
 *
 * On Windows, the built-in JIT loader does not work reliably;
 * `idea-plugin` uses a manual workaround (reading
 * `__jit_debug_descriptor` from inferior memory). For lldb-dap
 * on Windows, enabling this setting is still attempted as a
 * best-effort measure.
 */
const val LLDB_ENABLE_JIT_LOADER_CMD: String =
    "settings set plugin.jit-loader.gdb.enable on"

// ── JIT interface function name ─────────────────────────────────────

/**
 * The GDB JIT interface function that Wasmtime calls after
 * JIT-compiling a module and registering its debug symbols.
 *
 * On Windows, `idea-plugin` sets a breakpoint on this function
 * to detect when JIT symbols are available so user breakpoints
 * can be resolved manually (since the built-in JIT loader
 * doesn't work).
 */
const val JIT_REGISTER_FUNCTION_NAME: String = "__jit_debug_register_code"
