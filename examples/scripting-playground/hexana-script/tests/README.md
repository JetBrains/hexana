# Scripting Playground: Test Suites

Test suites for the `hexana-cli-script test` command. Each `.test.js` file is
an ESM module that registers cases with `describe`/`test` and asserts with
helpers imported from `./helpers/expect.mjs`.

## Layout

```
tests/
  app.test.js                         main suite for bin/app-debug.wasm
  helpers/
    expect.mjs                        assertEquals / assertTrue / assertNull
  deliberate-failure.test.js.disabled NOT discovered by default; renamed for the failure demo
```

The `*.disabled` file is intentionally excluded from the default directory run.
Rename it to `deliberate-failure.test.js` when demonstrating a failure, then
rename it back.

## Run the suite

From `examples/scripting-playground/`:

```sh
hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --telemetry-enabled false
```

Expected: `13 passed, 0 failed, 0 skipped`, exit 0.

## Produce a JUnit XML report

```sh
hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --report junit /tmp/suite.xml \
  --telemetry-enabled false
```

## Optional: external assertion library via --lib-root

To use a library such as `chai` or `assert` as a bare `import`:

1. Create a `node_modules` directory next to (or above) the suite files and
   install the library there. Do NOT commit `node_modules/`.
2. Pass the directory containing `node_modules/` as `--lib-root`:

```sh
npm install --prefix /tmp/suite-libs chai
hexana-cli-script test hexana-script/tests/ \
  --artifact app=bin/app-debug.wasm \
  --lib-root /tmp/suite-libs \
  --telemetry-enabled false
```

Inside a suite file you can then write:

```js
import { assert } from "chai";
```

No `package.json` needs to live in the playground directory itself. The
playground `.gitignore` already excludes `node_modules/`; do not commit it.

## More walkthrough steps

Part 6 of `examples/scripting-playground/README.md` covers the full suite
walkthrough: running, reading the report, producing JUnit XML, flipping the
deliberate failure, and the seed determinism demo.
