# UI-driver `@Remote` refs and IDE classloaders

How the IDE-side Driver resolves `@Remote` classes, and why `ui-driver-tests` declares its own
refs for platform execution classes.

## Resolution rule

The IDE-side `com.intellij.driver.impl.Invoker.getClassLoader(RemoteCall)` (performanceTesting
plugin, `intellij.driver.impl.jar`) picks the classloader from the ref's `plugin`:

- no plugin id → the Invoker's own classloader (the performanceTesting plugin);
- `pluginId` → that plugin's classloader;
- `pluginId/moduleName` → that content module's classloader.

A class the chosen loader cannot see fails as
`No such class '<fqn>' in plugin null` (or with the plugin id).

## RunContentManager failure (2026-10)

The driver-sdk `getRunContentManager` refs (`RunContentManager`, `RunContentDescriptor`,
`ProcessHandler`) carry no plugin id. On 2026.2/2026.3 (263.5701) `intellij.platform.execution`
is still on the boot classpath, so they resolve. On newer master IDE builds on TeamCity they did
not, and every run/debug test that observed the Run tool window (`HexanaRunConfigurationDriverTest`:
`runWasm`, `debugWasm`, `runNodeJsWasm`, `debugNodeJsWasm`) failed before launching, because
`selectedRunConfigurationContent()` runs first. DWARF breakpoint tests were unaffected (they go
through `WasmRunConfigTestHelper` only).

Fix: `ui-driver-tests/.../framework/HexanaRunConfigurationObservation.kt` declares
`HexanaRunContentManagerRef` / `HexanaRunContentDescriptorRef` / `HexanaProcessHandlerRef` with
`plugin = "org.jetbrains.hexana"`; the Hexana plugin depends on the execution module, so its
classloader always sees them.

## Rule

For platform classes outside the IDE core (execution, xdebugger, other product modules), declare
the `@Remote` ref with `plugin = "org.jetbrains.hexana"` instead of using a plugin-less driver-sdk
ref.
