---
title: Hexana 0.11 Release Notes
description: Release notes for the Hexana IntelliJ plugin 0.11 — Node.js and browser WASM runtimes, GraalVM Native Image detection, and an embedded-SBOM viewer with OSV vulnerability reachability.
version: "0.11"
released: 2026-06-11
---

# Hexana 0.11 Release Notes

Released **2026-06-11**.

Hexana 0.11 adds two new WebAssembly run/debug runtimes — Node.js and the browser — recognises GraalVM Native Image binaries, and reads their embedded CycloneDX SBOM, including matching components against the OSV vulnerability database and reporting which CVEs actually survived the image's dead-code elimination.

## What's new

### Node.js and browser runtimes

WASM run/debug configurations can now target **Node.js** and the **browser** (Chrome) in addition to Wasmtime, WAMR, and GraalWasm.

- **Run** under Node.js or the browser — Hexana generates the host glue and launches the module.
- **Debug** is supported for both: the browser path drives **Chrome** through the Chrome DevTools Protocol; Node.js uses its built-in inspector. (DWARF-backed `lldb` debugging via Wasmtime and WAMR is unchanged.)

See [`run-and-debug.md`](run-and-debug.md) for the full runtime matrix.

### GraalVM Native Image detection

![Information bar with the Native Image and SBOM badges](../images/idea/14-native-image-badge.png)

Executables and shared libraries produced by `native-image` (ELF, Mach-O, PE) are recognised by their SubstrateVM fingerprint — the `com.oracle.svm.core.VM=GraalVM …` identity string, `.svm_*` / `__svm*` sections, and the `__svm_version_info` marker — and tagged with a **Native Image** badge. The tooltip reports the GraalVM version, edition, and target platform. Because the identity string lives in read-only data, detection survives release stripping.

### Embedded SBOM viewer

Native Image binaries built with `--enable-sbom` (default-on in Oracle GraalVM for JDK 25+) carry an embedded CycloneDX software bill of materials. Hexana adds an **SBOM** badge and a dedicated **SBOM** tab:

- A metadata header — subject, timestamp, generating tool.
- A searchable, sortable component table — type, group, name, version, licences, PURL.
- A **View raw JSON** action that shows the decompressed CycloneDX document.

### SBOM vulnerability reachability

![SBOM tab with the OSV vulnerability overlay](../images/idea/13-sbom-tab.png)

The SBOM tab can match its components against the **OSV** vulnerability database and overlay known CVEs with CVSS severity, the fixed version, and an advisory link.

- Because a component only appears in the SBOM if `native-image` retained it, **every CVE shown is for code that actually survived dead-code elimination** — not a vulnerability in a dependency the image dropped.
- Binaries built with `--enable-sbom=class-level` embed a compiled-in class/method tree; for those, Hexana refines the verdict to **"vulnerable class retained"** vs **"eliminated by DCE"**.
- Matching is **offline by default** — the OSV database is downloaded once and the component list never leaves your machine. An opt-in toggle additionally queries `api.osv.dev` online.

Both toggles live under **Settings → Tools → Hexana** and are **off until enabled**. See [`settings.md`](settings.md#sbom-vulnerability-matching).

## Changed

- **WASM debugging on Windows** — more reliable breakpoint handling. If breakpoints did not bind on Windows for you before, they should now.

## Patch releases since 0.10

These patch releases shipped between 0.10 and 0.11:

- **0.10.3** (2026-06-10) — statistics-reporting fix.
- **0.10.2** (2026-06-03) — GraalVM Web Image `.wasm` detection with a **Java** badge that jumps to the originating Java bytecode; the `.jit` viewer split into Combined / Bytecode / Machine Code tabs with per-tab copy and Go-To-Offset; search compiled methods by inlined callee (`in:<method>`); an **Instrument JMH forks** option that attaches the JIT dump agent to each forked JMH JVM; foldable custom sections in the virtualised WAT view; and several large-binary responsiveness fixes (Top tab, WasmGC `(rec …)` type groups, WAT rebuild-on-recomposition).
- **0.10.1** (2026-05-29) — list entries for plain ZIP files, a missing scrollbar on the archive entry list, and JIT-binary open fixes.

## Upgrading to 0.11

No breaking changes from 0.10.

- Existing run configurations continue to work. The new Node.js / browser runtimes are additional choices in the runtime picker.
- Native Image detection and the SBOM tab are automatic for matching binaries; they add tabs and badges and never displace an existing editor.
- SBOM vulnerability matching is **off by default** — no database is downloaded and nothing is sent anywhere until you enable it in Settings.

## Known limitations in 0.11

- The SBOM features apply to **GraalVM Native Image** binaries only — a native binary without an embedded SBOM gets the disassembly view but no SBOM tab.
- The online OSV query sends the component coordinate list to `api.osv.dev`; it is off unless you explicitly enable it (and requires the offline matcher to be on first).
- Browser debugging targets **Chrome** (via the Chrome DevTools Protocol). Other browsers are not yet wired for debug.
- Carryover from 0.10: native-binary support is experimental; the Cranelift / Redline disassembler backend is experimental; Java integration covers Java sources only; quick-fixes are not yet wired on the WebAssembly Java or WIT inspections.

## Earlier releases

- [Hexana 0.10 release notes](changelog-0.10.md) — 0.10 (2026-05-27), plus the 0.10.1–0.10.3 patch line summarised above.
- [Hexana 0.9 release notes](changelog-0.9.md) — 0.9 (2026-05-07), 0.9.1 (2026-05-20).
