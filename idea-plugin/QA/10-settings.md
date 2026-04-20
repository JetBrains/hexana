# 10 — Plugin Settings

## Prerequisites
- Hexana plugin installed

## Test Cases

### 10.1 Settings page accessible
**Steps:** Open Settings -> Tools -> Hexana.
**Expected:** Settings page appears with fields: Wasm-tools path, Use built-in wasm tools, Show offsets, Send anonymous usage analytics.

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
**Expected:** No analytics events are sent. (Verify via network monitor or log.)

### 10.9 Analytics opt-in
**Steps:** Check "Send anonymous usage analytics". Perform actions.
**Expected:** PostHog events are sent with `ide_type`, `ide_build`, and `distinct_id` properties.

### 10.10 Settings persistence
**Steps:** Change settings. Close and reopen the IDE.
**Expected:** All settings are preserved across IDE restarts (stored in `hexana.xml`).
