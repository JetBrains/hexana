# WebAssembly `features.json`

Upstream source of the runtime support matrix Hexana's compatibility table is built from.

- Data: `https://raw.githubusercontent.com/WebAssembly/website/refs/heads/main/features.json`
- Schema: `https://raw.githubusercontent.com/WebAssembly/website/refs/heads/main/features.schema.json`
- Committed snapshot in this repo: `plugins-shared/features/features.json` (+ `features.schema.json`)

Consumed by [[entities/wasm-runtime-compatibility]].

## Shape

Four top-level keys; `features` and `browsers` are required by the schema.

```
{ "$schema": …, "features": {…}, "categories": […], "browsers": {…} }
```

- **`features`** — 51 entries as of the September 2026 snapshot, keyed by a stable camelCase
  id (`gc`, `tailCall`, `saturatedFloatToInt`, `exceptionsFinal`, …). Each has `description`,
  `url` (the documentation link Hexana surfaces) and `phase`, which is an integer 1..5 **or
  the string `"inactive"`** — so it cannot be modelled as a number.
- **`categories`** — `Web Browsers`, `Standalone Runtimes`, `Embedded Runtimes`, `Tools`,
  in display order, each with a `queryKey` and an optional `default: true`.
- **`browsers`** — despite the name, **every** runtime kind lives here: browsers, standalone
  runtimes and tools alike, told apart by their `category`. 23 entries in the snapshot.
  `category` is either one string or an array of them with the primary one first (`Owi` is
  `["Tools", "Standalone Runtimes"]`). Hexana itself is listed, as a Tool.

## The `status` union — the part worth knowing

A matrix cell (`browsers[runtime].features[featureId]`) is one of five things, and any of
them may be wrapped as `[status, note]`:

| JSON | Meaning |
|---|---|
| `null` | not applicable to this kind of runtime |
| `true` | supported, version unknown |
| `false` | known unsupported |
| `"flag"` | behind a flag |
| `"133"` | supported from that version on |
| `["flag", "Requires CLI flag …"]` | any of the above, plus a note |

Frequencies in the snapshot: 246 version strings, 175 booleans, 123 nulls, 53 `[str, str]`
pairs, 9 `[bool, str]` pairs. The note usually names the exact flag, which makes it worth
surfacing verbatim rather than summarizing.

A `null` cell is common — warning on it would put a marker on almost every module — which is
why Hexana treats it as `Unknown` and not as a warning.

## Version strings

Not semver. The snapshot spans single-segment browser numbers (`"85"`, `"137"`),
two-segment (`"0.4"`, `"12.5"`, `"16.4"`) and three-segment (`"1.0.24"`, `"0.10.1"`) forms.
Comparison must be segment-wise numeric with missing trailing segments read as 0; a lexical
compare gets `"9" > "133"` wrong.

## HTTP characteristics (verified September 2026)

```
cache-control: max-age=300
etag: "89a19596dd878eb902728ed5957385deae67af9f8e8a30ec7e303aed31b65937"
access-control-allow-origin: *
```

A strong ETag, so a conditional GET (`If-None-Match`) is the cheap way to revalidate — one
round-trip whether or not the file changed, against two for HEAD + GET. CORS is open, so a
browser could fetch it directly; Hexana still fetches host-side in VS Code because the
webview CSP allowlists only `webview.cspSource`, `blob:` and `https://api.osv.dev` for
`connect-src`.
