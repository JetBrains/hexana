#!/usr/bin/env bash
set -euo pipefail

# Build the VS Code extension with JavaScript obfuscation.
# Unlike build-and-run-locally.sh this script only produces the .vsix
# artifact — it does NOT install or launch VS Code.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "== Building Extension Lib =="
"$ROOT_DIR/gradlew" :vscode-plugin:extension-lib:jsBrowserProductionWebpack

echo "== Building WebView =="
"$ROOT_DIR/gradlew" :vscode-plugin:webview:jsBrowserProductionWebpack

echo "== Installing npm dependencies =="
cd "$SCRIPT_DIR"
npm install

echo "== Compiling TypeScript =="
npm run compile

echo "== Obfuscating JavaScript bundles =="
npm run obfuscate

echo "== Packaging VSIX =="
npx @vscode/vsce package

echo "== Done =="
echo "Obfuscated VSIX is at: $SCRIPT_DIR/hexana-wasm-0.0.1.vsix"
