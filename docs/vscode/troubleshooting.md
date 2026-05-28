---
title: Hexana for VS Code — Troubleshooting
description: Common issues users hit with the Hexana VS Code extension and how to resolve them.
version: "0.2.0"
---

# Troubleshooting

Common issues users hit with Hexana for VS Code and how to resolve them.

## A .wasm file opens in the default editor, not Hexana

By default, installing Hexana sets:

```json
"workbench.editorAssociations": {
  "*.wasm": "hexana.wasmEditor"
}
```

If a `.wasm` opens elsewhere:

1. Open `Settings → User Settings → Search "editorAssociations"`.
2. Confirm `*.wasm` maps to `hexana.wasmEditor`. If a previous extension claimed the association, override it.
3. Or, right-click the `.wasm` tab → **Reopen Editor With…** → choose **Hexana Wasm Editor** and click **Configure default editor for `*.wasm`**.
4. Reload the window (`Cmd/Ctrl+Shift+P` → **Developer: Reload Window**).

## The Hexana editor opens but the analysis panel is blank

This usually indicates a webview load failure.

1. Open `Help → Toggle Developer Tools` and check the webview's console for errors.
2. Confirm the extension installed correctly — `Extensions` view → click Hexana → check the version matches what you expect.
3. Reload the window (`Cmd/Ctrl+Shift+P` → **Developer: Reload Window**).
4. If the problem persists, file an issue with the Developer Tools console output attached.

## "Hexana editor: cannot read .wasm file"

The extension host reads the file via `vscode.workspace.fs`. Failures usually mean:

- The file is **larger than VS Code's `files.maxMemoryForLargeFilesMB`** (default 4096 MB on most setups, but lower on some). Increase if needed.
- The file is on a remote / network filesystem that does not implement VS Code's `FileSystemProvider` correctly. Try copying to a local path.
- File permissions block read.

## The Run button is greyed out

No supported runtime (Wasmtime / WAMR / GraalVM) is discoverable. Either:

- Install Wasmtime: `curl https://wasmtime.dev/install.sh -sSf | bash`.
- Install WAMR — see [WAMR's README](https://github.com/bytecodealliance/wasm-micro-runtime).
- Install GraalVM with GraalWasm — Hexana auto-detects common install locations.
- Or set `hexana.wasmtimePath` to an absolute path in your VS Code settings.

After fixing, reload the window.

## The Debug button is greyed out

Debug requires a debug-capable runtime (Wasmtime or WAMR) **and** LLVM 22.1 or newer on `PATH`.

- Check `lldb --version`. If it is older than 22.1, install a newer LLVM toolchain.
- Confirm Wasmtime or WAMR is on `PATH` (GraalVM debug is not yet wired).
- Confirm the binary you opened was compiled with debug info (`-g`); a stripped binary will let the debugger attach but produce no usable source mapping.

## A breakpoint does not bind during Debug

Hexana maps PCs to source files via DWARF. Common reasons a breakpoint does not bind:

- The binary was compiled without `-g`, or with `-Cstrip=debuginfo` (Rust) / equivalent.
- The source path stored in DWARF differs from what's open in VS Code. Aggressive-CGU Rust builds tag source files with a CGU suffix; Hexana applies fuzzy matching but does not always win.
- The line you set the breakpoint on lies inside an inlined function whose DWARF was elided.

Open **View → Output → Hexana** and look for `[dwarf-debug]` lines to see what path Hexana tried to resolve.

## "Hexana MCP server: download failed" / MCP integration broken

The MCP server is downloaded on demand from GitHub Releases. If it fails:

1. Check VS Code's **Output → Hexana MCP** channel for the underlying error (network, disk, JDK).
2. Confirm a Java 21+ JDK is discoverable — through `JAVA_HOME`, `PATH`, or the `hexana.mcp.javaHome` setting.
3. Run **Hexana: Reinstall MCP Server** from the Command Palette to force a fresh download.
4. If a previous download appears stuck (no progress, but the download lock file is present), Hexana detects and recovers stale locks automatically; reload the window if that fails.
5. Disk corruption or a partial extraction sometimes leaves a broken cache — running the Reinstall command from step 3 deletes and re-fetches.

## Run fails on a component binary with "unable to compose"

The Component Model composition step requires `wasm-tools` or `wac` on `PATH`. Install either:

```
cargo install wasm-tools
# or
cargo install wac-cli
```

Then verify with `wasm-tools --version` (or `wac --version`) in a fresh terminal. Reload VS Code.

If composition still fails:

1. Confirm all dependency `.wasm` files for the component are present in your workspace.
2. Check the run dialog for warnings about unresolved imports — those are the deps Hexana could not find. See [`component-model.md#dependency-resolution`](component-model.md).
3. Try `wasm-tools compose <target.wasm>` manually with the resolved dependency list to see the underlying error.

## Component Model dependency resolution picks the wrong .wasm

Hexana scans the entire workspace and matches by fully-qualified interface name (`namespace:package/interface`). If two components export the same interface (e.g. multiple versions of the same dependency), Hexana picks one and proceeds.

Workarounds:

- Move the unwanted dependency out of the workspace.
- Add `.vscode/settings.json` to exclude the file from Hexana's search — **not yet supported**; track on the roadmap.
- Vendor the correct dependency under a unique workspace path.

## Run terminal closes immediately or shows no output

Wasmtime exited fast. Check the terminal's scroll-back — even if it closed, the output should remain visible (VS Code keeps terminal history). If the terminal vanished entirely:

1. Re-run with a longer-lived export (or add a sleep / loop to the WASM-side code).
2. Check `hexana.enableStatistics` / VS Code telemetry — neither should affect runs, but if you suspect an env-var collision, disable both and retry.

## The WAT tab is slow on large files

The WAT printer is WASM-based and runs inside the webview. For multi-MB binaries with tens of thousands of functions, rendering can take several seconds the first time. Subsequent renders within the same editor session are cached.

For very large files where WAT is unusable:

- Use the **Summary** and **Top** tabs to identify hot regions instead.
- Or open in the JetBrains plugin, which has a virtualised WAT viewer optimised for the largest binaries.

## Hex viewer feels slow / janky

The hex viewer is virtual-scrolling and should stay smooth on any file size. If it feels slow:

1. Check Developer Tools for excessive recompositions or layout thrashing.
2. Confirm you're on the latest VS Code (Compose-for-Web depends on modern browser features).
3. File an issue with a screen recording and the file size — performance regressions are tracked.

## Analytics consent dialog won't go away / shows twice

The dialog appears once per fresh install (or after `globalStorage` is cleared). If it shows repeatedly:

1. Confirm VS Code is not running with `--user-data-dir` pointing at a fresh location each time (some CI setups do this).
2. Check `<globalStorage>/hexana-analytics-id` exists and is writable.
3. As a last resort, disable analytics: `"hexana.enableStatistics": false` in `settings.json`.

## The extension is not available in Cursor / VSCodium

Open VSX is the marketplace these editors use by default. Confirm:

- The extension is published to Open VSX (it is: `https://open-vsx.org/extension/JetBrains/hexana-wasm`).
- Your editor is configured to query Open VSX rather than the Microsoft marketplace.
- If the extension still doesn't appear, install from `.vsix` manually.

## How do I file a bug?

`https://github.com/JetBrains/hexana/issues`. Include:

- VS Code (or fork) name and version.
- OS.
- Hexana version (visible in the Extensions view).
- A minimal `.wasm` reproducer if relevant.
- Developer Tools console output for webview-side issues, or the extension host log (`Developer: Show Logs → Extension Host`) for host-side issues.

## See also

- [`getting-started.md`](getting-started.md), [`run-support.md`](run-support.md), [`component-model.md`](component-model.md).
- The JetBrains plugin's [troubleshooting page](../jetbrains/troubleshooting.md) for IDE-side issues.
