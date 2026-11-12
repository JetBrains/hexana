# Hexana Scripting Playground

A self-contained sample project for exploring the Hexana scripting API. It includes a
binary corpus, build scripts that reproduce every committed file, and thirteen annotated
scripts covering both the v1 API (runs on master) and the v2 slice API (requires
the `feature/scripting-v2-phase1` branch).

Total committed binary size: about 10.7 KB (6 files; 1 MB budget is not
approached). The `many-functions.wasm` scale module (413 KB at N=15000) is not committed;
generate it locally with `build/gen-many-functions.sh`.

## Toolchain versions

| Tool | Version | Location on this machine |
|------|---------|--------------------------|
| wabt wat2wasm | 1.0.36 | /opt/homebrew/bin/wat2wasm |
| wasi-sdk | 27.0 (clang 20.1.8, wasm32-wasi) | /Users/user/wasi-sdk |
| wasm-tools | 1.245.1 | /Users/user/.cargo/bin/wasm-tools |

The build scripts accept override env vars: `WAT2WASM`, `WASI_SDK`, `WASM_TOOLS`.

## Corpus

| File | Size | Built by | Purpose |
|------|------|----------|---------|
| bin/hello.wasm | 276 bytes | build/build-hello.sh (wabt 1.0.36) | Hand-written WAT: 5 exports, 1 import (env.log), 1 data segment, name section |
| bin/app-debug.wasm | 4703 bytes | build/build-app.sh (wasi-sdk 27.0) | C reactor (3 files), 5 exports, embedded DWARF + name section |
| bin/app-release.wasm | 935 bytes | build/build-app.sh | Stripped version of app-debug: no DWARF, no name section |
| bin/app-release.debug.wasm | 3578 bytes | build/build-app.sh | DWARF sidecar only (llvm-objcopy --only-keep-debug) |
| bin/broken.wasm | 276 bytes | build/gen-broken.sh | hello.wasm with byte 0x14 -> 0xFF: fails wasm-tools validate |
| bin/component.wasm | 1192 bytes | build/build-component.sh (wasm-tools 1.245.1) | Component wrapping app-release.wasm |
| bin/many-functions.wasm | not committed | build/gen-many-functions.sh | 15000 functions; 413593 bytes at N=15000 |

## Regenerating

Run `build/all.sh` from any directory to rebuild all committed binaries:

```sh
bash examples/scripting-playground/build/all.sh
```

For the scale module at N=15000 (not committed; gitignored):

```sh
bash examples/scripting-playground/build/gen-many-functions.sh
# Output: bin/many-functions.wasm  413593 bytes  N=15000
# Measured: 0.8 seconds total (1 s WAT generation + under 1 s wat2wasm compile)
```

Reproducibility: all committed binaries are byte-for-byte reproducible with the toolchain
versions above. The app-* binaries embed `DW_AT_comp_dir = "."` in DWARF because the
build script `cd`s to the project root before invoking clang; running from a different cwd
without the script would change this field. See `bin/MANIFEST.md` for sha256 hashes and
details.

## Walkthrough part 1: v1 scripts (master branch)

These scripts use only the v1 API (`print`, `table`, `row`, `binary.*`, `open()`).
They run on the current master branch.

1. Open `bin/hello.wasm` in the IntelliJ IDEA Hexana editor.
2. Open the Script tab (Add Script). Start from the starter template or paste from
   `hexana-script/scripts/v1-overview.js`.
3. Run `v1-overview.js` against `hello.wasm`. Expected: a structure table (8 regions)
   and a tab list (9 tabs including Data, Exports, Imports, Functions, Custom, Problems,
   Top, Monos, Garbage). Switch to `app-debug.wasm` and rerun: 17 regions, 8 tabs.
4. Switch to `bin/app-debug.wasm`. Run `v1-strings.js`. Expected: a table of 74 strings
   found by the script's byte scan, led by the clang/wasi-sdk DWARF producer strings.
   Note: `binary.strings` is only filled by the native providers (ELF, Mach-O); the Wasm
   provider does not implement the strings capability in v1, so the script scans
   `binary.bytes` itself and says which path it took. On `hello.wasm` it finds 6 strings
   including the data segment greeting "Hello, Hexana!" at 0xbe.
5. Run `v1-open-sibling.js` with `app-debug.wasm` open. Expected: opens
   `app-release.wasm` from the same directory, prints a size comparison table
   (4703 bytes debug vs 935 bytes release, delta 3768 bytes).
6. Run `v1-slow.js` against any binary. Expected: prints the startup line, then hits the
   timeout after 3 seconds (CLI) or 60 seconds (IDE). Diagnostic code: `TIMEOUT`.
7. Run `v1-denied.js` against any binary. Expected: exit code 1, diagnostic code
   `SANDBOX_DENIED`, message "TypeError: Access to host class java.lang.System is not
   allowed or does not exist."

## Walkthrough part 2: v2 scripts (requires branch feature/scripting-v2-phase1)

These scripts use `hexana.defineSlice()`, `binary.functions()`, `binary.imports()`, and
`console.log/debug`. They degrade with a printed message on master.

1. Open `bin/app-debug.wasm`.
2. Add `large-unnamed-functions.js` as a script. Expected: a slice tab named
   "user:Unnamed Functions" with columns funcidx/name/size/offset. Since app-debug.wasm
   has no name section, all defined functions appear as "(unnamed)".
3. Edit the `minSize` param in the toolbar. The stale badge appears. Click Recompute.
   Undo the change; the badge clears.
4. Add `failing-slice.js`. Expected: first page loads. Scroll or trigger load-more.
   A problem row appears with "second page fails deliberately".
5. Add `imports-by-module.js` against `bin/hello.wasm` (which has an `env.log` import).
   Expected: one table row (module `env`, count 1, kind `func`). The `console.debug`
   progress lines appear in the Log sub-tab only, not in the table.

## Walkthrough part 3: scale (optional, requires gen-many-functions.sh)

1. Generate the scale module:
   ```sh
   bash examples/scripting-playground/build/gen-many-functions.sh
   ```
2. Open `bin/many-functions.wasm` in the IDE.
3. Run `large-unnamed-functions.js` (v2 branch). Expected: a paginated table with 7500
   unnamed functions (odd-indexed). Load-more loads subsequent pages. Note scrolling
   latency at 15000 functions total.

## Walkthrough part 4: checks, baselines, and flags (Phase 2A)

These scripts require the Scripting v2 Phase 2A API. They use
`hexana.defineCheck`, `hexana.defineFlags`, `hexana.args`, `hexana.inputs`,
and `hexana.flags`.

### check-demo.js

Runs two checks against `bin/app-debug.wasm`.

1. Raw mode (exit 1 expected because of the ERROR check):

   ```sh
   hexana-cli-script run-script bin/app-debug.wasm \
     --script hexana-script/scripts/check-demo.js \
     --format raw --telemetry-enabled false \
     --baselines-dir .hexana/baselines
   ```

   Expected stdout: empty (no `print()` calls in the script).
   Expected stderr:

   ```
   [log] user:no-trivial-functions: scanned 8 functions
   [log] user:debug-sections-present: found 6 debug section(s)
   ```

   Expected exit code: 1 (the `user:no-trivial-functions` ERROR finding fires on
   `__wasm_call_ctors` at funcidx 0, whose code body is 3 bytes).

2. SARIF mode (spot-check ruleId, level, baselineState):

   ```sh
   hexana-cli-script run-script bin/app-debug.wasm \
     --script hexana-script/scripts/check-demo.js \
     --format sarif --telemetry-enabled false \
     --baselines-dir .hexana/baselines
   ```

   Expected: SARIF JSON on stdout with two results:
   - `ruleId: user:debug-sections-present`, `level: note`, `baselineState: new`
   - `ruleId: user:no-trivial-functions`, `level: error`, `baselineState: new`

   Exit code: 1.

### check-baseline-demo.js

Demonstrates the triage loop: a check that always emits one ERROR finding.

1. First run. No baseline file yet. Exit 1, `baselineState: new`:

   ```sh
   hexana-cli-script run-script bin/app-debug.wasm \
     --script hexana-script/scripts/check-baseline-demo.js \
     --format sarif --telemetry-enabled false \
     --baselines-dir .hexana/baselines
   ```

   Exit code: 1. SARIF shows `baselineState: new`.

2. Baseline the finding. Exit 0, baseline file appears:

   ```sh
   hexana-cli-script run-script bin/app-debug.wasm \
     --script hexana-script/scripts/check-baseline-demo.js \
     --format sarif --telemetry-enabled false \
     --baselines-dir .hexana/baselines --update-baselines
   ```

   Exit code: 0. The file `.hexana/baselines/user:always-fails.json` is created.

3. Re-run without `--update-baselines`. Exit 0, `baselineState: unchanged`:

   ```sh
   hexana-cli-script run-script bin/app-debug.wasm \
     --script hexana-script/scripts/check-baseline-demo.js \
     --format sarif --telemetry-enabled false \
     --baselines-dir .hexana/baselines
   ```

   Exit code: 0. SARIF shows `baselineState: unchanged`.

### flags-demo.js

1. Defaults only (exit 0):

   ```sh
   hexana-cli-script run-script bin/hello.wasm \
     --script hexana-script/scripts/flags-demo.js \
     --format raw --telemetry-enabled false
   ```

   Expected stdout:
   ```
   flags.mode  = auto
   flags.limit = 10
   flags.file  = null
   inputs.length = 1
   ```

   Expected stderr: `[log] hexana.args: ["/path/to/flags-demo.js"]`

2. Flags set, extra input (exit 0):

   ```sh
   hexana-cli-script run-script bin/hello.wasm \
     --script hexana-script/scripts/flags-demo.js \
     --format raw --telemetry-enabled false \
     --input bin/hello.wasm \
     -- --mode fast --limit 42 --file bin/hello.wasm
   ```

   Expected stdout:
   ```
   flags.mode  = fast
   flags.limit = 42
   flags.file  = bin/hello.wasm
   inputs.length = 2
   file.size = 276 bytes  format = Core Wasm
   ```

3. Undeclared flag (exit 2 expected):

   ```sh
   hexana-cli-script run-script bin/hello.wasm \
     --script hexana-script/scripts/flags-demo.js \
     --format raw --telemetry-enabled false \
     -- --undeclared-flag value
   ```

   Expected exit code: 2. Stderr: `Undeclared flag: undeclared-flag`.

## Broken binary

Open `bin/broken.wasm` in the IDE. Hexana must parse it without crashing. The Problems
tab should show an error row. The byte at offset 0x14 (decimal 20) is 0xFF, which is an
invalid valtype in the Type section. `wasm-tools validate` rejects it with "function
params size is out of bounds (at offset 0x14)".

## Sidecar pair

Open `bin/app-release.wasm` with `bin/app-release.debug.wasm` in the same directory.
Hexana's sidecar discovery finds the `.debug.wasm` file next to the release binary and
attaches the DWARF data. The DWARF tab appears with function source-location information
even though the release binary itself has no debug sections.

Note: `wasm-tools validate` rejects `app-release.debug.wasm` because it has no function
or type sections (it is debug-only). This is expected; `wasm-tools print` parses it
correctly.

## QA checklist

- [ ] `build/all.sh` completes without errors
- [ ] `wasm-tools validate` passes for hello.wasm, app-debug.wasm, app-release.wasm, component.wasm
- [ ] `wasm-tools validate` fails for broken.wasm
- [ ] `wasm-tools validate` fails for app-release.debug.wasm (expected: no function sections)
- [ ] `wasm-tools print` succeeds for app-release.debug.wasm
- [ ] v1-overview.js runs on hello.wasm via CLI: exit 0, 8 structure regions, 9 tabs
- [ ] v1-overview.js runs on app-debug.wasm via CLI: exit 0, 17 regions, 8 tabs
- [ ] v1-strings.js runs on app-debug.wasm via CLI: exit 0, 74 strings via byte scan
- [ ] v1-open-sibling.js on app-debug.wasm via CLI: exit 0, prints size comparison (4703 vs 935 bytes)
- [ ] v1-slow.js via CLI: exit 1, diagnostic code TIMEOUT within 3 seconds
- [ ] v1-denied.js via CLI: exit 1, diagnostic code SANDBOX_DENIED
- [ ] gen-many-functions.sh at N=15000: completes in under 5 seconds, output 413593 bytes
- [ ] git status shows only new files under examples/scripting-playground/, no modified tracked files
- [ ] bin/many-functions.wasm does NOT appear in git status (gitignored)
- [ ] v2 scripts on master each print "This script needs the Scripting v2 Phase 1 branch."
- [ ] async-trap.js on master: prints "sync output visible", exits cleanly (no diagnostic)
- [ ] Open broken.wasm in IDE: no crash, Problems tab shows error
- [ ] Open app-release.wasm with sidecar next to it: DWARF info visible in IDE
- [ ] check-demo.js on app-debug.wasm (raw): exit 1, stderr has [log] lines for both checks
- [ ] check-demo.js on app-debug.wasm (sarif): SARIF stdout with ruleId/level/baselineState present
- [ ] check-baseline-demo.js step 1 (no baselines): exit 1, baselineState new in SARIF
- [ ] check-baseline-demo.js step 2 (--update-baselines): exit 0, .hexana/baselines/user:always-fails.json created
- [ ] check-baseline-demo.js step 3 (re-run): exit 0, baselineState unchanged in SARIF
- [ ] flags-demo.js defaults only: exit 0, mode=auto limit=10 file=null inputs.length=1
- [ ] flags-demo.js with --mode fast --limit 42 --file bin/hello.wasm --input bin/hello.wasm: exit 0, file.size=276
- [ ] flags-demo.js with undeclared flag: exit 2, stderr contains "Undeclared flag"
- [ ] .hexana/ appears in .gitignore (baselines dir is not tracked)

Estimated time: under 15 minutes for the v1 and CLI portions. v2 walkthrough requires
the Scripting v2 Phase 1 API. Phase 2A walkthrough requires
the Phase 2A API. Phase 2B (IDE run configurations) requires
the Phase 2B API.

## Walkthrough part 5: run configurations (IDE)

Requires the Scripting v2 Phase 2B API and a running IDE sandbox (`./gradlew runIde`).

### Creating a run configuration from a script file

1. Open IntelliJ IDEA with the Hexana plugin loaded (via `./gradlew runIde` or from
   the installed plugin).
2. Open `examples/scripting-playground/` as a project (or add it as a content root).
3. In the Project tree, right-click `hexana-script/scripts/check-demo.js` and choose
   **Run "check-demo"**. A Hexana Script run configuration is created automatically
   with the script path pre-filled.
4. The Run tool window opens. The first run has no input binary, so the console prints
   a usage diagnostic and exits.

### Configuring inputs

1. Open **Run > Edit Configurations**.
2. Select the "check-demo" Hexana Script configuration.
3. In the **Input binaries (first = primary)** list, click **+** and choose
   `examples/scripting-playground/bin/app-debug.wasm`.
4. Optionally add `bin/hello.wasm` as a second input with **+** again.
5. The **Flags** section should show no flags (check-demo.js declares none). The table
   remains empty.
6. Click **Run**. The console prints:

   ```
   [log] user:no-trivial-functions: scanned 8 functions
   [log] user:debug-sections-present: found 6 debug section(s)
   ```

   Followed by two finding lines. Exit code 1 (the ERROR finding fires).

7. The finding for `user:no-trivial-functions` contains a clickable function link.
   Click it to navigate to the function in the WAT (virtualized) tab.

### Flags table (flags-demo.js)

1. Create a new Hexana Script run configuration manually via **Run > Edit Configurations > +**.
2. Set the script path to `hexana-script/scripts/flags-demo.js`.
3. After a brief pause, the **Flags** table populates with three rows:
   `mode` (values: fast/slow/auto, default auto), `limit` (int, default 10),
   `file` (path, no default).
4. Set `mode` to `fast` and `limit` to `42` in the Value column.
5. Add `bin/hello.wasm` as the primary input.
6. Click **Copy CLI Command** and verify the clipboard contains:

   ```
   hexana-cli-script run-script bin/hello.wasm --script hexana-script/scripts/flags-demo.js -- --mode fast --limit 42
   ```

7. Click **Run**. Expected stdout:

   ```
   flags.mode  = fast
   flags.limit = 42
   flags.file  = null
   inputs.length = 1
   ```

### QA checklist additions (Phase 2B)

- [ ] Right-click check-demo.js: "Run check-demo" creates a run configuration automatically
- [ ] Run configuration dialog opens without errors; script path is pre-filled
- [ ] Adding app-debug.wasm as primary input and running: exit code 1, console shows [log] lines
- [ ] Clicking the funcidx finding link navigates to the WAT (virtualized) tab at the correct function
- [ ] flags-demo.js: Flags table populates with mode/limit/file rows after script path is entered
- [ ] Editing limit=42 in the table, running: stdout shows "flags.limit = 42"
- [ ] Copy CLI Command produces a valid hexana-cli-script invocation with the -- separator
- [ ] No input binary: run still starts, engine receives the stub wasm, console may show errors or empty findings

## Walkthrough part 6: test suites (Phase 3A)

Requires the Scripting v2 Phase 3A API and a built CLI:

```sh
./gradlew :cli:hexana-cli-script:installDist
```

Binary: `cli/hexana-cli-script/build/install/hexana-cli-script/bin/hexana-cli-script`.

All commands below run from `examples/scripting-playground/`.

### Step 1: run the suite, read the report

```sh
hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --telemetry-enabled false
```

Observed stdout:

```
Suite: app.test.js
[PASS] app-debug.wasm shape > size is exactly 4703 bytes (2 ms)
[PASS] app-debug.wasm shape > format is wasm (0 ms)
[PASS] app-debug.wasm shape > name is app-debug.wasm (0 ms)
[PASS] app-debug.wasm shape > exports > exactly 5 exports (2 ms)
[PASS] app-debug.wasm shape > exports > export names match MANIFEST (16 ms)
[PASS] app-debug.wasm shape > exports > memory export has kind memory (1 ms)
[PASS] app-debug.wasm shape > exports > func exports have kind func (4 ms)
[PASS] imports > no imports (wasi-libc is statically linked) (0 ms)
[PASS] functions > total function count is 8 (3 ms)
[PASS] functions > all functions are code (no imports) (1 ms)
[PASS] functions > functions have non-negative funcidx (0 ms)
[PASS] async crypto > SHA-256 digest of file header completes and has 32 bytes (34 ms)
[PASS] async crypto > setTimeout resolves in async test (1 ms)

13 passed, 0 failed, 0 skipped (71 ms)
```

Observed stderr: `[log] SHA-256(header[:8]) = 93a44bbb96c75121...`

Exit 0.

Report structure: `Suite: <filename>`, then `[PASS]/<FAIL>/<SKIP>]
describe-path > test-name (N ms)`, summary at the end. Nested
`describe` blocks join with ` > `. The suite uses `import { assertEquals }
from "./helpers/expect.mjs"` for assertions and an async test that runs
`crypto.subtle.digest` (SHA-256 of the Wasm magic header).

### Step 2: produce a JUnit XML report

```sh
hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --report junit /tmp/suite.xml \
  --telemetry-enabled false
```

Exit 0. The report is written to `/tmp/suite.xml`. First lines:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="app.test.js" tests="13" failures="0" skipped="0" time="...">
    <testcase name="size is exactly 4703 bytes" classname="app-debug.wasm shape" .../>
    ...
```

Each nested `describe` becomes the `classname` of its child `testcase` elements.
Failures get a `<failure message="...">` element.

### Step 3: flip the deliberate failure on, watch exit 1

The file `hexana-script/tests/deliberate-failure.test.js.disabled` contains one
always-failing test. Rename it temporarily:

```sh
mv hexana-script/tests/deliberate-failure.test.js.disabled \
   hexana-script/tests/deliberate-failure.test.js

hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --telemetry-enabled false
```

Observed: the summary shows `14 passed, 1 failed, 0 skipped`, exit 1. The
failure line reads:

```
[FAIL] deliberate > this test always fails (8 ms)
       Error: deliberate failure: rename to .disabled after the demo
```

Rename back to restore the green state:

```sh
mv hexana-script/tests/deliberate-failure.test.js \
   hexana-script/tests/deliberate-failure.test.js.disabled
```

Directory run now discovers only `app.test.js` and exits 0 again.

### Step 4: seed determinism

The `--seed <hex>` flag pins the DRBG used by `crypto.getRandomValues` and
`crypto.randomUUID`. Two runs with the same seed produce byte-identical output
for any randomness consumed in the suite. A different seed diverges.

```sh
hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --seed aabbccdd \
  --telemetry-enabled false
```

Run the same command twice. The stderr lines (console.log output) are identical.
Change `--seed` to `--seed 00112233` and the random bytes change.

The default seed (no `--seed`) is derived from the suite source text and the
artifact content, so it stays stable as long as neither changes.

### QA checklist additions (Phase 3A)

- [ ] `hexana-cli-script test hexana-script/tests/ --artifact app=bin/app-debug.wasm --telemetry-enabled false`: exit 0, 13 passed
- [ ] stdout shows `Suite: app.test.js`, nested describes joined with ` > `
- [ ] stderr shows `[log] SHA-256(header[:8]) = 93a44bbb96c75121...`
- [ ] `--report junit /tmp/suite.xml`: exit 0, XML file present, `tests="13" failures="0"`
- [ ] rename deliberate-failure.test.js.disabled to .test.js, rerun directory: exit 1, `[FAIL] deliberate > this test always fails`
- [ ] rename back to .disabled, rerun: exit 0
- [ ] `--seed aabbccdd` twice: stderr rng line identical both runs
- [ ] `--seed 00112233` (different seed): rng line differs from aabbccdd run
- [ ] `node_modules/` absent from git status (covered by .gitignore)

## Walkthrough part 7: IDE suite runner (Phase 3B)

Requires the Scripting v2 Phase 3B API and a running IDE sandbox (`./gradlew runIde`).
GraalJS cannot initialize inside the idea-plugin test harness, so IDE outputs below are
stated as expected values derived from the pinned CLI behavior and unit tests rather
than observed IDE outputs.

### Step 1: create a suite run configuration

1. Open IntelliJ IDEA with the Hexana plugin loaded.
2. Open `examples/scripting-playground/` as a project.
3. In the Project tree, right-click `hexana-script/tests/app.test.js`. When the JavaScript
   plugin is enabled, Run gutter icons appear on each `test()` and `describe()` line; the
   context menu also works regardless of that plugin.
4. Choose **Run "app"** from the context menu. A "Hexana Suite" run configuration
   is created automatically with the suite path pre-filled. The configuration name is
   the file name with `.test.js` removed (so `app.test.js` becomes `app`).

   The configuration type is named **Hexana Suite** (source:
   `HexanaSuiteRunConfigurationType.kt`).

### Step 2: fill the artifact table

1. The run configuration editor opens (or open it via **Run > Edit Configurations**).
2. The editor runs a collection pass on `app.test.js` off the EDT. The status label
   (field "Artifacts:") shows **1 artifact declaration(s)** once the pass completes
   (source: `HexanaSuiteRunSettingsEditor.kt`).
3. The artifact table has four columns: **Role**, **Formats**, **Optional**, **Path**
   (source: `HexanaSuiteRunSettingsEditor.kt`). The table shows one row with
   Role = `app` and Formats = `wasm`.
4. Click the **Path** cell for the `app` row and enter the absolute path to
   `bin/app-debug.wasm`, or use the browse button.
5. For a directory suite path the editor cannot run the collection pass and falls back
   to a free-form text area (source: `HexanaSuiteRunSettingsEditor.kt`). In
   that mode, enter `app=bin/app-debug.wasm` as one line per artifact.

   Expected after entering the path: the suite field shows
   `hexana-script/tests/app.test.js`, the artifact table Path cell shows the
   `app-debug.wasm` path.

### Step 3: run and inspect the test tree

1. Click **Run**. The IDE launches the suite runner.
2. The Run tool window shows a test tree. Expected structure:

   ```
   app.test.js
     app-debug.wasm shape
       size is exactly 4703 bytes
       format is wasm
       name is app-debug.wasm
       exports
         exactly 5 exports
         export names match MANIFEST
         memory export has kind memory
         func exports have kind func
     imports
       no imports (wasi-libc is statically linked)
     functions
       total function count is 8
       all functions are code (no imports)
       functions have non-negative funcidx
     async crypto
       SHA-256 digest of file header completes and has 32 bytes
       setTimeout resolves in async test
   ```

   13 tests, all green. Nested `describe` blocks appear as tree nodes. Durations
   are shown per test.
3. Click any test node. Expected: the editor navigates to the corresponding `test()`
   call in `app.test.js` at the matching line.

### Step 4: introduce a failure

Two options:

**Option A:** rename the disabled fixture.

```sh
mv hexana-script/tests/deliberate-failure.test.js.disabled \
   hexana-script/tests/deliberate-failure.test.js
```

Point the suite field at `hexana-script/tests/` (the directory) and run.

**Option B:** edit `app.test.js` to break one assertion, then restore it after.

After running with the failure enabled:

Expected test tree: 13 green tests from `app.test.js`, plus from
`deliberate-failure.test.js`:

```
deliberate
  this test always fails   [FAIL]
  this test always passes  [PASS]
```

The detail pane for the failing test shows:

```
Error: deliberate failure: rename to .disabled after the demo
```

The stack line points to `deliberate-failure.test.js:18`.

### Step 5: rerun failed

1. In the Run tool window, click the **Rerun Failed Tests** button.
2. Expected: only the one failing test executes again. The 13 passing tests from
   `app.test.js` show as skipped (not re-executed). The failure message is the same.

### Step 6: copy CLI command

1. In the run configuration editor, click **Copy CLI Command**
   (source: `HexanaSuiteRunSettingsEditor.kt`).
2. The clipboard contains a command of the form:

   ```sh
   hexana-cli-script test hexana-script/tests/ --artifact app=/absolute/path/to/bin/app-debug.wasm
   ```

   `--telemetry-enabled false` is not included by the builder; append it manually
   for clean runs that should not send telemetry.

3. Paste into a terminal (from `examples/scripting-playground/`) and run. Observed
   output with `deliberate-failure.test.js` present:

   ```
   Suite: app.test.js
   [PASS] app-debug.wasm shape > size is exactly 4703 bytes (2 ms)
   ...
   13 passed, 0 failed, 0 skipped (87 ms)

   Suite: deliberate-failure.test.js
   [FAIL] deliberate > this test always fails (10 ms)
          Error: deliberate failure: rename to .disabled after the demo
   [PASS] deliberate > this test always passes (0 ms)

   14 passed, 1 failed, 0 skipped (78 ms)
   ```

   Exit code: 1. (CLI run verified on this branch; exit 1 confirmed.)

### Step 7: restore the passing state

```sh
mv hexana-script/tests/deliberate-failure.test.js \
   hexana-script/tests/deliberate-failure.test.js.disabled
```

Rerun the CLI or IDE configuration. Expected: `app.test.js` only, 13 passed, exit 0.

### QA checklist additions (Phase 3B)

- [ ] Right-click app.test.js: "Run app" creates a "Hexana Suite" run configuration
- [ ] Artifact table populates with one row: Role=app, Formats=wasm, after collection pass
- [ ] Status label shows "1 artifact declaration(s)"
- [ ] Entering app-debug.wasm path in the Path column and running: 13 green tests, tree matches describe nesting
- [ ] Clicking a test node navigates to its `test()` line in app.test.js
- [ ] Directory suite path: editor switches to free-form text area (no table)
- [ ] Rename deliberate-failure.test.js.disabled to .test.js, run directory config: 1 failure shown, detail pane shows error message and stack line
- [ ] Rerun Failed: only the 1 failing test re-executes, others show as skipped
- [ ] Copy CLI Command button produces a valid hexana-cli-script invocation
- [ ] Pasting CLI command in terminal with deliberate failure present: exit 1, "[FAIL] deliberate > this test always fails"
- [ ] Rename back to .disabled, rerun: exit 0, 13 passed
- [ ] "Run Suites for This Binary" action visible in binary editor when app-debug.wasm is open (source: HexanaRunSuitesForBinaryAction.kt line 37)
