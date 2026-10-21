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
- Explicit "Open as Protobuf message" (`ProtobufMessageBinaryProvider`) stays `generic` by design.
- `GenericExt` gained firmware/object/dump buckets: o, obj, exe, elf, ko, efi, sys, hex, uf2, fw,
  rom, pak, dmp, core, raw.
