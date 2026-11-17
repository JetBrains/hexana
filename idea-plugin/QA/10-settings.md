# 10 — Plugin Settings

## Prerequisites
- Hexana plugin installed

## Test Cases

### 10.1 Settings page accessible
**Steps:** Open Settings -> Tools -> Hexana.
**Expected:** Settings page appears with fields: Wasm-tools path, LLVM objdump path, Use built-in wasm tools, Show offsets, Send anonymous usage analytics, SBOM vulnerability options, feedback prompt, and What's New update prompt.

### 10.2 Wasm-tools path — custom
**Steps:** Set a custom path to the `wasm-tools` binary via the file browser. Open a `.wasm` file, click Wat tab.
**Expected:** WAT conversion uses the specified binary.

### 10.3 Wasm-tools path — empty (use PATH)
**Steps:** Clear the wasm-tools path field. Open a `.wasm`, click Wat tab.
**Expected:** `wasm-tools` is resolved from the system PATH.

### 10.4 Use built-in wasm tools — enabled
**Steps:** Check the "Use built-in wasm tools" checkbox. Open a `.wasm`, click Wat tab.
**Expected:** WAT conversion succeeds using the built-in GraalWasm converter. No external binary needed.

### 10.5 Use built-in wasm tools — disabled
**Steps:** Uncheck the checkbox. Ensure `wasm-tools` is available. Convert a `.wasm`.
**Expected:** External `wasm-tools` binary is used for conversion.

### 10.6 Show offsets — enabled
**Steps:** Check "Show offsets". Open or reopen a `.wasm` file, switch to Wat tab.
**Expected:** Hex offset annotations appear in the WAT gutter.

### 10.7 Show offsets — disabled
**Steps:** Uncheck "Show offsets". Reopen the WAT view.
**Expected:** No offset annotations in the gutter.

### 10.8 Analytics opt-out
**Steps:** Uncheck "Send anonymous usage analytics". Perform various actions (open files, switch tabs).
**Expected:** No analytics events are sent. (Verify via network monitor or log.) The opt-out takes effect immediately — no IDE restart needed.

### 10.9 Analytics opt-in
**Steps:** Check "Send anonymous usage analytics". Perform actions.
**Expected:** PostHog events are sent with `surface`, `ide_type`, `ide_build`, `plugin_version`, and `distinct_id` properties. `plugin_version` is the Hexana plugin's version, not the IDE's. Re-enabling works without an IDE restart.

### 10.9a Analytics opt-out leaves no client running
**Steps:** Uncheck "Send anonymous usage analytics", restart the IDE, and take a thread dump (Help | Diagnostic Tools | Analyze Threads, or `jstack`).
**Expected:** No PostHog flush timer thread exists — with analytics off the SDK is never allocated.

### 10.9b Events survive an immediate quit
**Steps:** With analytics enabled, start the IDE, open exactly one binary, and quit within a few seconds (well under the 30s flush interval).
**Expected:** The `binary_opened` event still reaches PostHog — the IDE flushes on exit rather than dropping the queue. Quitting is not visibly delayed (the flush is capped at 2s).

### 10.10 Settings persistence
**Steps:** Change settings. Close and reopen the IDE.
**Expected:** All settings are preserved across IDE restarts (stored in `hexana.xml`).

### 10.11 What's New update prompt — enabled
**Steps:** Check "Show What's New after Hexana updates". Start the IDE with a Hexana version whose major/minor version differs from the stored `lastShownWhatsNewVersion` in `hexana.xml`.
**Expected:** A "Hexana What's New <version>" editor tab opens once and the stored version is updated. The tab renders the bundled rich HTML What's New page with formatting and images; if the IDE browser component is unavailable, a fallback panel offers to open release notes in the external browser.

### 10.12 What's New update prompt — disabled
**Steps:** Uncheck "Show What's New after Hexana updates". Start the IDE after changing the Hexana major/minor version.
**Expected:** No What's New tab opens automatically.
