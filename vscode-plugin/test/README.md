# Hexana VS Code Extension – UI Acceptance Tests

Selenium-WebDriver–based acceptance tests for the Hexana VS Code extension,
powered by [vscode-extension-tester](https://github.com/redhat-developer/vscode-extension-tester).

## What These Tests Verify

- Opening a `.wasm` file activates the Hexana custom editor.
- The hex viewer renders binary content with correct byte values.
- The tab bar shows expected tabs (Summary, Exports, Imports, etc.).
- Clicking a hex byte updates the selection status bar with offset info.
- Switching to the Exports tab displays export rows for the `.wasm` module.
- Clicking an export row highlights the corresponding bytes in the hex view.
- Updating the Hexana MCP server from a mocked GitHub Release downloads,
  extracts, and launches the VS Code MCP server distribution when the MCP ZIP
  has been built.

## Prerequisites

1. **Node.js** ≥ 18.
2. A pre-built extension `.vsix` at `../hexana-wasm-<version>.vsix` (version is read from `package.json`).
   Build it with:
   ```bash
   cd vscode-plugin && ./build-and-run-locally.sh
   ```
   or:
   ```bash
   ./gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack
   ./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack
   cd vscode-plugin && npm install && npm run compile
   npx @vscode/vsce package --allow-missing-repository
   ```

## Running the Tests

```bash
cd vscode-plugin/test
npm install
npm test
```

This will:
1. Compile the TypeScript test sources.
2. Download a compatible VS Code version and ChromeDriver.
3. Install the Hexana `.vsix` into the test VS Code instance.
4. Open a `.wasm` fixture file and run the Mocha test suite.

The MCP update test runs only when
`../../mcp/vscode-server/build/distributions/hexana-vscode-mcp-server-<version>.zip`
exists. Build it first with:

```bash
./gradlew :mcp:vscode-server:distZip
```

On TeamCity, the Mocha run uses the checked-in TeamCity reporter from
`src/teamcity-reporter.ts`. It emits `testSuiteStarted`, `testStarted`,
`testFailed`, and `testFinished` service messages so the build results page
shows passed and failed VS Code tests instead of only the command-line step
status.

## Test Fixtures

- `fixtures/test-exports.wasm` — a minimal 50-byte WebAssembly module
  with two exports (`memory` and `add`) used as the test subject.

## Project Structure

```
test/
├── .gitignore
├── .mocharc.js            # Mocha configuration (timeout, slow threshold)
├── package.json           # Test-specific dependencies
├── README.md              # This file
├── settings.json          # VS Code settings used during test runs
├── tsconfig.json          # TypeScript config for test sources
├── fixtures/
│   └── test-exports.wasm  # Wasm fixture with exports
└── src/
    └── ui-test/
        ├── hexana.test.ts # Main editor acceptance test suite
        └── mcp.test.ts    # MCP update/download acceptance test
```

## Notes

- These are **expensive integration tests** that launch a full VS Code
  instance. They are not part of the normal development build cycle.
- The test run creates temporary directories (`test-resources/`,
  `.test-extensions/`) for the downloaded VS Code and ChromeDriver binaries.
  These are excluded via `.gitignore`.
- On macOS, ChromeDriver may require you to allow it in System Settings →
  Privacy & Security before first run.
