---
title: Hexana for VS Code — Settings (0.0.2)
description: The two VS Code settings the Hexana extension contributes, plus the telemetry consent model.
version: "0.0.2"
---

# Settings

Hexana for VS Code 0.0.2 contributes two settings, both under `Settings → Extensions → Hexana` (`hexana.*` in `settings.json`).

## `hexana.enableStatistics`

| Field | Value |
|---|---|
| **Type** | `boolean` |
| **Default** | `true` |

Toggle anonymous usage statistics collection. When `false`, no analytics events are sent **regardless of the global VS Code telemetry setting**.

The setting is re-read live — toggling it at runtime takes effect immediately. Hexana flushes the current analytics client, shuts it down, and reinitialises with the new consent state. No restart needed.

## `hexana.wasmtimePath`

| Field | Value |
|---|---|
| **Type** | `string` |
| **Default** | `""` (empty — Hexana looks for `wasmtime` on `PATH`) |

Absolute path to the Wasmtime executable. Use this when:

- You have multiple Wasmtime installations and want to pin Hexana to a specific one.
- Wasmtime is installed in a non-standard location not on your shell `PATH`.
- You are testing a pre-release Wasmtime build.

Hexana does not validate the path at save time — if the path is wrong, you'll see the failure when you next click **Run**.

There is no analogous setting for `wasm-tools` or `wac` (used in Component Model composition); both must be on `PATH`.

## Telemetry — the full consent model

Hexana's analytics events fire only when **all** of these are true:

1. The user has not opted out of VS Code's global telemetry (`vscode.env.isTelemetryEnabled`).
2. The Hexana-specific `hexana.enableStatistics` setting is `true`.
3. The extension has successfully initialised its analytics client (which requires the Kotlin/JS `extension-lib` to be loaded — if it failed to load for any reason, analytics is silently disabled).

On first activation, Hexana shows a **notification** describing data collection, with a link to the JetBrains privacy notice at `https://www.jetbrains.com/legal/docs/privacy/privacy/`. This is required disclosure for anonymous analytics under JetBrains' privacy policy.

You can opt out at any time by:

- Disabling Hexana's analytics specifically: `Settings → Extensions → Hexana → Enable Statistics` (uncheck), or set `"hexana.enableStatistics": false` in `settings.json`.
- Disabling VS Code's telemetry globally: `Settings → Application → Telemetry → Telemetry Level → off`.

Either one alone is sufficient.

### What is collected

The schema is defined in `extension-lib/src/jsMain/kotlin/org/jetbrains/hexana/vscode/lib/Analytics.kt`. Inspect the source for the authoritative list. Hexana follows the same event taxonomy as the JetBrains plugin — primarily `wasm.file.opened` events, with no source contents or PII in the payload.

### Storage

A persistent anonymous ID is stored under VS Code's `globalStorage` at `<globalStorage>/hexana-analytics-id`. This ID is regenerated only if the file is manually deleted; uninstalling the extension does **not** remove it (VS Code retains globalStorage across installs).

## Configuring file associations

Hexana sets a `configurationDefault` to associate `*.wasm` with the Hexana custom editor:

```json
"workbench.editorAssociations": {
  "*.wasm": "hexana.wasmEditor"
}
```

This is applied automatically on install. If you prefer to use a different default editor for `.wasm` (e.g. VS Code's built-in hex editor), override in your `settings.json`:

```json
"workbench.editorAssociations": {
  "*.wasm": "default"
}
```

You can still open files in Hexana on demand via **Reopen Editor With…** in the editor tab's context menu.

## See also

- [`features.md`](features.md), [`run-support.md`](run-support.md), [`troubleshooting.md`](troubleshooting.md).
- [JetBrains privacy notice](https://www.jetbrains.com/legal/docs/privacy/privacy/).
