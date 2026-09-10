# DWARF outline model

The row model behind the `.debug_abbrev` / `.debug_info` / `.debug_line` editor tabs, shared by the
IntelliJ plugin and the VS Code webview. Lives in `plugins-shared/.../DwarfOutline.kt`; the two
hosts only draw it.

## Why it exists

The tabs started as IntelliJ-only Jewel `LazyTree` code with the label text computed inline in
three `label: (Any) -> String` lambdas. When the VS Code port was asked for, none of that was
reachable from JS. The labels *are* the contract — search matches against exactly the rendered
strings — so a second copy would have drifted. Same split as `DexSmaliText.kt` (the Smali tab).

## Shape

```
DwarfAccessor ──► dwarfOutlineSections(accessor, bytes)   cheap: probes section presence only,
                        │                                  parses nothing (naming a tab is free)
                        ▼
        List<DwarfOutlineSection>(name, roots: () -> List<DwarfOutlineNode>)
                        │  roots() forced when a tab is opened
                        ▼
   DwarfOutlineNode(id, label, offset, size, childrenProvider)
      │                                    └─ null = leaf; otherwise PURE and UN-MEMOIZED
      ├── idea-plugin  DwarfPanels.kt   → buildTree { addNode(node, node.id) }  (Jewel caches children)
      └── web-ui       DwarfEditorContent.kt → flattenDwarfRows(roots, expanded, childrenCache)
                        │
                        ▼
              dwarfSearchIndex(roots, limit = 200_000)
                 idsMatching(needle) / ancestorsOf(id)
```

## Decisions worth remembering

- **`id` is path-derived and unique across the whole tree, not just among siblings.** Jewel defaults
  a node id to its child index, so a node collides with its own first child — that broke expansion
  in the `.debug_line` tree before the ids were made explicit. Both hosts key expansion, selection
  and search navigation on these ids, and they survive a reparse (the earlier IntelliJ code passed
  identity objects, pinning the whole parsed tree in `openNodes`).
- **`childrenProvider` is deliberately not memoized.** Hosts cache instead — Jewel on
  `Tree.Element.Node.children`, the web side in a `MutableMap`. This is what keeps a `.debug_line`
  line program (one very wide level) from being decoded until someone expands it. Un-memoized also
  keeps the model a pure function per `plugins-shared/AGENTS.md`.
- **Searching collapsed subtrees forces the whole tree.** There is no way to search text that has
  not been generated, so `dwarfSearchIndex` walks everything and both hosts defer it to the first
  keystroke rather than to tab open. `limit` bounds a pathological binary.
- **Grouping follows `groupPanels`, not the grouped-*table* rule.** A lone DWARF section keeps its
  own `.debug_*` tab name; it is *not* promoted to `"DWARF: .debug_info"` the way
  `GroupedTableTabDescriptor` would. `web-ui/GroupedPanelTab.kt` and IntelliJ's
  `discoverDwarfPanelNames` both encode this.
- **`Form.toString()` was added** (`debugInfo/dwarf/.../Form.kt`). `Form` had none, so moving labels
  out of IntelliJ turned `DW_FORM_strp` into a default object string. It mirrors
  `Attribute.toString()` but with a hex fallback, because `simpleName` depends on retained class
  metadata and a minified Kotlin/JS bundle can lose it — degrading to `DW_FORM_0xe` rather than
  `Unknown`.
- **Malformed sections degrade to an error row.** The DWARF parsers `error()` on a missing abbrev
  and throw on an unsupported version. On the JVM that is an IDE error; in a webview an uncaught
  throw out of composition kills the editor.

## Known gaps

- **`.debug_info` is still parsed eagerly.** `DwarfInfoSection.scanDwarfInfo()` materializes every
  DIE and `DieField` for every unit up front; only the *node* construction is deferred. A per-CU
  split (`scanDwarfInfoUnitHeaders()` + `readUnitDies(header)`) would make it lazy and benefits
  IntelliJ equally. Watch `CompilationUnitHeader.add(child)` (mutates the header, so a lazy re-read
  must not double-append) and the shared `abbrevCache`.
- **JS byte reads are allocation-heavy.** `WebByteBuffer.get()` does `Int8Array(buffer)[position()]`
  — a fresh typed-array view per byte — and `getShortLE`/`getLongLE` fall back to the
  `CommonByteBuffer` defaults, which allocate a `ByteArray` and slice the `ArrayBuffer` per read.
  DWARF is ULEB128-dense, so this is the first thing to fix if the webview tabs feel slow. Hoisting
  a cached `Int8Array` and routing 2/8-byte reads through the existing `DataView` is contained to
  `encdec/src/webMain/.../toCommonByteBuffer.web.kt`.
- **The browser webapp's default worker route does not show these tabs.** It renders from a
  serialized presentation against a `GenericBinaryProvider` with no `wasmModule` and never calls
  `computeExtraTabs`. Reaching it means either `"DWARF"` in `coreLegacyTabs` +
  `supportedLegacyTabsByFormat["core_wasm"]` (one extra click, like `Wat` today) or a new wire type.
  The VS Code webview and the webapp's legacy main-thread route are unaffected.

## Files

- `plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/DwarfOutline.kt`
- `plugins-shared/src/jvmTest/kotlin/org/jetbrains/hexana/shared/DwarfOutlineTest.kt` — pins labels
- `idea-plugin/src/main/kotlin/org/jetbrains/hexana/dwarf/ui/DwarfPanels.kt`
- `idea-plugin/src/main/kotlin/org/jetbrains/hexana/ui/panel/GroupedPanel.kt`
- `web-ui/src/jsMain/kotlin/org/jetbrains/hexana/vscode/DwarfEditorContent.kt`
- `web-ui/src/jsMain/kotlin/org/jetbrains/hexana/vscode/GroupedPanelTab.kt`
- `web-ui/src/jsMain/kotlin/org/jetbrains/hexana/vscode/VirtualizedList.kt` — `revealIndex`
