# Scripted binary providers and tabs

Named binary providers and auto-attached tabs defined in JavaScript, loaded from
`.hexana/providers/*.js` and assigned to files through `.hexana/file_associations.json`
([[entities/file-parser-associations]]). Built on the v2 `hexana` global of the scripting
runtime ([[entities/hexana-scripting-api]]). Decided 2026-11-03.

## API

```js
hexana.defineProvider({
  id: "user:acme-fw",        // file_associations.json value; must start with "user:"
  name: "ACME firmware",     // badge label + parser-menu entry
  description: "…",          // optional
  base: "elf",               // optional built-in parser id, default "generic"
  parse(binary) {            // optional; once per opened file
    return { regions: [{ name, offset, length, tab? }] };   // or the array itself
  },
});

hexana.defineTab({           // = SliceSpec with appliesTo instead of formats
  name: "user:Header",
  appliesTo: ["user:acme-fw", "elf", "*"],   // required, non-empty
  columns, params?, rows(binary, params, paging),
});
```

`hexana.has("providers")` / `has("tabs")` are true.

## Creating a script from the menu

"Create New Provider Script…" (end of the badge menu's Scripts section, in both editors) asks
for a name, writes `.hexana/providers/<slug>.js` from the shared stub
(`providerScriptTemplate` / `providerScript` in `ScriptProviders.kt`), puts
`hexana-scripting.d.ts` (+ `jsconfig.json` when the folder has no JS config) beside it, opens it
and assigns the new provider to the current file. The id is derived from the file name actually
used (`my-format-2.js` → `user:my-format-2`), so a repeated name never clashes. Neither the
`.d.ts` nor `jsconfig.json` ends in `.js`, so neither is loaded as a provider script.

VS Code: the webview owns the stub but the host owns the name prompt, so the webview sends the
stub with `PROVIDER_ID_PLACEHOLDER` / `PROVIDER_NAME_PLACEHOLDER`; the host substitutes
`JSON.stringify` literals, writes, and replies `provider-script-created { providerId }`, which the
webview turns into an association save. `providerScriptSlug` exists twice (Kotlin + TS
`WasmEditorProvider.ts`); keep them identical.

## Demo project

`examples/providers/` is a self-contained project rather than a loose script: the script in its
real home (`.hexana/providers/firmware-image.js`), a 144-byte `firmware-image.bin` in the made-up
format it reads, and `.hexana/file_associations.json` assigning `user:acme-fw` to it. Open *that
folder* as the project — scripts load from the opened project's root `.hexana/`, so the Hexana
repo opened as a whole does not pick it up. `HexanaScriptProvidersTest` runs the script against
the committed image (regions tile the file exactly) and pins the JSON to the serializer's output,
so opening the demo and touching nothing leaves the tree clean. No schema file is committed: it
lists the built-in parser ids and would go stale; the editors write it on the first save.

## Decisions worth remembering

- **A scripted provider is base provider + overlay, not a `BinaryProvider`.** `BinaryProvider`
  is sealed and every tab provider dispatches on `is ElfBinaryProvider` etc., so a wrapper would
  hide the base parser's tabs. The overlay (name, regions, error) travels beside the provider:
  hosts merge its regions into the hex view's ranges (`HexViewer.extraStructureRanges`,
  `BinaryEditorContent.scriptStructureRanges`) and the IntelliJ Structure view adds them as one
  group named after the provider.
- **`defineTab` is a slice with `isTab = true`.** Same paging/columns/params machinery as
  `defineSlice`; only `isTab` slices auto-attach. A plain `defineSlice` in a provider script never
  attaches, or one without `formats` ("all") would land on every binary in the project — that is
  also why `appliesTo` is required for tabs.
- **Association ids**: `ParserRef` = `Builtin(BinaryParser)` | `Scripted("user:…")`. An unknown
  `user:` id is kept (its script may not be loaded yet); the badge says "not loaded" / why. The
  schema's parser def is `anyOf` [built-in enum, loaded providers enum, `^user:.+`].
- **Two evaluations, like the JVM**: a metadata pass against an empty binary (cached by script
  text) feeds the dropdown; per file, only the scripts that matter (owner of the chosen provider,
  or with tabs whose `appliesTo` matches `binaryParserOf(provider).id` / the provider id) are
  evaluated again against the real binary. Top-level code should only register.
- **Trust**: scripts run on their own when a binary opens, so they only run in a trusted project
  (`TrustedProjects.isProjectTrusted`, re-evaluated on `TrustedProjectsListener.TOPIC`) / trusted
  workspace (`vscode.workspace.isTrusted`, `onDidGrantWorkspaceTrust`). The extension declares no
  `untrustedWorkspaces` capability, so VS Code already disables it in Restricted Mode; the check
  keeps that from being load-bearing.
- **Duplicate ids across scripts**: first script by file name wins; later one keeps its other
  registrations and records the conflict in `ProviderScript.error`.

## Where things live

| Layer | Files |
|---|---|
| Model (common) | `plugins-shared/.../scripting/ScriptProviders.kt` (registration, regions, `appliesToFormats`), `ScriptContributions.kt` (`registerProvider`, `SliceRegistration.isTab`), `features/FileAssociations.kt` (`ParserRef`) |
| JVM runtime | `mcp/scripting/GraalScriptContributionCollector.kt` (`defineProvider`/`defineTab`), `HexanaScriptSession.runParse`, `ProviderScripts.kt` (`ProviderScriptCatalog`, `bindProviderScripts`) |
| IntelliJ | `features/ProviderScriptsService.kt` (watch, trust, metadata cache), `HexanaFileEditor` (resolution + bindings, closed on dispose), `ui/BinaryEditorView` (`ProviderTabPanel`), `ui/panel/ScriptCompoundView.ProviderTabContent`, `ui/ParserChoice.kt` ("Scripts" section) |
| VS Code host | `WasmEditorProvider.ts` `readProviderScripts` / `pushProviderScripts`, message `provider-scripts-loaded`, `load-wasm.providerScripts` |
| Browser runtime | `web-ui/.../ProviderScriptRuntime.kt` (JS `hexana`, `loadProviderCatalog`, `bindBrowserProviderScripts`), `ProviderScriptTab.kt` (tab renderer), webview `Main.kt` |
| Docs | `ScriptApiReference.v2Declarations` → regenerate `.d.ts` with `:mcp:scripting:generateHexanaScriptingDts` |

## Traps

- **GraalJS does not run in `idea-plugin` unit tests** (Truffle `Shape` sealed-subclass check
  fails on that test classpath). Host-neutral JVM script logic belongs in `mcp:scripting`, where
  it is testable — which is why `bindProviderScripts` lives there and takes a session opener.
- **Compose rejects `dynamic`** in composable parameters/lambdas (`IrGenerationExtensionException:
  dynamic`). JS values cross into composition inside a holder class (`BoundScriptTab`).
- **`/*` inside a KDoc opens a nested comment** in Kotlin — `.hexana/providers/*.js` in a doc
  comment broke the whole webview module. Spell it without the glob.
- **Browser scripts have no timeout**: they run on the webview's only thread; a script that never
  returns hangs that editor (as a script tab would). JVM calls have the runner's timeout.
- A region past the end of the file is filtered during conversion, not by the builder; count both
  kinds when reporting dropped regions (the first cut did not).
