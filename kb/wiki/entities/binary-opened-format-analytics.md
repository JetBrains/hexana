# `binary_opened` format analytics — reading the `generic` bucket

How the `format` property of the PostHog `binary_opened` event is derived, and how to break
down `format = generic` to find which file types users open that Hexana does not classify.

## Properties available on generic opens

Defined in `analytics/api/.../HexanaEvent.kt` (`BinaryOpened`), populated only when
`format = generic` (IDE: `idea-plugin/.../analytics/BinaryFormatDetection.kt`; VS Code:
`vscode-plugin/extension-lib/.../Analytics.kt`):

- `generic_ext` — allowlisted extension (`GenericExt`); any other or missing extension → `other`.
  Added 2026-06-08 (a0784f120).
- `detected_format` — magic-byte sniff of the first 512 bytes (`DetectedFormat.fromMagic`);
  absent when nothing matched.
- `generic_header` — first 8 bytes as 16-char hex, or `text` when they decode as printable UTF-8.
  Added 2026-07-14 (49e0e919b). Main tool for finding *unknown-unknown* formats: group by it
  and decode the top hex values.

## Supported formats are never reported as `generic` (since 2026-10-21)

Before 2026-10-21 the IDE reported static archives, DEX, PCAP, protobuf descriptor sets, PDBs,
ARSC, .NET resources and zip containers other than jar/war/apk/zip as `generic` (their
providers extend `GenericBinaryProvider` or fell through to an extension switch). Fixed in
`binaryFormatOf` (IDE), `resolveBinaryFormat` (VS Code, which now runs the same static content
checks as `nativeOrGenericBinaryProvider`) and the webapp's `toBoundedBinaryFormat`. Read
`generic` data from before that date with this in mind.

Rules now:

- Every content-verified provider maps to its own format, including the new `pdb`,
  `portable_pdb`, `arsc`, `dotnet_resources`, plus `archive`, `dex`, `pcap`, `protobuf`.
- Zip content: `jar` / `war` / `apk` by extension, **every other zip (any name) → `zip`**
  (`BinaryFormat.forZipContent`). Without content (providerless editors, `.gz` inner name),
  `BinaryFormat.fromExtension` maps `ZIP_FAMILY_EXTENSIONS` (aar, whl, nupkg, vsix, ipa, docx, …)
  to `zip`.
- IDE classifies by the *logical* name: one trailing `.gz` is stripped (`app.jar.gz` → jar),
  because the editor resolves the provider over the inflated bytes by the inner name.
  `binaryFormatOf` takes a file name, not a `VirtualFile`, so `BinaryEditorView` (tab events) can
  pass its `fileName` for providers that carry no file.
- VS Code never falls back to the extension after content checks fail: the host passes inflated
  bytes under the inner name and the webview picks providers by content, so a malformed `.wasm` /
  `.dex` / `.jar` is shown — and reported — as `generic`. The extension only picks the zip subtype.
- Explicit "Open as Protobuf message" (`ProtobufMessageBinaryProvider`) stays `generic` by design.
- `GenericExt` gained firmware/object/dump buckets: o, obj, exe, elf, ko, efi, sys, hex, uf2, fw,
  rom, pak, dmp, core, raw.

## How `generic` opens happen per surface

- IntelliJ: Hexana claims only its own file types and extensions (`HexanaFileEditorProvider.acceptsFile`).
  So a `generic` open is one of: a claimed extension whose content check failed, a `.gz` whose inner
  name is unknown, an explicit "Open in Hex View" (`HexFileTypeOverrider` sets `BinaryFileType`), or a
  parser the user forced to generic in [[entities/file-parser-associations]].
- VS Code: `hexana.binaryEditor` matches `*` at `option` priority. Every "Reopen With → Hexana Binary
  Editor" on any file (text files too) is a deliberate generic open.

## Queries for breaking down `generic` (HogQL)

Count distinct users, not opens: one user reopening one file inflates the event count. Exclude
`plugin_version`s built before 2026-10-21, because those builds still misreport supported formats.

```sql
-- known magic / extension
SELECT properties.surface s, coalesce(properties.detected_format,'none') d,
       properties.generic_ext e, count() opens, count(DISTINCT person_id) users
FROM events WHERE event='binary_opened' AND properties.format='generic'
  AND timestamp >= toDateTime('2026-10-22')
GROUP BY s,d,e ORDER BY users DESC LIMIT 100
-- unknown-unknowns: decode the top headers by hand
SELECT properties.generic_header h, properties.generic_ext e,
       count() opens, count(DISTINCT person_id) users
FROM events WHERE event='binary_opened' AND properties.format='generic'
  AND properties.detected_format IS NULL AND timestamp >= toDateTime('2026-10-22')
GROUP BY h,e ORDER BY users DESC LIMIT 100
```

## Magic gaps in `DetectedFormat` (as of 2026-11-05)

These formats are common in Hexana's audience but `fromMagic` does not sniff them, so they show up
only as a raw `generic_header`: Java `.hprof` (`JAVA PROFILE`), JFR (`FLR\0`), Windows minidump
(`MDMP`), Java serialization (`AC ED 00 05`), `.pyc` (`?? ?? 0D 0A`), TrueType (`00 01 00 00`),
Android `boot.img` (`ANDROID!`), device tree `.dtb` (`D0 0D FE ED`), git pack/index (`PACK`/`DIRC`),
Lua bytecode (`1B Lua`), Erlang BEAM (`FOR1`), Unity bundles (`UnityFS`), Android VDEX/ART
(`vdex`/`art\n`), Intel HEX (text, `:`-prefixed lines).

Since 2026-11-05 GGUF and safetensors models report `gguf` / `safetensors` instead of `generic`
(see [[entities/ml-model-formats]]); only files ≤2 GB can be opened at all.
