#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "== Building Extension Assets =="
"$ROOT_DIR/gradlew" :vscode-plugin:assembleExtensionAssets

echo "== Building plugin shim =="
cd "$SCRIPT_DIR"
npm install
npm run compile

echo "== Packaging =="
npx @vscode/vsce package --allow-missing-repository

VSIX_VERSION=$(node -p "require('./package.json').version")
VSIX_FILE="hexana-wasm-${VSIX_VERSION}.vsix"

echo "== Registering the plugin in VSCode =="
if ! which code >/dev/null 2>&1
then
    if ! which code-insiders  >/dev/null 2>&1
    then
      echo "Please register vscode/vscode-insiders in PATH (Help -> Show All Commands -> path)"
    else
      code-insiders --install-extension "$VSIX_FILE"
      cd ~
      code-insiders &
    fi
else
  code --install-extension "$VSIX_FILE"
  cd ~
  code &
fi
