# APK emulator run

The trivial "Run APK on an existing Android emulator" button, in both IntelliJ and VS Code.

## Where the code lives

- Shared, pure: `plugins-shared/.../shared/AndroidEmulatorRun.kt` — SDK root candidates, tool
  paths (`/`-joined strings), `adb`/`emulator`/`aapt` arguments, output parsing, boot/install
  timeouts, and the user-facing messages, so both hosts behave and word results identically.
  Tested in `AndroidEmulatorRunTest` (JVM + JS).
- IntelliJ: `idea-plugin/.../android/ApkEmulatorRunner.kt` (sync processes in a background
  `Task`, `hexana` balloon notifications, `JBPopup` AVD chooser), button in `ui/FileDescriptionBar.kt`.
- VS Code: `extension-lib/.../AndroidEmulator.kt` (`@JsExport` wrappers, typed in
  `src/extension-lib.d.ts`) → `src/apkRunner.ts` (async `execFile`, `withProgress`,
  `showQuickPick`). `WasmEditorProvider` sets `canRunApk` on `load-wasm` and handles `run-apk`;
  the webview passes it to `VscodeHost(canRunApk)`, and `web-ui`'s `EditorToolbar` renders the
  button when `Host.canRunApk` (default false, so the webapp never shows it).

Orchestration is per host on purpose: a shared synchronous `ProcessRunner` (as in
`WasmComponentComposer`) would block the Node extension host for the whole emulator boot.

## Behaviour

- **Gate.** Top-level, on-disk `.apk` only (not an archive entry), and an SDK root
  (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, then Android Studio defaults on macOS/Linux/Windows)
  with executable `platform-tools/adb` and `emulator/emulator`. File checks only, no processes.
  AVDs are *not* part of the gate: Android Studio installs the emulator package without creating
  an AVD, and gating on `-list-avds` hid the button for exactly that setup.
- **Flow.** On click: reuse the first online `emulator-*` from `adb devices`; else
  `emulator -list-avds` — none → warning pointing to Device Manager, one → boot it, several →
  chooser. Booting starts `emulator -avd <name>` detached and polls `sys.boot_completed` (5 min)
  → `adb install -r -t` → package name via newest build-tools `aapt2 dump packagename`
  (or `aapt dump badging`) → `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`.
- **Not done on purpose.** No SDK/AVD provisioning, no run configuration, no console/logcat,
  no physical devices, no debugger, no ABI compatibility check. A running emulator is always
  reused (no AVD prompt).
