# Knowledge wiki index

Durable, cross-session knowledge for this repository. See the root `AGENTS.md` for the
consult-first / write-back rules.

## Entities

- [[entities/hexana-scripting-api]] — the custom script tab surface (`binary`, `print`,
  `table`, `row`) and how each host binds it.
- [[entities/wasm-runtime-compatibility]] — the Wasm runtime compatibility table: proposal
  detection, `.hexana/runtimes.json`, the badge warning, and the Compatibility tab.
- [[entities/dwarf-outline-model]] — the shared row model behind the `.debug_*` editor tabs:
  node ids, deferred children, whole-tree search, and the grouping rule both editors follow.
- [[entities/binary-provider-selection]] — the one factory that turns bytes plus a name into a
  `BinaryProvider`, why gzip stays outside it, and the per-host lists that decide which files
  reach the editor in the first place.

- [[entities/binary-diff-provider]] — the shared diff model (`BinaryDiffProvider`): tabs,
  detail pane, navigation requests, how IntelliJ and VS Code host it, and the hex viewer in the
  IntelliJ diff window (`diff.DiffTool`).

## Sources

- [[sources/hexana-0-20-whatsnew]] — 0.20 card selection, validated claims,
  capture policy, and generated-page runtime contract.
- [[sources/webassembly-features-json]] — WebAssembly's `features.json`: shape, the four-way
  `status` union, version-string quirks, and its HTTP/ETag characteristics.
