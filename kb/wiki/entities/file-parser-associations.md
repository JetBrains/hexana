# File parser associations

A per-file override of [[entities/binary-provider-selection]]: the user picks which parser opens a
file, or "Generic binary" for none. Stored in `.hexana/file_associations.json`, shared by the
IntelliJ plugin and the VS Code extension, modelled on `.hexana/runtimes.json`
([[entities/wasm-runtime-compatibility]]).

## Shape

```
binaryProvider  BinaryParser (enum, stable `id`)          ← vocabulary of the file
                createBinaryProvider(parser, bytes, path) ← forced build, null = cannot read
                binaryParserOf(provider)                  ← which entry to mark as current
plugins-shared  features/FileAssociations.kt              ← parse / serialize / schema / key
idea-plugin     FileAssociationsService (+Store, Tracker)  VFS-watched project service
                HexanaFileEditor.resolveProvider           override applied before detection
                ui/ParserChoice.kt (FormatBadge)           badge → platform list popup
                FileAssociationsConfigurable               Tools | Hexana | File Parser Associations
vscode-plugin   WasmEditorProvider.ts                      read/watch/write, key per document
  webview       Main.kt                                    builds the provider with the override
web-ui          ParserChoice.kt, FileAssociationsDialog.kt badge menu + settings dialog
```

File format (keys sorted, layout fixed so re-saves are byte-stable):

```json
{ "$schema": "./file_associations.schema.json", "version": 1,
  "associations": { "fw/boot.img": "elf", "data/blob.dat": "generic" } }
```

## Decisions worth remembering

- **Keys are project-relative `/` paths**, so the file means the same on every OS and checkout.
  Only files on the local disk inside the project (IntelliJ `project.basePath`; VS Code the
  workspace folder that *contains* the file — no first-folder fallback, unlike runtimes) can
  carry a choice. Archive entries, nested modules and in-memory views get a plain badge.
  A file in the project root is therefore stored under its bare name, which *looks* like name
  matching but is not. Matching by file name (`"match": "filename"`) was considered on
  2026-11-02 and declined: relative-path matching is the intended behaviour.
- **Parser ids reuse `binaryFormatId` names** (`wasm`, `elf`, `macho`, `pe`, …) so scripts and the
  file share one spelling; `generic` is new. Ids are a committed format: never rename.
- **Entries are coarser than providers**: Mach-O covers thin + universal, Arrow covers file +
  stream (stream marker believed because the user narrowed the format), PCAP covers both.
- **A forced parser that cannot read the bytes falls back to detection**, not to generic, and
  the badge turns warning-coloured with a tooltip. "Cannot read" is narrow: native parsers need
  `NativeBinaryFormatDetector` to report their own format (no header → no format object to build
  from); any other parser is refused only if its constructor throws. Lazy parse failures surface
  in tabs like they do for corrupt files.
- **Both editors swap the provider in place.** IntelliJ keeps the provider and the badge's
  `ParserChoice` in Compose state inside `HexanaFileEditor.initPanel`; a changed association
  re-runs `resolveProvider` (off the EDT), resets the tab index, retires the WAT text editor, and the
  view recomposes with the new provider. *Not* under `key(provider)`: tearing the whole tree down
  (hex view, Swing-hosted panels) made every switch blink. `BinaryEditorView` keys all
  provider-dependent state on `binaryProvider` instead — keep it that way when adding state there.
  The other blink source was `BinaryEditorView`'s sidecar gate: `sidecarRestored` is keyed on the
  provider, so each swap replaced the *whole* view with a progress indicator for the `.hexana.json`
  restore round-trip (one blank frame in a screen recording). The gate now applies only until the
  first restore completes (`restoredOnce`); later swaps render straight away.
- **VS Code had two blink sources of its own.** (1) The webview's PDB gate in `Main.kt` showed a
  full-page "Loading debug symbols…" for every *new* `PeBinaryProvider` — e.g. leaving "Generic
  binary" on a `.dll`, or a failing parser falling back to detection from there. Now the editor
  keeps showing the previous provider (and its fallback flag) until the replacement's PDB is
  attached; the loading text only appears for the first provider of a set of bytes. (2) The
  divider above an empty panel was dropped one frame late, through an effect reporting "no tabs";
  `HexanaPanel` now draws its own divider (`onResize`) and drops it in the same frame. The first cut closed and reopened the file instead
  (`closeFile` + `openFile` in one EDT turn); in 2026.2 that only took effect on the *second*
  switch — the reopen could land before the close completed and keep the old editor. The platform
  has a `FileEditorManagerImpl.CLOSING_TO_REOPEN` flag for its own reopen path, a hint that the
  naive pair is not safe. The VS Code webview `remember`s the provider on the chosen parser, so an
  association added for *another* file does not reparse this one.
- **The VS Code host only moves text.** The webview renders both JSON and schema (shared Kotlin);
  `WasmEditorProvider.ts` reads, watches (one watcher per folder, alongside the runtimes one) and
  writes with read-compare-write, and sends `fileAssociationKey` with `load-wasm`. Messages:
  `file-associations-loaded`, `save-file-associations`, `open-file-associations-dialog`,
  `open-file-associations-file` (badge menu's "Edit File Associations…": opens the JSON beside the
  binary, creating it and the schema first if missing; IntelliJ opens the settings page instead); command
  `hexana.configureFileAssociations` (needs an open Hexana editor, like the runtimes dialog).
- **"Open As Protobuf Message" beats an association** — it is an explicit action on a synthetic file.
- **`.gz` / `.tgz`**: the choice applies to the inflated content. A `.tgz` with a choice skips the
  tar shortcut and goes through the generic gzip branch with the name rewritten to `.tar`.
- **The browser webapp has no project**, so `Host.canEditFileAssociations` is false and the badge
  stays a label there.

## Traps

- **Read-modify-write is serialized** in `FileAssociationsService` (one lock around transform +
  write + `tracker.recordSave`, and around `reload`'s read + observe). The VFS refresh stays
  *outside* the lock: a synchronous refresh can wait for an EDT write action while the EDT sits
  in a VFS listener waiting for the lock. `FileAssociationsStore` writes temp-then-atomic-move so
  VS Code's watcher never reads half a file (which would parse as "nothing associated").
- **The IntelliJ rebuild runs off the EDT** (editor IO scope; `collect` is sequential and the
  StateFlow conflates bursts); only publishing is on the EDT. It then calls
  `HexanaFileEditor.providerChangeListeners`, which the Structure view model uses to rebuild its
  root and fire `onModelChanged` — public API, no `StructureViewWrapperImpl` needed.
- Reaching for an effect to report layout facts upward (e.g. "no tabs") costs a frame and
  shows as a blink; decide in the composable that has the fact.
- **Web `liveProvider` must be keyed on `provider`** as well as the reparse counter, or a changed
  parser keeps tabs and problems on the old provider while the toolbar and hex view move on.
- **`computeExtraTabs` decides class/DEX tabs by provider type when it has a provider**, magic
  only without one — or a forced "Generic binary" class/DEX file gets its bytecode tabs back.

- A plain `GenericBinaryProvider` labels itself with the native header it sniffs
  (`nativeFormat`), so a forced "Generic binary" on an ELF read "ELF64". Both badges call the
  3-arg `fileTypeDescription(provider, fileName, chosenParser)`, which labels it "Generic binary".
  Match it through `binaryParserOf`, never the exact class: IntelliJ hands over a
  `GenericBinaryVirtualFileProvider` subclass (the first fix missed exactly that).

- `HexanaFileEditor.parserOverride` / `parserOverrideFailed` must be declared **before** `init`:
  `initPanel()` runs from `init` and a later initializer would reset them.
- `HexanaSettingsTracker` (in `RuntimeTargetsTracker.kt`) is now shared by both settings files;
  `runtimeTargetsEventMatches` is the path matcher for both despite its name.
