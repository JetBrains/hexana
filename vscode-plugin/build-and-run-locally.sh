#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
EXTTEST_STORAGE_DIR="$SCRIPT_DIR/test/test-resources/local-dev-vscode"
EXTTEST_USER_DATA_DIR="$EXTTEST_STORAGE_DIR/user-data"
EXTTEST_EXTENSIONS_DIR="$EXTTEST_STORAGE_DIR/extensions"
EXTTEST_CLI="$SCRIPT_DIR/test/node_modules/vscode-extension-tester/out/cli.js"
EXTTEST_VSCODE_CLI="$EXTTEST_STORAGE_DIR/Visual Studio Code.app/Contents/Resources/app/bin/code"

find_vscode_cli() {
    if command -v code >/dev/null 2>&1; then
        command -v code
        return 0
    fi

    if command -v code-insiders >/dev/null 2>&1; then
        command -v code-insiders
        return 0
    fi

    local candidate
    for candidate in \
        "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" \
        "$HOME/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" \
        "/Applications/Visual Studio Code - Insiders.app/Contents/Resources/app/bin/code-insiders" \
        "$HOME/Applications/Visual Studio Code - Insiders.app/Contents/Resources/app/bin/code-insiders"
    do
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

ensure_extest_cli() {
    if [ -f "$EXTTEST_CLI" ]; then
        return 0
    fi

    echo "== Preparing VSCode downloader ==" >&2
    (
        cd "$SCRIPT_DIR/test"
        npm install
    )
}

download_vscode_cli() {
    if [ -x "$EXTTEST_VSCODE_CLI" ]; then
        printf '%s\n' "$EXTTEST_VSCODE_CLI"
        return 0
    fi

    ensure_extest_cli

    echo "== Downloading fallback VSCode ==" >&2
    node "$EXTTEST_CLI" get-vscode --storage "$EXTTEST_STORAGE_DIR" --code_version max

    if [ -x "$EXTTEST_VSCODE_CLI" ]; then
        printf '%s\n' "$EXTTEST_VSCODE_CLI"
        return 0
    fi

    echo "Downloaded VSCode, but could not locate the CLI at:" >&2
    echo "  $EXTTEST_VSCODE_CLI" >&2
    return 1
}

run_vscode_cli() {
    local args=("$@")
    if [ -n "${VSCODE_EXTENSIONS_DIR:-}" ]; then
        args+=("--extensions-dir=$VSCODE_EXTENSIONS_DIR")
    fi
    if [ -n "${VSCODE_USER_DATA_DIR:-}" ]; then
        args+=("--user-data-dir=$VSCODE_USER_DATA_DIR")
    fi
    "$VSCODE_CLI" "${args[@]}"
}

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
VSCODE_USER_DATA_DIR=""
VSCODE_EXTENSIONS_DIR=""
if ! VSCODE_CLI="$(find_vscode_cli)"; then
    VSCODE_CLI="$(download_vscode_cli)"
    VSCODE_USER_DATA_DIR="$EXTTEST_USER_DATA_DIR"
    VSCODE_EXTENSIONS_DIR="$EXTTEST_EXTENSIONS_DIR"
fi

run_vscode_cli --install-extension "$VSIX_FILE" --force
run_vscode_cli --reuse-window "$ROOT_DIR" >/dev/null 2>&1 &
