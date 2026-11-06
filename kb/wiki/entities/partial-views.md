# Partial views — opening files over the load limit

Since 2026-11-06 a file larger than a host can hold opens as a **partial view**: its head, read-only,
with the real size shown. Before, IntelliJ threw from `FileChannel.map` (over `Int.MAX_VALUE`) and
VS Code's `workspace.fs.readFile` rejected anything over 2 GiB — both left a broken editor.

## Why only the head

`CommonByteBuffer` and every parser and both hex viewers address bytes with `Int`. Making them
`Long`-addressed (chunked mmap, ranged reads) touches almost every parser; the head-window design was
chosen instead (user decision 2026-11-06). Consequence: formats whose tables sit at the end of the file
(ZIP central directory, Parquet/Arrow footers, ELF section headers) show what the head contains, while
head-described formats (GGUF, safetensors, tar listings up to the window, pcap packets in the window) work.

## Pieces

- `binaryProvider/.../LargeFiles.kt` — `MAX_BINARY_WINDOW_BYTES` = 2 GiB − 1 MiB (headroom so
  `offset + size` near the end cannot overflow `Int`).
- `BinaryProvider.fileSize` (default `bytes.size`) and a `fileSize: Long?` parameter on every
  `createBinaryProvider` overload and `nativeOrGenericBinaryProvider`. Only `GgufBinaryProvider` /
  `SafetensorsBinaryProvider` use it (their parsers validate tensor ranges against it). **Do not use
  `provider.fileSize > bytes.size` to detect a partial view** — every other provider reports its buffer
  size; the hosts track partial state themselves.
- Model tabs select a tensor's data only when it lies inside the loaded bytes, else its info record /
  the JSON header; `sizeAnalysis` is `null` for a partial model (Int-sized `BinarySizeNode`).
- `plugins-shared/.../PartialView.kt` — `partialViewNotice(loaded, fileSize)` (one text for all hosts)
  and `isPartialView`.

## IntelliJ

- `VirtualFile.toBinaryWindow()` (`HexanaUtil.kt`) maps at most the window; used only by
  `HexanaFileEditor.initPanel`. `toMappedBuffer` / `File.toCommonByteBuffer` now throw
  `FileTooLargeToMapException` with a readable message — callers that need the whole file (running
  Wasm, diff, breakpoint identity SHA) must not silently get a head.
- `HexanaFileEditor.partialFileSize` → passed to the factory (`fileSizeFor`: never for an inflated
  `.gz` or in-memory entry) and to `BinaryEditorView`, which routes `editableDoc = null` into the hex
  viewer, description bar and panels (`hexEditDoc`) and skips the sidecar journal restore. Reason:
  "Overwrite" writes the document back over the file and would truncate it to the window.
- A partial Wasm file gets plain `WasmBinaryProvider`s, not the `VirtualFile`-backed ones (they re-map
  the whole file). `GenericBinaryVirtualFileProvider` takes the already-loaded buffer for the same reason.
- `PartialViewNotification` (EditorNotificationProvider) decides from `file.length` because
  notifications are collected before `initPanel` sets the editor's state.
- Structural diff catches `FileTooLargeToMapException` (it used to hang on "Comparing…"); hex diff
  already showed "Could not compare: <message>".
- Test: `PartialViewEditorTest` opens a **sparse** 3 GiB file (`RandomAccessFile.setLength`), so the
  suite writes nothing big.

## VS Code

- `src/largeFiles.ts`: `readHead(uri, max)` reads via a Node file handle for `file:` URIs; other
  schemes have no ranged read, so they are read whole if they fit, else `FileTooLargeError`
  (shown via the existing `load-expired` message). `readPrefix` replaces two whole-file reads used only
  for magic sniffing (`isHexanaBinary`, the tab-reopen detector).
- Limit: `hexana.maxLoadedFileSizeMB`, default **512**, clamped to `MAX_LOADABLE_BYTES` (kept equal
  to the Kotlin constant — a unit test reads `LargeFiles.kt`). Lower than IntelliJ's because the
  webview holds its own copy of the payload.
- `WasmDocument.partialFileSize` → `load-wasm.fileSize` → webview `Main.kt` → factory `fileSize` and
  `BinaryEditorContent(partialFileSize)`: no `HexEditState` (it copies every byte), real size in the
  toolbar, warning notice. Host `overwriteFile` / `saveCopy` refuse a partial document too.
- A partial `.gz` is not inflated (the head of a gzip stream cannot be).
- `messageBytesOrNull` now views a posted `Uint8Array` in place (`Int8Array` over the same buffer)
  instead of copying byte by byte. Test the type on an expression (`payload.unsafeCast<Any>() is
  Uint8Array`): a smart cast on the `dynamic` parameter broke the array-like path with an
  `IrLinkageError` on `length`.
- Analytics: `captureBinaryOpenedOfSize` reports the size on disk.
- "Compare Files" (`openDiff`) stats both sides first and refuses a file over the limit with the reason.

## Not covered

Webapp (50 MB cap, unchanged), MCP/CLI (own explicit caps), scripts (`binary.size` is the loaded size;
raw providers expose `fileSize`), and a movable window for viewing bytes past the head.
