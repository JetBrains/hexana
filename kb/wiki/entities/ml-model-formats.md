# ML model formats — GGUF and safetensors

Support for the two common LLM weight containers, added 2026-11-05 to shrink the `generic`
bucket of `binary_opened` (see [[entities/binary-opened-format-analytics]]).

## Where it lives

- `parsing/mlmodel` (KMP, depends on `encdec` + `kotlinx-serialization-json`):
  - `gguf/GgufParser`, `GgufFile`, `GgmlType` — header, metadata KVs, tensor infos. Versions 1–3,
    either byte order (big-endian is recognised by the byte-swapped version word).
  - `safetensors/SafetensorsParser`, `SafetensorsFile` — the `u64`-length-prefixed JSON header.
  - `ModelByteRange` uses `Long` offsets on purpose (see "Large files" below).
  - `test-data/generate.py` writes `tiny.gguf` / `tiny.safetensors` with the **reference writers**
    (llama.cpp `gguf` 0.8.0, HF `safetensors` 0.5.4); `ReferenceFixtureTest` pins the offsets
    `GGUFReader` reports. Run it in a throwaway venv (`pip install gguf safetensors numpy`).
- `binaryProvider/.../MlModelBinaryProviders.kt` — `GgufBinaryProvider`, `SafetensorsBinaryProvider`
  (both extend `GenericBinaryProvider`), structure ranges, and a `sizeAnalysis` tree grouping tensors
  by layer (`tensorGroupOf`: name up to the first all-digit segment — `blk.12`, `model.layers.3`).
- `plugins-shared/.../MlModelTableTabs.kt` — GGUF: Header / Metadata (arrays expand as child rows,
  capped at 10 000) / Tensors; safetensors: Header (incl. `__metadata__`) / Tensors. Parse
  diagnostics are "Warning" rows in Header, like `.resources`.
- `plugins-shared/.../scripting/MlModelScriptValue.kt` — plain-map values for the browser hosts
  (`binary.raw.parsedGguf` / `parsedSafetensors`); `MlModelScriptValueTest` pins key parity with the
  JVM objects' public getters, which is what the raw `.d.ts` is generated from.

Ids: `BinaryParser` `gguf` / `safetensors`; `binary.format` `gguf` / `safetensors`; analytics
`BinaryFormat.GGUF` / `SAFETENSORS` (all four surfaces: IDE `binaryFormatOf`, VS Code
`contentFormatOf`, webapp `toBoundedBinaryFormat`, and the worker's `nonWasmFormat`).

## Detection

- **Factory** (`nativeOrGenericBinaryProvider`): content only, both formats, checked after the
  .NET resources check and before protobuf/native detection.
  - GGUF: `"GGUF"` + version 1..3 in either byte order.
  - safetensors has no magic. Accepted when the `u64` length is 2..100 MiB (the crate's own cap) and
    fits the file, byte 8 is `{`, the last non-space header byte is `}`, and the first token is a
    quoted key or `}`. Strong in practice; still placed before the structural COFF guess.
- **Routing into the editor**: IntelliJ `acceptsFile` claims `.gguf` / `.safetensors`;
  `NativeBinaryFileTypeDetector` types GGUF content as `BinaryFileType` (GGUF still ships as `.bin`).
  VS Code: both extensions in the `hexana.wasmEditor` selector + `KNOWN_EXTENSIONS`, GGUF magic in
  `isNativeBinary`, both in `OPENABLE_ENTRY_EXTENSIONS` and the diff list. Safetensors is **not**
  content-routed in either host: its signature needs the whole header, not a 256-byte prefix.

## Decisions worth remembering

- **`GgufValue` is one flat data class** (`type` + nullable `integer`/`real`/`bool`/`string`/
  `elementType`/`count`/`elements`), not a sealed hierarchy: the `.d.ts` generator renders a sealed
  interface as an opaque type with only its methods, so scripts would have had no typed fields.
  Likewise safetensors metadata is `SafetensorsMetadataEntry(key, value)` because `Pair` renders as
  `{first: A; second: B}`, and `GgufFile` has no private lookup map (the generator listed it).
- `uint64` metadata keeps its bits in a `Long`; `display()` and the script value read it unsigned.
- Every count/length is checked against the remaining bytes before allocating; arrays keep at most
  2^20 elements (`MAX_ARRAY_ELEMENTS_KEPT`) and skip the rest. Damage yields partial results plus a
  `ModelDiagnostic`, never an exception.
- GGUF tensor sizes come from `GgmlType` block size / bytes (from ggml's `type_traits`); retired ids
  (4, 5, 31–33, 36–38) resolve to `null` → size unknown, shown as `type N`.
- Shapes are shown as stored: GGUF in ggml order (innermost first — `64 × 5` for a 5×64 matrix),
  safetensors row-major.

## Large files

Files over the load limit open as a partial view — see [[entities/partial-views]]. The parsers take
`fileSize` separately from the buffer and read only the head, so a GGUF/safetensors model of any size
still lists every tensor with its true offset; selections fall back to the info record / JSON header
for data past the loaded bytes, and the size tree is omitted.

## Not done yet

- ONNX (protobuf `ModelProto`, extension-only — no magic) and TFLite (FlatBuffers, `TFL3` at offset 4).
- MCP `toModuleIdentity` / `BinarySummaryQuery` still report `generic` for these, as for DEX/ARSC.
- `DetectedFormat` (generic-bucket sniff) is unchanged; GGUF/safetensors opens are now their own format.
