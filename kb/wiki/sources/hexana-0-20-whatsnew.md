# Hexana 0.20 What's New

Release curation for the IntelliJ plugin's 0.20 What's New page. The source of
release facts is `idea-plugin/CHANGELOG.md` at the 0.20 heading.

## Chosen visible workflows

- **Binary-view breakpoints** — WAT (virtualized) and native Disassembly gutters
  can define breakpoints before debugging. The binary-content anchor lets them
  survive a file move and IDE restart; the gutter reports the runtime result.
- **Debugger Console** — supported paused LLDB/GDB sessions gain a command tab.
  Hexana forwards inspection commands while routing execution-control commands
  through IDE actions, keeping the console and debugger state synchronized.
- **Runtime Compatibility** — a project declares targets in
  `.hexana/runtimes.json`; every Wasm binary can show a Compatibility tab and a
  warning badge when its proposals are not supported by those targets. See also
  [[entities/wasm-runtime-compatibility]].
- **DEX Smali** — Android `.dex` files have a baksmali-style view with resolved
  operands, decoded switch/fill-array payloads, and row-to-hex navigation.
- **Structural regions** — hex tinting, labels, previous/next navigation, and
  the Structure tool window now cover every parsed format, not just Wasm/ELF.

These five are intentionally the page cards because each is an independently
visible workflow and has a real opt-in UI-driver capture. Custom scripting,
component-graph navigation, TLS key logs, `resources.arsc`, DWARF grouping, and
script sandboxing remain release-note improvements rather than competing cards.

## Capture policy

`docs/whatsnew/assets.yml` sets `fallback_source: none`. The 0.20 page therefore
requires its selected assets under `docs/whatsnew/captures/0.20/`; documentation
images cannot substitute for a missing capture. Four cards use PNG because they
show one stable product state. Debugger Console uses a short GIF because command
entry and the returned LLDB frame information form one meaningful sequence.

The breakpoint capture uses the dark IDE theme and waits for the rendered red
gutter dot at the navigated instruction. The Console GIF starts with the
Debugger Console selected, then shows `frame info` in the focused input before
showing LLDB's returned frame. The structural-regions capture closes the IDE's
Services and Structure tool windows so the ZIP editor remains the only visible
structure surface.

## Generated resource contract

The generator copies the chosen assets into
`idea-plugin/src/main/resources/whatsnew/0.20/screenshots/`, emits only relative
paths, and records every generated file in `manifest.txt`. This matters because
the native runtime copies only manifest-listed companion assets beside the
temporary `index.html`.
