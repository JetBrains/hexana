---
title: Disassembler Backends — AOT and Redline
description: How Hexana executes the bundled Capstone WASM disassembler — bytecode AOT (default) and Cranelift native (Redline, experimental). Switching between them, when each is faster, and how to observe in-flight decode work.
audience: [users]
---

# Disassembler Backends

Hexana disassembles native binaries (ELF, Mach-O, PE) through a bundled Capstone WASM module. The module itself is the same regardless of how it runs — the difference is how the WASM bytecode is *executed* inside the JVM.

Two backends are supported:

| Backend | Default | Execution path | Distribution |
|---|---|---|---|
| **Bytecode AOT** | yes | Capstone WASM is lowered to JVM bytecode at build time and shipped inside the plugin. Runs on the JVM with no native components. | Single jar slice, every platform. |
| **Cranelift / Redline** | experimental | Capstone WASM is compiled to native machine code at build time, one binary per (OS, architecture), and loaded at runtime via Project Panama FFM (Java 25+) or jffi. | One slice per host: macOS arm64 / x86-64, Linux x86-64 / aarch64, Windows x86-64. |

Both backends produce **identical** disassembly output. The choice is a performance trade-off, not a feature trade-off.

## When each backend is faster

The AOT backend is the safe default — it runs on every platform the IDE itself runs on, with no extra files to deploy and no native-loader surprises.

The Redline backend has lower overhead per decode call. On large `.text` sections (typically several MB and above), or when scrubbing quickly through a virtualised disassembly view, the difference becomes visible. On small or already-disassembled binaries (everything is cached) the two backends are indistinguishable.

If no native slice is available for the host platform, Redline transparently falls back to the AOT path — selecting it never breaks the disassembly view, it simply has no effect.

## Switching backends

Backend selection is a Registry key.

1. Open **Help → Find Action…** (`Cmd+Shift+A` on macOS, `Ctrl+Shift+A` on Linux / Windows).
2. Type `Registry…` and open the Registry dialog.
3. Filter for `hexana.disassembly`.
4. Toggle the keys you want.

| Registry key | Default | Effect |
|---|---|---|
| `hexana.disassembly.backend.redline` | `false` | When `true`, use the Cranelift native backend; when `false`, use the bytecode AOT backend. |
| `hexana.disassembly.perf.logging` | `false` | When `true`, emit `[disasm-perf]` timing lines to `idea.log` for every decoded chunk. Useful when comparing the two backends on a specific binary. |

The change takes effect on the next disassembly panel open, or via the panel's Retry button — there is no restart required.

## Observing in-flight decode work

The Capstone library itself is not thread-safe. Hexana serialises all decode calls through a single-slot dispatcher, so several panels (or several virtualised windows in the same panel) queue rather than running in parallel.

The number of decode requests currently in flight is surfaced as a status-bar widget:

> `Disassembling: 2`

When the queue drains, the widget hides itself. This is also the easiest way to spot whether the disassembly view is genuinely busy or whether a UI freeze has another cause.

## Reading `[disasm-perf]` logs

With `hexana.disassembly.perf.logging = true`, each decoded chunk emits a line like:

```
[disasm-perf] backend=redline arch=x86-64 bytes=131072 instr=24871 wall=83ms
```

- `backend` — `aot` or `redline`.
- `arch` — the ISA the decoder ran.
- `bytes` — input length passed to Capstone (an instruction window, not the whole section).
- `instr` — instructions returned.
- `wall` — wall-clock time spent inside the decoder, excluding queue wait.

To compare backends on the same binary:

1. Open the binary, scroll through the disassembly tab to populate the cache.
2. Note the `wall` values.
3. Toggle `hexana.disassembly.backend.redline`, close and reopen the panel.
4. Repeat — chunks are decoded fresh, so the new `wall` values are directly comparable.

## Caveats

- Redline is experimental in 0.10. If you hit a crash or a decoder mismatch, switch back to AOT and file an issue with the `[disasm-perf]` lines and the binary's architecture, OS, and platform.
- The native slice is loaded once per IDE session. Toggling the Registry key while a disassembly is in flight is safe but cosmetically inconsistent — the in-flight chunk finishes on the old backend, subsequent chunks use the new one.
- Both backends share the same on-disk decode cache (an LRU keyed by `(section offset, length, ISA)`). Switching backends does not invalidate cached results — and because the output is byte-for-byte identical, there is no need to.