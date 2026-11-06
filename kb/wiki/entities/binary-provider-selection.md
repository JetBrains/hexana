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
        │    1. content, believed over any name:
        │      PAR1 magic (both ends)           → Parquet
        │      ARROW1 magic (both ends)         → Arrow(FILE)
        │      \0asm + version 01 00 / 0d 00    → Wasm | WasmComponent
        │    2. then the name, for what nothing above could recognize:
        │      ext "pb"                         → ProtobufMessage    ) no magic exists,
        │      ext "parquet"                    → Parquet            ) or it was truncated
        │      ext "arrow" / "arrows"           → Arrow(STREAM|FILE) ) away
        │    3. else                            → NativeOrGeneric
        │                                              └─► nativeOrGenericBinaryProvider
        └─► the matching provider, over bytes.duplicate().position(0)
```

`resolveBinaryProviderKind` is exposed separately because IntelliJ cannot use the providers this
module builds for two of the kinds: the WAT side view needs `WasmBinaryVirtualFileProvider` /
`WasmComponentBinaryVirtualFileProvider`, which carry the `VirtualFile` a text document is opened
over. It takes the kind and builds its own, or hands an adjusted one back to the
`createBinaryProvider(kind, bytes, sourcePath)` overload.

Above all of this sits a user's explicit choice for the file, from `.hexana/file_associations.json`
(`createBinaryProvider(parser, …)`); see [[entities/file-parser-associations]].

IntelliJ layers one more signal on top, in `resolveIdeBinaryProviderKind`:

```
HexanaProtobufMessageVirtualFile          → ProtobufMessage    forced: the user said so
        │
resolveBinaryProviderKind(bytes, name)    → content, then name
        │  (NativeOrGeneric only)
        ▼
myFile.fileType: Parquet | Arrow | ProtobufMessage             the IDE's registry
        │  (still nothing)
        ▼
NativeOrGeneric
```

## Decisions worth remembering

- **Content beats the name, except for the three magic-less formats.** A wasm module a build step
  named `.arrow` is a wasm module. Only `pb` / `parquet` / `arrow` fall back to the extension, and
  only because there is nothing else to fall back on — a Protobuf wire message has no magic at all,
  and a Parquet or Arrow file truncated past its trailing magic has lost its. The first cut of this
  factory had the extension checks *before* the Wasm selector, which quietly misclassified any of
  the three names holding real Wasm; the order is load-bearing, not incidental.
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
- **Inflation is capped at 128 MiB on both hosts** (`MAX_INFLATED_BYTES` in `HexanaFileEditor.kt`
  and in `WasmEditorProvider.ts`, matching `MAX_SBOM_INFLATED_BYTES`). A gzip stream declares
  nothing about its output size, and both inflate into a process the user cannot afford to lose —
  the IDE's heap, the extension host. Over the cap the file opens as the compressed bytes it is.
- **An archive entry is inflated on the same path as a file from disk.** VS Code stashes extracted
  ZIP/TAR entries in `inMemoryModules`, which `openCustomDocument` serves from a branch that
  returns early — so `inflateIfGzipped` has to run in *both* branches, or a `module.wasm.gz` inside
  an archive opens as a hex dump of the gzip stream.
- **`sourcePath` and `fileName` are separate parameters** (the second defaults to the first). A host
  that inflated `part-0.parquet.gz` holds bytes named `part-0.parquet` while the path on disk still
  ends `.gz`: resolution needs the former, and the Parquet/Arrow/pcap/native providers record the
  latter as what scripts read as `binary.path` (see [[entities/hexana-scripting-api]]).
- **The factory normalizes the buffer to position 0 once.** Providers read through
  `sharedBytes.duplicate()` and a duplicate inherits the source cursor, so a buffer handed over
  mid-read would have every provider parsing from the middle of the file.
- **The IDE file type registry is a real signal, and the shared factory cannot see it.** Under
  Settings | Editor | File Types a user binds arbitrary patterns to a registered type, and
  `ColumnarBinaryFileTypeDetector`'s verdict lands there too — so `*.pq` mapped to Parquet, or a
  Parquet file truncated past its trailing magic, is only resolvable from `myFile.fileType`. The
  first cut of the factory deleted those `is ParquetFileType` / `is ArrowFileType` checks on the
  grounds that the detector behind them is magic-based and the factory now does magic itself; that
  reasoning silently discarded every association configured by hand. The type is consulted last, so
  a declaration can never override a format the bytes prove.
- **"Open As Protobuf Message" is forced, a `.pb` name is not.** The action beats even the content —
  decoding bytes that look like something else is the whole point of it — so it is matched on the
  synthetic `HexanaProtobufMessageVirtualFile` it creates. Matching on `ProtobufMessageFileType`
  instead would sweep in every plain `.pb` on disk and let the name beat the bytes for it.
- **A file nothing recognizes keeps its `VirtualFile` in the IDE.** `resolveProvider` swaps a plain
  `GenericBinaryProvider` for `GenericBinaryVirtualFileProvider` when there is a real file behind
  it, because `FileDescriptionBar`'s reveal action and `HexanaStructureViewBuilder` read the file
  back off the provider via `BinaryVirtualFileProvider.file`.

## Reaching the editor at all is a separate problem

Resolving the provider only matters once the file is routed to the Hexana editor, and each host
gates that independently:

- IntelliJ — `HexanaFileEditorProvider.accept` (extensions and file types) plus the content
  detectors `NativeBinaryFileTypeDetector` and `ColumnarBinaryFileTypeDetector`. `accept` has no
  magic of its own, so a format is only reachable by content if one of those two detectors returns
  a Hexana file type for it: teaching the factory to resolve extensionless Wasm did nothing until
  `\0asm` was added to `NativeBinaryFileTypeDetector`. Keep its Wasm test and
  `WasmBinaryProviderSelector` accepting the same bytes, or a tab gets typed Wasm and then shows a
  generic hex view.
- VS Code — three surfaces, and a fourth for archive entries:
  - `customEditors` in `package.json` contributes **two** entries. `hexana.wasmEditor` lists the
    formats Hexana claims at `priority: "default"` and opens them on its own.
    `hexana.binaryEditor` matches `*` at `priority: "option"`, which puts "Hexana Binary Editor"
    in the "Reopen Editor With…" picker for every file without ever being chosen automatically —
    the only way to open a binary whose extension nothing claims (an Arrow stream written
    `.stream`, a firmware image `.dat`). Both resolve to one `WasmEditorProvider` instance,
    registered twice. The priorities are load-bearing: `default` on `*` would open every text
    file as hex, and two *default* editors matching one file make VS Code suppress auto-opening
    and show the picker instead, so a `.wasm` would stop opening in Hexana by itself.
  - `isNativeBinary` in `src/NativeBinaryFileTypeDetector.ts` intercepts an unclaimed file's tab
    when its magic is recognizable and reopens it (persisting a `workbench.editorAssociations`
    entry so later opens are direct). Its `KNOWN_EXTENSIONS` mirrors the *default* selector only.
  - `OPENABLE_ENTRY_EXTENSIONS` in `WasmEditorProvider.ts` decides which archive *entries* open in
    a tab of their own.

These VS Code lists and the IntelliJ ones have to be extended together with the factory —
teaching the factory a format it can never be handed changes nothing.

## Partial views (2026-11-06)

Every `createBinaryProvider` overload and `nativeOrGenericBinaryProvider` take `fileSize: Long?`: the
size on disk when the bytes are only the head of a file over the load limit. Only the model providers
use it; hosts track whether a view is partial. See [[entities/partial-views]].
