#!/usr/bin/env bash
set -euo pipefail

# Publish the Hexana VS Code extension to the VS Code Marketplace and Open VSX.
#
# Prerequisites:
#   1. Build the .vsix first:  ./build-release-package.sh
#   2. Set the required environment variables (see below).
#
# Environment variables:
#   VSCE_PAT          — Personal Access Token for the VS Code Marketplace.
#                       Create one at https://dev.azure.com (Marketplace > Manage).
#   OVSX_PAT          — Personal Access Token for Open VSX.
#                       Create one at https://open-vsx.org/user-settings/tokens.
#   PUBLISH_TARGET    — (optional) "vscode", "openvsx", or "all" (default: "all").

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ---------------------------------------------------------------------------
# Resolve the .vsix file from package.json version
# ---------------------------------------------------------------------------
PACKAGE_NAME="$(node -p "require('./package.json').name")"
PACKAGE_VERSION="$(node -p "require('./package.json').version")"
VSIX_FILE="${PACKAGE_NAME}-${PACKAGE_VERSION}.vsix"

if [ ! -f "$VSIX_FILE" ]; then
  echo "ERROR: $VSIX_FILE not found."
  echo "Run ./build-release-package.sh first to produce the .vsix artifact."
  exit 1
fi

PUBLISH_TARGET="${PUBLISH_TARGET:-all}"
EXIT_CODE=0

# ---------------------------------------------------------------------------
# Publish to VS Code Marketplace
# ---------------------------------------------------------------------------
publish_vscode() {
  if [ -z "${VSCE_PAT:-}" ]; then
    echo "ERROR: VSCE_PAT is not set. Skipping VS Code Marketplace publish."
    return 1
  fi

  echo "== Publishing $VSIX_FILE to VS Code Marketplace =="
  npx @vscode/vsce publish --pat "$VSCE_PAT" --packagePath "$VSIX_FILE"
  echo "== Published to VS Code Marketplace =="
}

# ---------------------------------------------------------------------------
# Publish to Open VSX
# ---------------------------------------------------------------------------
publish_openvsx() {
  if [ -z "${OVSX_PAT:-}" ]; then
    echo "ERROR: OVSX_PAT is not set. Skipping Open VSX publish."
    return 1
  fi

  echo "== Publishing $VSIX_FILE to Open VSX =="
  npx ovsx publish "$VSIX_FILE" --pat "$OVSX_PAT"
  echo "== Published to Open VSX =="
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------
case "$PUBLISH_TARGET" in
  vscode)
    publish_vscode || EXIT_CODE=1
    ;;
  openvsx)
    publish_openvsx || EXIT_CODE=1
    ;;
  all)
    publish_vscode || EXIT_CODE=1
    publish_openvsx || EXIT_CODE=1
    ;;
  *)
    echo "ERROR: Unknown PUBLISH_TARGET '$PUBLISH_TARGET'. Use 'vscode', 'openvsx', or 'all'."
    exit 1
    ;;
esac

if [ "$EXIT_CODE" -ne 0 ]; then
  echo "WARNING: One or more publish targets failed. See output above."
fi

exit $EXIT_CODE
