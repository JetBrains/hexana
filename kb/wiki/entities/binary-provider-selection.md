# Binary provider selection

How bytes plus a file name become a `BinaryProvider`. One factory in
`binaryProvider/.../BinaryProviderFactory.kt`, called by every host: the IntelliJ editor, the
VS Code webview, the browser webapp, and the script-tab `open(path)` path.

## Why it exists

Provider selection was implemented twice and the copies drifted. `HexanaFileEditor.resolveProvider`
grew Protobuf-message, Parquet, Arrow, `.tgz` and `.gz` handling; every other host only ever ran

```kotlin
when (WasmBinaryProviderSelector.select(buffer)) {
    Wasm -> WasmBinaryProvider(buffer); WasmComponent -> WasmComponentBinaryProvider(buffer)
    else -> nativeOrGenericBinaryProvider(buffer, path)
}
```

`nativeOrGenericBinaryProvider` resolves only what magic bytes alone can identify. The three formats
it cannot — Parquet, Arrow, Protobuf message — need the *name*, so they existed in the IDE and
nowhere else, even though `ParquetTableTabsProvider` / `ArrowTableTabsProvider` have been in
`TableTabsProviderRegistry` all along and both panels build tabs from that registry generically. A
`.parquet` file in VS Code was a raw hex view with a full tab implementation sitting unreachable
behind it.

## Shape

```
createBinaryProvider(bytes, sourcePath, fileName)          ← what hosts call
        │
        ├─► resolveBinaryProviderKind(bytes, fileName) : BinaryProviderKind
        │      ext "pb"                         → ProtobufMessage    ) name only; no magic exists
        │      ext "parquet" | PAR1 magic       → Parquet            )
        │      ext "arrow"/"arrows" | ARROW1    → Arrow(FILE|STREAM) )
        │      \0asm + version                  → Wasm | WasmComponent
        │      else                             → NativeOrGeneric
        │                                              └─► nativeOrGenericBinaryProvider
        └─► the matching provider, over bytes.duplicate().position(0)
```

`resolveBinaryProviderKind` is exposed separately because IntelliJ cannot use the providers this
module builds for two of the kinds: the WAT side view needs `WasmBinaryVirtualFileProvider` /
`WasmComponentBinaryVirtualFileProvider`, which carry the `VirtualFile` a text document is opened
over. It takes the kind and builds its own.

## Decisions worth remembering

- **Content beats the name, except for the three magic-less formats.** A wasm module a build step
  named `.arrow` is a wasm module. Only `pb` / `parquet` / `arrow` trust the extension, and only
  because there is nothing else to trust.
- **Arrow stream detection is gated behind the extension.** A stream's only marker is a `0xFFFFFFFF`
  continuation word at offset 0, which any all-`0xFF` prefix matches — a padded ROM image, a
  corrupted binary. So `isArrowStreamMagic` is consulted only after the name has already said Arrow
  and the much stronger `ARROW1` file magic has said "not a file". Same rationale the IntelliJ
  `ColumnarBinaryFileTypeDetector` states for not detecting streams at all. Before this, both hosts
  hardcoded `ArrowFormat.FILE`, so streams never worked anywhere.
- **gzip is deliberately *not* in the factory.** The factory is synchronous — callers invoke it from
  Compose `remember {}` — while the only common-code gunzip (`providers/sbom/gunzip`) is `suspend`
  and unimplemented on `wasmJs`. Each host also has its own follow-up work after inflating:
  IntelliJ stashes the inflated bytes in `HexanaBinaryVirtualFile.BUFFER_KEY` so the WAT view reads
  them too; the VS Code host sets `displayName` and the `decompressed` flag. Hosts inflate, strip
  the `.gz`, and pass the result in.
- **`sourcePath` and `fileName` are separate parameters** (the second defaults to the first). A host
  that inflated `part-0.parquet.gz` holds bytes named `part-0.parquet` while the path on disk still
  ends `.gz`: resolution needs the former, and the Parquet/Arrow/pcap/native providers record the
  latter as what scripts read as `binary.path` (see [[entities/hexana-scripting-api]]).
- **The factory normalizes the buffer to position 0 once.** Providers read through
  `sharedBytes.duplicate()` and a duplicate inherits the source cursor, so a buffer handed over
  mid-read would have every provider parsing from the middle of the file.
- **A file nothing recognizes keeps its `VirtualFile` in the IDE.** `resolveProvider` swaps a plain
  `GenericBinaryProvider` for `GenericBinaryVirtualFileProvider` when there is a real file behind
  it, because `FileDescriptionBar`'s reveal action and `HexanaStructureViewBuilder` read the file
  back off the provider via `BinaryVirtualFileProvider.file`.

## Reaching the editor at all is a separate problem

Resolving the provider only matters once the file is routed to the Hexana editor, and each host
gates that independently:

- IntelliJ — `HexanaFileEditorProvider.accept` (extensions and file types) plus the content
  detectors `NativeBinaryFileTypeDetector` and `ColumnarBinaryFileTypeDetector`.
- VS Code — the `customEditors` `filenamePattern` in `package.json`, plus `isNativeBinary` in
  `src/NativeBinaryFileTypeDetector.ts`, which intercepts an extensionless file's tab and reopens
  it (and persists a `workbench.editorAssociations` entry so later opens are direct). A third list,
  `OPENABLE_ENTRY_EXTENSIONS` in `WasmEditorProvider.ts`, decides which archive *entries* open in
  a tab of their own.

These three VS Code lists and the IntelliJ ones have to be extended together with the factory —
teaching the factory a format it can never be handed changes nothing.
