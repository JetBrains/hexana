#!/usr/bin/env bash
set -euo pipefail

# Build the VS Code extension with JavaScript obfuscation.
# Unlike build-and-run-locally.sh this script only produces the .vsix
# artifact — it does NOT install or launch VS Code.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "== Installing npm dependencies =="
cd "$SCRIPT_DIR"
npm install

# The third-party notices read the production npm dependencies from node_modules, so npm install comes first.
echo "== Building Extension Assets and third-party notices =="
"$ROOT_DIR/gradlew" :vscode-plugin:assembleExtensionAssets :vscode-plugin:generateThirdPartyNotices

echo "== Compiling TypeScript =="
npm run compile

echo "== Obfuscating JavaScript bundles =="
npm run obfuscate

echo "== Packaging VSIX =="
npx @vscode/vsce package

PACKAGE_NAME="$(node -p "require('./package.json').name")"
PACKAGE_VERSION="$(node -p "require('./package.json').version")"
VSIX_FILE="${PACKAGE_NAME}-${PACKAGE_VERSION}.vsix"

echo "== Verifying third-party notices in the VSIX =="
node scripts/verify-vsix-notices.js "$VSIX_FILE"

echo "== Done =="
echo "Obfuscated VSIX is at: $SCRIPT_DIR/$VSIX_FILE"
