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
- **Parser ids reuse `binaryFormatId` names** (`wasm`, `elf`, `macho`, `pe`, …) so scripts and the
  file share one spelling; `generic` is new. Ids are a committed format: never rename.
- **Entries are coarser than providers**: Mach-O covers thin + universal, Arrow covers file +
  stream (stream marker believed because the user narrowed the format), PCAP covers both.
- **A forced parser that cannot read the bytes falls back to detection**, not to generic, and
  the badge turns warning-coloured with a tooltip. "Cannot read" is narrow: native parsers need
  `NativeBinaryFormatDetector` to report their own format (no header → no format object to build
  from); any other parser is refused only if its constructor throws. Lazy parse failures surface
  in tabs like they do for corrupt files.
- **IntelliJ reopens the editor on change; VS Code re-keys the provider.** An IntelliJ editor wires
  tabs, structure view and the WAT document once, so a changed association closes and reopens the
  file (what the platform does on a file-type change). The VS Code webview simply recomposes:
  the provider is `remember`ed on the chosen parser, so an association added for *another* file
  does not reparse this one.
- **The VS Code host only moves text.** The webview renders both JSON and schema (shared Kotlin);
  `WasmEditorProvider.ts` reads, watches (one watcher per folder, alongside the runtimes one) and
  writes with read-compare-write, and sends `fileAssociationKey` with `load-wasm`. Messages:
  `file-associations-loaded`, `save-file-associations`, `open-file-associations-dialog`; command
  `hexana.configureFileAssociations` (needs an open Hexana editor, like the runtimes dialog).
- **"Open As Protobuf Message" beats an association** — it is an explicit action on a synthetic file.
- **`.gz` / `.tgz`**: the choice applies to the inflated content. A `.tgz` with a choice skips the
  tar shortcut and goes through the generic gzip branch with the name rewritten to `.tar`.
- **The browser webapp has no project**, so `Host.canEditFileAssociations` is false and the badge
  stays a label there.

## Traps

- `HexanaFileEditor.parserOverride` / `parserOverrideFailed` must be declared **before** `init`:
  `initPanel()` runs from `init` and a later initializer would reset them.
- `HexanaSettingsTracker` (in `RuntimeTargetsTracker.kt`) is now shared by both settings files;
  `runtimeTargetsEventMatches` is the path matcher for both despite its name.
