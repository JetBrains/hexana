#!/usr/bin/env bash
set -euo pipefail
set +x

CLEANUP_DIRS=()

cleanup_on_exit() {
  local dir
  for dir in "${CLEANUP_DIRS[@]:-}"; do
    if [ -n "$dir" ]; then
      rm -rf "$dir"
    fi
  done
  return 0
}
trap cleanup_on_exit EXIT

register_cleanup_dir() {
  local dir="$1"
  CLEANUP_DIRS+=("$dir")
}

log() {
  printf '[codex-claude-release] %s\n' "$*"
}

warn() {
  printf '[codex-claude-release] WARNING: %s\n' "$*" >&2
}

die() {
  printf '[codex-claude-release] ERROR: %s\n' "$*" >&2
  exit 1
}

require_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Required tool '$1' is not installed"
}

require_env() {
  local name="$1"
  local value="${!name:-}"
  [ -n "$value" ] || die "Required environment variable $name is not set"
}

init_leak_guard_internal_names() {
  local mode="$1"

  if [ -z "${HEXANA_LEAK_GUARD_INTERNAL_NAMES:-}" ]; then
    [ "$mode" = "optional" ] || die "Required environment variable HEXANA_LEAK_GUARD_INTERNAL_NAMES is not set"
    log "HEXANA_LEAK_GUARD_INTERNAL_NAMES is not set; checking only the built-in public patterns"
    LEAK_GUARD_INTERNAL_NAMES_BRANCH=""
    return
  fi
  case "$HEXANA_LEAK_GUARD_INTERNAL_NAMES" in
    '|'*|*'|'|*'||'*)
      die "HEXANA_LEAK_GUARD_INTERNAL_NAMES must be a regex alternation without empty branches"
      ;;
  esac
  LEAK_GUARD_INTERNAL_NAMES_BRANCH="|$HEXANA_LEAK_GUARD_INTERNAL_NAMES"
}

use_self_test_leak_guard_names() {
  HEXANA_LEAK_GUARD_INTERNAL_NAMES='example-secret-param-a|example-secret-param-b|%example\.'
  export HEXANA_LEAK_GUARD_INTERNAL_NAMES
  init_leak_guard_internal_names "required"
}

to_bool() {
  case "${1:-false}" in
    true|TRUE|True|1|yes|YES|Yes) printf 'true' ;;
    false|FALSE|False|0|no|NO|No|'') printf 'false' ;;
    *) die "Expected boolean value for $2, got '$1'" ;;
  esac
}

lower_ascii() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{ print $1 }'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{ print $1 }'
  else
    die "Required tool 'shasum' or 'sha256sum' is not installed"
  fi
}

write_zip_digest_manifest() {
  local dir="$1"
  local output_file="$2"
  local zip_file
  local zip_name

  : > "$output_file"
  find "$dir" -maxdepth 1 -type f -name '*.zip' -print | sort | while IFS= read -r zip_file; do
    zip_name="${zip_file##*/}"
    printf '%s  %s\n' "$(sha256_file "$zip_file")" "$zip_name" >> "$output_file"
  done
}

require_matching_release_zip_assets() {
  local local_dir="$1"
  local existing_dir="$2"
  local local_manifest
  local existing_manifest
  local local_names
  local existing_names
  local missing_names
  local extra_names
  local diff_file

  local_manifest="$(mktemp "${TMPDIR:-/tmp}/hexana-local-release-zips.XXXXXX")"
  existing_manifest="$(mktemp "${TMPDIR:-/tmp}/hexana-existing-release-zips.XXXXXX")"
  local_names="$(mktemp "${TMPDIR:-/tmp}/hexana-local-release-zip-names.XXXXXX")"
  existing_names="$(mktemp "${TMPDIR:-/tmp}/hexana-existing-release-zip-names.XXXXXX")"
  missing_names="$(mktemp "${TMPDIR:-/tmp}/hexana-missing-release-zips.XXXXXX")"
  extra_names="$(mktemp "${TMPDIR:-/tmp}/hexana-extra-release-zips.XXXXXX")"
  diff_file="$(mktemp "${TMPDIR:-/tmp}/hexana-release-zip-digest-diff.XXXXXX")"
  register_cleanup_dir "$local_manifest"
  register_cleanup_dir "$existing_manifest"
  register_cleanup_dir "$local_names"
  register_cleanup_dir "$existing_names"
  register_cleanup_dir "$missing_names"
  register_cleanup_dir "$extra_names"
  register_cleanup_dir "$diff_file"

  write_zip_digest_manifest "$local_dir" "$local_manifest"
  write_zip_digest_manifest "$existing_dir" "$existing_manifest"
  awk '{ print $2 }' "$local_manifest" > "$local_names"
  awk '{ print $2 }' "$existing_manifest" > "$existing_names"

  comm -23 "$local_names" "$existing_names" > "$missing_names"
  if [ -s "$missing_names" ]; then
    sed 's/^/[codex-claude-release] Missing existing GitHub release ZIP asset: /' "$missing_names" >&2
    die "Existing GitHub release $RELEASE_TAG is missing required ZIP assets"
  fi

  comm -13 "$local_names" "$existing_names" > "$extra_names"
  if [ -s "$extra_names" ]; then
    sed 's/^/[codex-claude-release] Extra existing GitHub release ZIP asset: /' "$extra_names" >&2
    die "Existing GitHub release $RELEASE_TAG has unexpected ZIP assets"
  fi

  if ! diff -u "$existing_manifest" "$local_manifest" > "$diff_file"; then
    cat "$diff_file" >&2
    die "Existing GitHub release $RELEASE_TAG has ZIP assets with different SHA-256 digests"
  fi
}

default_release_tag() {
  case "$PUBLICATION_MODE" in
    production)
      printf 'v%s\n' "$PLUGIN_VERSION"
      ;;
    test-nightly)
      local build_id="${TEAMCITY_BUILD_ID:-}"
      if [ -z "$build_id" ]; then
        build_id="$(date -u +%Y%m%d%H%M%S)"
      fi
      build_id="$(sanitize_branch_part "$build_id")"
      printf 'nightly-%s-%s\n' "$(sanitize_branch_part "$PLUGIN_VERSION")" "$build_id"
      ;;
    *)
      die "Unsupported PUBLICATION_MODE: $PUBLICATION_MODE"
      ;;
  esac
}

validate_publication_mode() {
  local canonical_repo
  local requested_repo

  case "$PUBLICATION_MODE" in
    production|test-nightly) ;;
    *) die "PUBLICATION_MODE must be 'production' or 'test-nightly', got '$PUBLICATION_MODE'" ;;
  esac

  canonical_repo="$(lower_ascii "$PRODUCTION_PUBLIC_REPO_SLUG")"
  requested_repo="$(lower_ascii "$PUBLIC_REPO_SLUG")"

  case "$PUBLICATION_MODE" in
    production)
      if [ "$requested_repo" != "$canonical_repo" ] && [ "$ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO" != "true" ]; then
        die "Refusing production publication to non-canonical repo '$PUBLIC_REPO_SLUG'. Expected '$PRODUCTION_PUBLIC_REPO_SLUG'. Set PUBLICATION_MODE=test-nightly for test repositories."
      fi
      ;;
    test-nightly)
      if [ "$requested_repo" = "$canonical_repo" ]; then
        die "Refusing test-nightly publication to canonical production repo '$PRODUCTION_PUBLIC_REPO_SLUG'"
      fi
      if [ "$AUTO_MERGE" = "true" ]; then
        die "Refusing AUTO_MERGE=true in test-nightly publication mode"
      fi
      ;;
  esac

  if [ -z "${RELEASE_TAG:-}" ]; then
    RELEASE_TAG="$(default_release_tag)"
    export RELEASE_TAG
  fi
}

read_mcp_version() {
  local properties_file="mcp/server/gradle.properties"
  [ -f "$properties_file" ] || die "Missing $properties_file"
  awk -F= '
    /^[[:space:]]*mcpServerVersion[[:space:]]*=/ {
      value = $0
      sub(/^[^=]*=/, "", value)
      sub(/[[:space:]]+#.*/, "", value)
      gsub(/[[:space:]]/, "", value)
      print value
      exit
    }
  ' "$properties_file"
}

versioned_release_artifact() {
  local dir="$1"
  local file_name="$2"
  local file="$dir/$file_name"
  if [ -f "$file" ]; then
    printf '%s\n' "$file"
  fi
}

collect_native_release_artifacts() {
  local platform
  local artifact
  local native_zip

  NATIVE_RELEASE_ARTIFACTS=()
  for platform in $NATIVE_RELEASE_PLATFORMS; do
    for artifact in \
      "hexana-mcp-native-$platform-$PLUGIN_VERSION.zip" \
      "hexana-claude-plugin-$platform-$PLUGIN_VERSION.zip" \
      "hexana-codex-plugin-$platform-$PLUGIN_VERSION.zip" \
      "hexana-cli-native-$platform-$PLUGIN_VERSION.zip" \
      "hexana-cli-script-native-$platform-$PLUGIN_VERSION.zip"; do
      native_zip="$(versioned_release_artifact "$NATIVE_RELEASE_ARTIFACT_DIR" "$artifact")"
      [ -n "$native_zip" ] || die "No $artifact produced under $NATIVE_RELEASE_ARTIFACT_DIR"
      cp "$native_zip" "$release_dir/"
      NATIVE_RELEASE_ARTIFACTS+=("$artifact")
    done
  done
}

collect_release_artifacts() {
  release_dir="${RELEASE_ARTIFACT_DIR:-mcp/out/teamcity/release}"
  rm -rf "$release_dir"
  mkdir -p "$release_dir"

  server_zip="$(versioned_release_artifact "mcp/build/distributions" "server-$PLUGIN_VERSION.zip")"
  if [ -z "$server_zip" ]; then
    server_zip="$(versioned_release_artifact "mcp/server/build/distributions" "server-$PLUGIN_VERSION.zip")"
  fi
  claude_zip="$(versioned_release_artifact "mcp/build/distributions" "hexana-claude-plugin-$PLUGIN_VERSION.zip")"
  codex_zip="$(versioned_release_artifact "mcp/build/distributions" "hexana-codex-plugin-$PLUGIN_VERSION.zip")"
  standard_cli_zip="$(versioned_release_artifact "mcp/build/distributions" "hexana-cli-$PLUGIN_VERSION.zip")"
  script_cli_zip="$(versioned_release_artifact "mcp/build/distributions" "hexana-cli-script-$PLUGIN_VERSION.zip")"

  [ -n "$server_zip" ] || die "No server-$PLUGIN_VERSION.zip produced under mcp/build/distributions or mcp/server/build/distributions"
  [ -n "$claude_zip" ] || die "No hexana-claude-plugin-$PLUGIN_VERSION.zip produced under mcp/build/distributions"
  [ -n "$codex_zip" ] || die "No hexana-codex-plugin-$PLUGIN_VERSION.zip produced under mcp/build/distributions"
  [ -n "$standard_cli_zip" ] || die "No hexana-cli-$PLUGIN_VERSION.zip produced under mcp/build/distributions"
  [ -n "$script_cli_zip" ] || die "No hexana-cli-script-$PLUGIN_VERSION.zip produced under mcp/build/distributions"

  cp "$server_zip" "$release_dir/"
  cp "$claude_zip" "$release_dir/"
  cp "$codex_zip" "$release_dir/"
  cp "$standard_cli_zip" "$release_dir/"
  cp "$script_cli_zip" "$release_dir/"

  if [ "$INCLUDE_NATIVE_ARTIFACTS" = "true" ]; then
    collect_native_release_artifacts
  else
    NATIVE_RELEASE_ARTIFACTS=()
  fi
}

write_mcp_changelog_entry() {
  local version="$1"
  local output_file="$2"
  local changelog_file="${MCP_CHANGELOG_FILE:-mcp/CHANGELOG.md}"
  local status

  [ -f "$changelog_file" ] || die "Missing MCP changelog: $changelog_file"

  set +e
  awk -v version="$version" '
    function trim(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    function heading_version(line, value) {
      value = line
      sub(/^##[[:space:]]+/, "", value)
      value = trim(value)
      if (value ~ /^\[[^]]+\]/) {
        sub(/^\[/, "", value)
        sub(/\].*$/, "", value)
      } else {
        sub(/[[:space:]]+-[[:space:]].*$/, "", value)
        sub(/[[:space:]]+\(.*/, "", value)
      }
      return value
    }
    /^##[[:space:]]+/ {
      if (found) {
        exit
      }
      if (heading_version($0) == version) {
        found = 1
        next
      }
    }
    found {
      print
    }
    END {
      if (!found) {
        exit 42
      }
    }
  ' "$changelog_file" > "$output_file"
  status=$?
  set -e

  case "$status" in
    0) ;;
    42) die "MCP changelog $changelog_file has no entry for version $version" ;;
    *) die "Could not extract MCP changelog entry for version $version from $changelog_file" ;;
  esac

  if ! grep -E -q '[^[:space:]]' "$output_file"; then
    die "MCP changelog entry for version $version is empty"
  fi
  if ! grep -v -E '^[[:space:]]*$|^###[[:space:]]+' "$output_file" | grep -E -q '[^[:space:]]'; then
    die "MCP changelog entry for version $version contains no release notes"
  fi
}

write_release_notes() {
  local changelog_entry

  release_notes="$release_dir/release-notes.md"
  changelog_entry="$(mktemp "${TMPDIR:-/tmp}/hexana-mcp-changelog-entry.XXXXXX")"
  register_cleanup_dir "$changelog_entry"
  write_mcp_changelog_entry "$PLUGIN_VERSION" "$changelog_entry"

  {
    printf 'Hexana MCP %s\n\n' "$PLUGIN_VERSION"
    cat "$changelog_entry"
  } > "$release_notes"
  require_public_github_text_file "GitHub release notes" "$release_notes"
}

check_public_github_text_file() {
  local context="$1"
  local file="$2"
  local forbidden_content
  local matches
  local grep_status

  forbidden_content='TeamCity|buildserver|git\.jetbrains\.team|/Users/|/private/'"${LEAK_GUARD_INTERNAL_NAMES_BRANCH?leak guard internal names are not initialised}"'|GH_TOKEN|GITHUB_TOKEN|TEAMCITY_BUILD|BEGIN PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|sk-|AKIA|password=|secret=|token=|https?://|release/|automation/update-'

  [ -f "$file" ] || die "$context file is missing: $file"

  if matches="$(grep -I -n -E -- "$forbidden_content" "$file" 2>/dev/null)"; then
    printf '%s\n' "$matches" | sed "s#^#$context:#" >&2
    return 1
  else
    grep_status=$?
  fi
  if [ "$grep_status" -ne 1 ]; then
    return 2
  fi
  return 0
}

require_public_github_text_file() {
  local context="$1"
  local file="$2"
  local scan_status

  set +e
  check_public_github_text_file "$context" "$file"
  scan_status=$?
  set -e

  case "$scan_status" in
    0) ;;
    1) die "$context contains forbidden private/internal text" ;;
    *) die "$context scan failed" ;;
  esac
}

check_public_github_text() {
  local context="$1"
  local text="$2"
  local text_file

  text_file="$(mktemp "${TMPDIR:-/tmp}/hexana-public-github-text.XXXXXX")"
  register_cleanup_dir "$text_file"
  printf '%s\n' "$text" > "$text_file"
  require_public_github_text_file "$context" "$text_file"
}

self_test_public_github_text_guard() {
  use_self_test_leak_guard_names

  local safe_file
  local unsafe_file
  local scan_status

  safe_file="$(mktemp "${TMPDIR:-/tmp}/hexana-public-github-text-safe.XXXXXX")"
  unsafe_file="$(mktemp "${TMPDIR:-/tmp}/hexana-public-github-text-unsafe.XXXXXX")"
  register_cleanup_dir "$safe_file"
  register_cleanup_dir "$unsafe_file"

  printf 'Hexana MCP plugin distribution 0.7.0\n\n- Public marketplace metadata is updated through the companion pull request.\n' > "$safe_file"
  printf 'Updated from TeamCity build https://buildserver.internal.example/build/123\n' > "$unsafe_file"

  require_public_github_text_file "Public GitHub text guard self-test safe fixture" "$safe_file"

  set +e
  check_public_github_text_file "Public GitHub text guard self-test unsafe fixture" "$unsafe_file" >/dev/null 2>&1
  scan_status=$?
  set -e

  [ "$scan_status" -eq 1 ] || die "Public GitHub text guard self-test did not reject the unsafe fixture"

  printf 'Published with example-secret-param-b\n' > "$unsafe_file"
  set +e
  check_public_github_text_file "Public GitHub text guard self-test env-name fixture" "$unsafe_file" >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -eq 1 ] || die "Public GitHub text guard self-test did not enforce HEXANA_LEAK_GUARD_INTERNAL_NAMES"

  set +e
  (
    unset HEXANA_LEAK_GUARD_INTERNAL_NAMES
    init_leak_guard_internal_names "required"
  ) >"$unsafe_file" 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Public GitHub text guard self-test accepted a missing HEXANA_LEAK_GUARD_INTERNAL_NAMES"
  grep -F -q "HEXANA_LEAK_GUARD_INTERNAL_NAMES" "$unsafe_file" || die "Public GitHub text guard self-test did not name the missing variable"

  set +e
  (
    HEXANA_LEAK_GUARD_INTERNAL_NAMES='example-secret-param-a||example-secret-param-b'
    init_leak_guard_internal_names "required"
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Public GitHub text guard self-test accepted an empty regex branch"

  printf 'internal name example-secret-param-a in a packaged file\n' > "$unsafe_file"
  set +e
  (check_artifact_file_content "self-test.zip" "file.txt" "$unsafe_file") >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Public GitHub text guard self-test did not enforce the internal names on packaged files"

  (
    unset HEXANA_LEAK_GUARD_INTERNAL_NAMES
    init_leak_guard_internal_names "optional"
    [ -z "$LEAK_GUARD_INTERNAL_NAMES_BRANCH" ]
    printf 'Updated from TeamCity build 12345\n' > "$unsafe_file"
    set +e
    check_public_github_text_file "Public GitHub text guard self-test public-only fixture" "$unsafe_file" >/dev/null 2>&1
    [ "$?" -eq 1 ]
  ) >/dev/null 2>&1 || die "Public GitHub text guard self-test did not keep the built-in patterns without internal names"
  log "Public GitHub text guard self-test passed"
}

self_test_release_native_artifact_policy() {
  use_self_test_leak_guard_names

  local temp_dir
  local lock_dir
  local artifact_name="hexana-mcp-native-macos-arm64-0.2.0.zip"
  local scan_status

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-native-artifact-policy.XXXXXX")"
  register_cleanup_dir "$temp_dir"
  lock_dir="$temp_dir/public/plugins/hexana"
  mkdir -p "$lock_dir" "$temp_dir/release"
  printf '%s\n' \
    '{' \
    '  "nativeArtifacts": {' \
    '    "distribution": "github-release-only",' \
    '    "artifacts": [' \
    "      \"$artifact_name\"" \
    '    ]' \
    '  }' \
    '}' > "$lock_dir/release.lock.json"

  set +e
  (
    GENERATED_PUBLIC_DIR="$temp_dir/public"
    PLUGIN_NAME="hexana"
    release_dir="$temp_dir/release"
    INCLUDE_NATIVE_ARTIFACTS="false"
    check_release_native_artifact_policy
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Native artifact policy self-test did not reject INCLUDE_NATIVE_ARTIFACTS=false"

  set +e
  (
    GENERATED_PUBLIC_DIR="$temp_dir/public"
    PLUGIN_NAME="hexana"
    release_dir="$temp_dir/release"
    INCLUDE_NATIVE_ARTIFACTS="true"
    check_release_native_artifact_policy
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Native artifact policy self-test did not reject a missing native artifact"

  touch "$temp_dir/release/$artifact_name"
  GENERATED_PUBLIC_DIR="$temp_dir/public" \
    PLUGIN_NAME="hexana" \
    release_dir="$temp_dir/release" \
    INCLUDE_NATIVE_ARTIFACTS="true" \
    check_release_native_artifact_policy

  log "Native artifact policy self-test passed"
}

self_test_public_bundle_native_policy() {
  use_self_test_leak_guard_names

  local temp_dir
  local plugin_dir
  local scan_status

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-public-native-policy.XXXXXX")"
  register_cleanup_dir "$temp_dir"
  plugin_dir="$temp_dir/public/plugins/hexana"
  mkdir -p "$plugin_dir/native/macos-arm64" "$plugin_dir/server"
  touch \
    "$plugin_dir/LICENSE.txt" \
    "$plugin_dir/THIRD-PARTY-NOTICES.md" \
    "$plugin_dir/server/LICENSE.txt" \
    "$plugin_dir/server/THIRD-PARTY-NOTICES.md"
  printf 'native executable fixture\n' > "$plugin_dir/native/macos-arm64/hexana-mcp"
  printf '%s\n' \
    '# Hexana MCP' \
    '' \
    '- `native/<platform>/hexana-mcp`: optional bundled native executable' > "$plugin_dir/README.md"
  printf '%s\n' \
    '{' \
    '  "nativeArtifacts": {' \
    '    "distribution": "marketplace-bundle",' \
    '    "platforms": [' \
    '      "macos-arm64"' \
    '    ]' \
    '  }' \
    '}' > "$plugin_dir/release.lock.json"

  GENERATED_PUBLIC_DIR="$temp_dir/public" \
    PLUGIN_NAME="hexana" \
    check_public_bundle_native_policy

  printf '%s\n' \
    '{' \
    '  "nativeArtifacts": {' \
    '    "distribution": "marketplace-bundle",' \
    '    "platforms": [' \
    '      "linux-x64"' \
    '    ]' \
    '  }' \
    '}' > "$plugin_dir/release.lock.json"

  set +e
  (
    GENERATED_PUBLIC_DIR="$temp_dir/public"
    PLUGIN_NAME="hexana"
    check_public_bundle_native_policy
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Public bundled native policy self-test did not reject mismatched metadata"

  rm -rf "$plugin_dir/native"
  printf '%s\n' \
    '# Hexana MCP' \
    '' \
    'Native executables are not included in this public marketplace tree.' > "$plugin_dir/README.md"
  printf '%s\n' \
    '{' \
    '  "nativeArtifacts": {' \
    '    "distribution": "github-release-only",' \
    '    "artifacts": [' \
    '      "hexana-mcp-native-macos-arm64-0.2.0.zip"' \
    '    ]' \
    '  }' \
    '}' > "$plugin_dir/release.lock.json"
  GENERATED_PUBLIC_DIR="$temp_dir/public" \
    PLUGIN_NAME="hexana" \
    check_public_bundle_native_policy

  log "Public bundled native policy self-test passed"
}

self_test_release_native_artifact_default() {
  use_self_test_leak_guard_names

  local temp_dir
  local version="0.2.0"
  local scan_status
  local error_file

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-native-artifact-default.XXXXXX")"
  register_cleanup_dir "$temp_dir"
  error_file="$temp_dir/error.log"
  mkdir -p "$temp_dir/mcp/build/distributions" "$temp_dir/mcp/build/distributions-native"
  touch \
    "$temp_dir/mcp/build/distributions/server-$version.zip" \
    "$temp_dir/mcp/build/distributions/hexana-claude-plugin-$version.zip" \
    "$temp_dir/mcp/build/distributions/hexana-codex-plugin-$version.zip" \
    "$temp_dir/mcp/build/distributions/hexana-cli-$version.zip"

  set +e
  (
    cd "$temp_dir"
    PLUGIN_VERSION="$version"
    INCLUDE_NATIVE_ARTIFACTS="false"
    NATIVE_RELEASE_ARTIFACT_DIR="mcp/build/distributions-native"
    NATIVE_RELEASE_PLATFORMS="macos-arm64"
    collect_release_artifacts
  ) >"$error_file" 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Native artifact default self-test did not require the script CLI JVM artifact"
  grep -F -q "No hexana-cli-script-$version.zip produced under mcp/build/distributions" "$error_file" ||
    die "Native artifact default self-test did not record the missing script CLI JVM artifact failure"
  log "Native artifact default self-test recorded missing hexana-cli-script-$version.zip failure"

  touch "$temp_dir/mcp/build/distributions/hexana-cli-script-$version.zip"

  set +e
  (
    cd "$temp_dir"
    unset INCLUDE_NATIVE_ARTIFACTS
    PLUGIN_VERSION="$version"
    INCLUDE_NATIVE_ARTIFACTS="$(to_bool "${INCLUDE_NATIVE_ARTIFACTS:-true}" "INCLUDE_NATIVE_ARTIFACTS")"
    NATIVE_RELEASE_ARTIFACT_DIR="mcp/build/distributions-native"
    NATIVE_RELEASE_PLATFORMS="macos-arm64"
    collect_release_artifacts
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Native artifact default self-test did not require native artifacts by default"

  touch \
    "$temp_dir/mcp/build/distributions-native/hexana-mcp-native-macos-arm64-$version.zip" \
    "$temp_dir/mcp/build/distributions-native/hexana-claude-plugin-macos-arm64-$version.zip" \
    "$temp_dir/mcp/build/distributions-native/hexana-codex-plugin-macos-arm64-$version.zip" \
    "$temp_dir/mcp/build/distributions-native/hexana-cli-native-macos-arm64-$version.zip"

  set +e
  (
    cd "$temp_dir"
    unset INCLUDE_NATIVE_ARTIFACTS
    PLUGIN_VERSION="$version"
    INCLUDE_NATIVE_ARTIFACTS="$(to_bool "${INCLUDE_NATIVE_ARTIFACTS:-true}" "INCLUDE_NATIVE_ARTIFACTS")"
    NATIVE_RELEASE_ARTIFACT_DIR="mcp/build/distributions-native"
    NATIVE_RELEASE_PLATFORMS="macos-arm64"
    collect_release_artifacts
  ) >"$error_file" 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Native artifact default self-test did not require the script CLI native artifact"
  grep -F -q "No hexana-cli-script-native-macos-arm64-$version.zip produced under mcp/build/distributions-native" "$error_file" ||
    die "Native artifact default self-test did not record the missing script CLI native artifact failure"
  log "Native artifact default self-test recorded missing hexana-cli-script-native-macos-arm64-$version.zip failure"

  touch "$temp_dir/mcp/build/distributions-native/hexana-cli-script-native-macos-arm64-$version.zip"

  (
    cd "$temp_dir"
    unset INCLUDE_NATIVE_ARTIFACTS
    PLUGIN_VERSION="$version"
    INCLUDE_NATIVE_ARTIFACTS="$(to_bool "${INCLUDE_NATIVE_ARTIFACTS:-true}" "INCLUDE_NATIVE_ARTIFACTS")"
    NATIVE_RELEASE_ARTIFACT_DIR="mcp/build/distributions-native"
    NATIVE_RELEASE_PLATFORMS="macos-arm64"
    collect_release_artifacts
    [ -f "mcp/out/teamcity/release/hexana-mcp-native-macos-arm64-$version.zip" ]
    [ -f "mcp/out/teamcity/release/hexana-claude-plugin-macos-arm64-$version.zip" ]
    [ -f "mcp/out/teamcity/release/hexana-codex-plugin-macos-arm64-$version.zip" ]
    [ -f "mcp/out/teamcity/release/hexana-cli-native-macos-arm64-$version.zip" ]
    [ -f "mcp/out/teamcity/release/hexana-cli-script-native-macos-arm64-$version.zip" ]
  )

  log "Native artifact default self-test passed"
}

self_test_mcp_changelog_release_notes() {
  use_self_test_leak_guard_names

  local temp_dir
  local release_notes_file
  local scan_status

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-mcp-changelog.XXXXXX")"
  register_cleanup_dir "$temp_dir"
  mkdir -p "$temp_dir/mcp/server" "$temp_dir/release"
  printf 'mcpServerVersion=0.2.0\n' > "$temp_dir/mcp/server/gradle.properties"
  cat > "$temp_dir/mcp/CHANGELOG.md" <<'EOF'
# Hexana MCP Changelog

## Unreleased

## 0.2.0 - 2026-06-23

### Added
- Native MCP release artifacts are published for macOS arm64 and Linux x64.
- Claude Code and Codex wrappers use native executables with the JVM runtime as fallback.

## 0.1.0 - 2026-06-10

### Added
- Initial MCP packaging.
EOF

  (
    cd "$temp_dir"
    PLUGIN_VERSION="0.2.0"
    release_dir="$temp_dir/release"
    NATIVE_RELEASE_ARTIFACTS=()
    NATIVE_RELEASE_PLATFORMS="macos-arm64 linux-x64"
    write_release_notes
  )
  release_notes_file="$temp_dir/release/release-notes.md"
  grep -F -q 'Hexana MCP 0.2.0' "$release_notes_file" ||
    die "MCP changelog self-test did not write the release title"
  grep -F -q 'Native MCP release artifacts are published for macOS arm64 and Linux x64.' "$release_notes_file" ||
    die "MCP changelog self-test did not include the matching changelog entry"
  if grep -F -q 'Initial MCP packaging.' "$release_notes_file"; then
    die "MCP changelog self-test leaked another changelog entry into release notes"
  fi

  set +e
  (
    cd "$temp_dir"
    PLUGIN_VERSION="9.9.9"
    release_dir="$temp_dir/release"
    NATIVE_RELEASE_ARTIFACTS=()
    NATIVE_RELEASE_PLATFORMS=""
    write_release_notes
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "MCP changelog self-test did not reject a missing version entry"

  cat > "$temp_dir/mcp/CHANGELOG.md" <<'EOF'
# Hexana MCP Changelog

## 0.2.0 - 2026-06-23

### Fixed
- Release output mentions TeamCity internals.
EOF

  set +e
  (
    cd "$temp_dir"
    PLUGIN_VERSION="0.2.0"
    release_dir="$temp_dir/release"
    NATIVE_RELEASE_ARTIFACTS=()
    NATIVE_RELEASE_PLATFORMS=""
    write_release_notes
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "MCP changelog self-test did not apply the public text guard to release notes"

  log "MCP changelog release-notes self-test passed"
}

self_test_publication_mode_guard() {
  use_self_test_leak_guard_names

  local scan_status

  (
    PUBLICATION_MODE="production"
    PUBLIC_REPO_SLUG="JetBrains/hexana"
    PRODUCTION_PUBLIC_REPO_SLUG="JetBrains/hexana"
    ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO="false"
    AUTO_MERGE="false"
    PLUGIN_VERSION="1.2.3"
    RELEASE_TAG=""
    validate_publication_mode
    [ "$RELEASE_TAG" = "v1.2.3" ]
  )

  set +e
  (
    PUBLICATION_MODE="production"
    PUBLIC_REPO_SLUG="example-owner/example-nightly"
    PRODUCTION_PUBLIC_REPO_SLUG="JetBrains/hexana"
    ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO="false"
    AUTO_MERGE="false"
    PLUGIN_VERSION="1.2.3"
    RELEASE_TAG=""
    validate_publication_mode
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Publication mode self-test did not reject production publication to a test repo"

  set +e
  (
    PUBLICATION_MODE="test-nightly"
    PUBLIC_REPO_SLUG="JetBrains/hexana"
    PRODUCTION_PUBLIC_REPO_SLUG="JetBrains/hexana"
    ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO="false"
    AUTO_MERGE="false"
    PLUGIN_VERSION="1.2.3"
    RELEASE_TAG=""
    validate_publication_mode
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Publication mode self-test did not reject test-nightly publication to production repo"

  set +e
  (
    PUBLICATION_MODE="test-nightly"
    PUBLIC_REPO_SLUG="example-owner/example-nightly"
    PRODUCTION_PUBLIC_REPO_SLUG="JetBrains/hexana"
    ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO="false"
    AUTO_MERGE="true"
    PLUGIN_VERSION="1.2.3"
    RELEASE_TAG=""
    validate_publication_mode
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Publication mode self-test did not reject AUTO_MERGE=true in test-nightly mode"

  (
    PUBLICATION_MODE="test-nightly"
    PUBLIC_REPO_SLUG="example-owner/example-nightly"
    PRODUCTION_PUBLIC_REPO_SLUG="JetBrains/hexana"
    ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO="false"
    AUTO_MERGE="false"
    PLUGIN_VERSION="1.2.3"
    TEAMCITY_BUILD_ID="12345"
    RELEASE_TAG=""
    validate_publication_mode
    [ "$RELEASE_TAG" = "nightly-1.2.3-12345" ]
  )

  log "Publication mode guard self-test passed"
}

self_test_existing_release_asset_immutability() {
  use_self_test_leak_guard_names

  local temp_dir
  local local_dir
  local existing_dir
  local scan_status

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-existing-release-assets.XXXXXX")"
  register_cleanup_dir "$temp_dir"
  RELEASE_TAG="self-test"
  local_dir="$temp_dir/local"
  existing_dir="$temp_dir/existing"
  mkdir -p "$local_dir" "$existing_dir"

  printf 'alpha\n' > "$local_dir/hexana-alpha.zip"
  printf 'beta\n' > "$local_dir/hexana-beta.zip"
  cp "$local_dir/hexana-alpha.zip" "$existing_dir/hexana-alpha.zip"
  cp "$local_dir/hexana-beta.zip" "$existing_dir/hexana-beta.zip"
  require_matching_release_zip_assets "$local_dir" "$existing_dir"

  rm "$existing_dir/hexana-beta.zip"
  set +e
  (
    require_matching_release_zip_assets "$local_dir" "$existing_dir"
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Existing release asset immutability self-test did not reject a missing required asset"
  cp "$local_dir/hexana-beta.zip" "$existing_dir/hexana-beta.zip"

  printf 'gamma\n' > "$existing_dir/hexana-gamma.zip"
  set +e
  (
    require_matching_release_zip_assets "$local_dir" "$existing_dir"
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Existing release asset immutability self-test did not reject an extra existing asset"
  rm "$existing_dir/hexana-gamma.zip"

  printf 'different beta\n' > "$existing_dir/hexana-beta.zip"
  set +e
  (
    require_matching_release_zip_assets "$local_dir" "$existing_dir"
  ) >/dev/null 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Existing release asset immutability self-test did not reject a digest mismatch"

  log "Existing release asset immutability self-test passed"
}

publish_github_release() {
  local existing_dir

  require_public_github_text_file "GitHub release notes" "$release_notes"

  if [ "$DRY_RUN" = "true" ]; then
    log "Dry run requested; not creating or updating GitHub release $RELEASE_TAG"
    return
  fi

  log "Publishing GitHub release $RELEASE_TAG in $PUBLIC_REPO_SLUG"
  if gh release view "$RELEASE_TAG" --repo "$PUBLIC_REPO_SLUG" >/dev/null 2>&1; then
    existing_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-existing-release-assets.XXXXXX")"
    register_cleanup_dir "$existing_dir"
    gh release download "$RELEASE_TAG" \
      --repo "$PUBLIC_REPO_SLUG" \
      --pattern "*.zip" \
      --dir "$existing_dir"
    require_matching_release_zip_assets "$release_dir" "$existing_dir"
    gh release edit "$RELEASE_TAG" \
      --repo "$PUBLIC_REPO_SLUG" \
      --title "Hexana MCP $PLUGIN_VERSION" \
      --notes-file "$release_notes"
  else
    gh release create "$RELEASE_TAG" "$release_dir"/*.zip \
      --repo "$PUBLIC_REPO_SLUG" \
      --title "Hexana MCP $PLUGIN_VERSION" \
      --notes-file "$release_notes"
  fi
}

artifact_relative_path() {
  local base="$1"
  local file="$2"
  printf '%s' "${file#"$base"/}"
}

check_artifact_file_name() {
  local zip_name="$1"
  local rel="$2"
  case "$rel" in
    mapping.txt|*/mapping.txt|r8/mapping.txt|*/r8/mapping.txt)
      die "Release artifact $zip_name contains R8 mapping output: $rel"
      ;;
    */src/*|src/*|*/build/*|build/*|*/target/*|target/*|*/out/*|out/*|*/.gradle/*|.gradle/*|*/.idea/*|.idea/*|*/.teamcity/*|.teamcity/*|*/private/*|private/*|*/secret/*|secret/*|*/secrets/*|secrets/*|*/.env|.env|*.java|*.kt|*.kts|*.pem|*.key|*.p12|*.keystore|*.jks)
      die "Release artifact $zip_name contains suspicious path: $rel"
      ;;
  esac
}

check_artifact_file_content() {
  local zip_name="$1"
  local rel="$2"
  local file="$3"
  local forbidden_content

  forbidden_content='(/Users/|/private/'"${LEAK_GUARD_INTERNAL_NAMES_BRANCH?leak guard internal names are not initialised}"'|git\.jetbrains\.team|GH_TOKEN|GITHUB_TOKEN|TEAMCITY_BUILD|BEGIN PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|sk-|AKIA|password=|secret=|token=)'

  if strings "$file" | grep -E -q "$forbidden_content"; then
    die "Release artifact $zip_name contains forbidden string pattern in $rel"
  fi
}

require_nonempty_file() {
  local context="$1"
  local file="$2"
  [ -f "$file" ] || die "$context is missing required file: $file"
  [ -s "$file" ] || die "$context has empty required file: $file"
}

check_release_artifact_legal_files() {
  local zip_name="$1"
  local root="$2"

  case "$zip_name" in
    hexana-claude-plugin-*.zip|hexana-codex-plugin-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/THIRD-PARTY-NOTICES.md"
      require_nonempty_file "Release artifact $zip_name" "$root/server/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/server/THIRD-PARTY-NOTICES.md"
      ;;
    server-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/server/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/server/THIRD-PARTY-NOTICES.md"
      ;;
    hexana-cli-native-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/THIRD-PARTY-NOTICES.md"
      if [ ! -f "$root/bin/hexana-cli" ] && [ ! -f "$root/bin/hexana-cli.exe" ]; then
        die "Release artifact $zip_name is missing native executable under bin/"
      fi
      ;;
    hexana-cli-script-native-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/THIRD-PARTY-NOTICES.md"
      if [ ! -f "$root/bin/hexana-cli-script" ] && [ ! -f "$root/bin/hexana-cli-script.exe" ]; then
        die "Release artifact $zip_name is missing native executable under bin/"
      fi
      ;;
    hexana-mcp-native-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/THIRD-PARTY-NOTICES.md"
      if [ ! -f "$root/bin/hexana-mcp" ] && [ ! -f "$root/bin/hexana-mcp.exe" ]; then
        die "Release artifact $zip_name is missing native executable under bin/"
      fi
      ;;
    hexana-cli-script-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/hexana-cli-script/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/hexana-cli-script/THIRD-PARTY-NOTICES.md"
      require_nonempty_file "Release artifact $zip_name" "$root/hexana-cli-script/lib/hexana-runtime-obfuscated.jar"
      if [ ! -f "$root/hexana-cli-script/bin/hexana-cli-script" ] && [ ! -f "$root/hexana-cli-script/bin/hexana-cli-script.bat" ]; then
        die "Release artifact $zip_name is missing JVM CLI executable under bin/"
      fi
      ;;
    hexana-cli-*.zip)
      require_nonempty_file "Release artifact $zip_name" "$root/hexana-cli/LICENSE.txt"
      require_nonempty_file "Release artifact $zip_name" "$root/hexana-cli/THIRD-PARTY-NOTICES.md"
      require_nonempty_file "Release artifact $zip_name" "$root/hexana-cli/lib/hexana-runtime-obfuscated.jar"
      if [ ! -f "$root/hexana-cli/bin/hexana-cli" ] && [ ! -f "$root/hexana-cli/bin/hexana-cli.bat" ]; then
        die "Release artifact $zip_name is missing JVM CLI executable under bin/"
      fi
      ;;
    *)
      die "Unexpected release artifact name: $zip_name"
      ;;
  esac
}

is_absolute_command_path() {
  local command_path="$1"
  case "$command_path" in
    /*|~/*|[A-Za-z]:/*|[A-Za-z]:\\*|\\\\*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

check_mcp_json_command_paths() {
  local context="$1"
  local rel="$2"
  local file="$3"
  local command_path

  case "$rel" in
    .mcp.json|*/.mcp.json)
      ;;
    *)
      return
      ;;
  esac

  while IFS= read -r command_path; do
    [ -n "$command_path" ] || continue
    if is_absolute_command_path "$command_path"; then
      die "$context contains absolute .mcp.json command path in $rel: $command_path"
    fi
  done < <(jq -r '.. | objects | .command? // empty | strings' "$file")

  if jq -e '.. | objects | select(.command? == "./bin/hexana-mcp" and .cwd? != ".")' "$file" >/dev/null; then
    die "$context contains Codex .mcp.json command ./bin/hexana-mcp without cwd=. in $rel"
  fi
}

check_plugin_manifest_license() {
  local context="$1"
  local rel="$2"
  local file="$3"
  local license

  case "$rel" in
    .claude-plugin/plugin.json|*/.claude-plugin/plugin.json)
      ;;
    *)
      return
      ;;
  esac

  license="$(jq -r '.license // empty' "$file")"
  if [ "$license" != "SEE LICENSE IN LICENSE.txt" ]; then
    die "$context contains unsupported Claude plugin license in $rel: ${license:-<missing>}"
  fi
}

check_embedded_jar_entries() {
  local zip_name="$1"
  local root="$2"
  local jar_file
  local jar_rel
  local suspicious_entry

  while IFS= read -r -d '' jar_file; do
    jar_rel="$(artifact_relative_path "$root" "$jar_file")"
    suspicious_entry="$(jar tf "$jar_file" | grep -E '(^|/)(src|build|target|out|\.gradle|\.idea|\.teamcity|private|secret|secrets)(/|$)|(^|/)\.env$|(^|/)mapping\.txt$|\.(java|kt|kts|pem|key|p12|keystore|jks)$' | head -n 1 || true)"
    if [ -n "$suspicious_entry" ]; then
      die "Release artifact $zip_name contains suspicious entry in $jar_rel: $suspicious_entry"
    fi
  done < <(find "$root" -type f -name '*.jar' -print0)
}

check_obfuscated_runtime_layout() {
  local zip_name="$1"
  local root="$2"
  local jar_file
  local jar_rel
  local found_obfuscated="false"
  local leaked_hexana_class

  while IFS= read -r -d '' jar_file; do
    jar_rel="$(artifact_relative_path "$root" "$jar_file")"
    case "$(basename "$jar_file")" in
      hexana-runtime-obfuscated.jar)
        found_obfuscated="true"
        ;;
      analytics-jvm.jar|arrow-jvm.jar|binaryProvider-jvm.jar|cli-core.jar|cli-runtime.jar|codec-jvm.jar|common-jvm.jar|core.jar|dex-jvm.jar|dwarf-jvm.jar|elf-jvm.jar|encdec-jvm.jar|flatbuffers-jvm.jar|hexanaDocument-jvm.jar|javaBytecode-jvm.jar|macho-jvm.jar|parquet-jvm.jar|pe-jvm.jar|plugins-shared-jvm.jar|protobuf-jvm.jar|server-*.jar|thrift-jvm.jar|wasmParser-jvm.jar|zip-jvm.jar)
        die "Release artifact $zip_name contains pre-obfuscation Hexana runtime jar: $jar_rel"
        ;;
      *)
        leaked_hexana_class="$(jar tf "$jar_file" | grep -E '^org/jetbrains/hexana/.*\.class$' | head -n 1 || true)"
        if [ -n "$leaked_hexana_class" ]; then
          die "Release artifact $zip_name contains non-obfuscated Hexana classes in $jar_rel: $leaked_hexana_class"
        fi
        ;;
    esac
  done < <(find "$root" -type f -name '*.jar' -print0)

  if [ "$found_obfuscated" != "true" ]; then
    die "Release artifact $zip_name does not contain hexana-runtime-obfuscated.jar"
  fi
}

check_obfuscated_runtime_hardening() {
  local zip_name="$1"
  local root="$2"
  local jar_file
  local jar_rel
  local suspicious_class
  local obfuscated_class_count
  local forbidden_debug_string
  local allowed_hexana_class_regex

  case "$zip_name" in
    hexana-cli-script-*.zip|hexana-cli-*.zip)
      allowed_hexana_class_regex='^org/jetbrains/hexana/cli/(hexanacli|hexanacliscript)/MainKt\.class$|^org/jetbrains/hexana/cli/metadata/(HexanaCliInputMode|HexanaCliToolGroup|HexanaCliOutputPolicy|HexanaCliExecutionStatus|HexanaCliToolSpec|HexanaGeneratedCliToolRegistry|HexanaCliReleaseProduct|HexanaCliProductToolSelection|HexanaGeneratedCliProducts)\.class$|^org/jetbrains/hexana/cli/runtime/|^org/jetbrains/hexana/mcp/[^/]+\.class$|^org/jetbrains/hexana/mcp/model/|^org/jetbrains/hexana/providers/a\.class$'
      ;;
    *)
      allowed_hexana_class_regex='^org/jetbrains/hexana/mcp/model/|^org/jetbrains/hexana/mcp/server/(MainKt|HexanaLogLevel)\.class$|^org/jetbrains/hexana/providers/a\.class$'
      ;;
  esac

  while IFS= read -r -d '' jar_file; do
    jar_rel="$(artifact_relative_path "$root" "$jar_file")"

    suspicious_class="$(
      jar tf "$jar_file" |
        grep -E '^org/jetbrains/hexana/.*\.class$' |
        grep -Ev "$allowed_hexana_class_regex" |
        head -n 1 || true
    )"
    if [ -n "$suspicious_class" ]; then
      die "Release artifact $zip_name exposes non-allowlisted Hexana class name in $jar_rel: $suspicious_class"
    fi

    obfuscated_class_count="$(jar tf "$jar_file" | grep -E '^obf/.*\.class$' | wc -l | tr -d '[:space:]')"
    if [ "$obfuscated_class_count" -lt 500 ]; then
      die "Release artifact $zip_name has too few obfuscated runtime classes in $jar_rel: $obfuscated_class_count"
    fi

    forbidden_debug_string="$(
      strings "$jar_file" |
        grep -E '(^|[^A-Za-z0-9_.])(SMAP|kotlin\.Metadata|SourceDebugExtension|Main\.kt|DefaultHexanaAnalysisService\.kt|WasmBinaryProvider\.kt)([^A-Za-z0-9_.]|$)|/src/(main|jvmMain|commonMain)/' |
        head -n 1 || true
    )"
    if [ -n "$forbidden_debug_string" ]; then
      die "Release artifact $zip_name contains source/debug metadata in $jar_rel"
    fi
  done < <(find "$root" -type f -name 'hexana-runtime-obfuscated.jar' -print0)
}

list_zip_metadata() {
  local mode="$1"
  local zip="$2"
  local output_file="$3"

  if command -v zipinfo >/dev/null 2>&1; then
    LC_ALL=C zipinfo "$mode" "$zip" > "$output_file" || die "Could not inspect release artifact $(basename "$zip")"
  else
    LC_ALL=C unzip -Z "$mode" "$zip" > "$output_file" || die "Could not inspect release artifact $(basename "$zip")"
  fi
}

validate_zip_entry_name_controls() {
  local zip="$1"
  local zip_name="$2"
  local work_dir="$3"
  local escaped_entries_file="$4"
  local raw_entries_file="$work_dir/raw-entries"
  local raw_entries_without_newlines_file="$work_dir/raw-entries-without-newlines"
  local summary_file="$work_dir/summary"
  local expected_entry_count
  local raw_line_count
  local escaped_entry

  jar tf "$zip" > "$raw_entries_file" || die "Could not read raw ZIP entries from release artifact $zip_name"
  list_zip_metadata -t "$zip" "$summary_file"
  expected_entry_count="$(awk '
    $1 ~ /^[0-9]+$/ && ($2 == "file," || $2 == "files,") {
      count = $1
    }
    END {
      print count
    }
  ' "$summary_file")"
  case "$expected_entry_count" in
    ''|*[!0-9]*) die "Could not determine ZIP entry count for release artifact $zip_name" ;;
  esac

  raw_line_count="$(wc -l < "$raw_entries_file" | tr -d '[:space:]')"
  LC_ALL=C tr -d '\n' < "$raw_entries_file" > "$raw_entries_without_newlines_file"
  if [ "$raw_line_count" -ne "$expected_entry_count" ] ||
    LC_ALL=C grep -q '[[:cntrl:]]' "$raw_entries_without_newlines_file"; then
    escaped_entry="$(grep -E '\^.|[?]' "$escaped_entries_file" | head -n 1 || true)"
    die "Release artifact $zip_name contains control-character ZIP entry: ${escaped_entry:-<unprintable entry>}"
  fi
}

validate_zip_entries_before_extraction() {
  local zip="$1"
  local zip_name="$2"
  local work_dir="$3"
  local entries_file="$work_dir/entries"
  local listing_file="$work_dir/listing"
  local seen_entries_file="$work_dir/seen-entries"
  local entry
  local collision_key
  local symlink_entry

  list_zip_metadata -1 "$zip" "$entries_file"
  [ -s "$entries_file" ] || die "Release artifact $zip_name has no ZIP entries"
  validate_zip_entry_name_controls "$zip" "$zip_name" "$work_dir" "$entries_file"
  : > "$seen_entries_file"

  while IFS= read -r entry || [ -n "$entry" ]; do
    [ -n "$entry" ] || die "Release artifact $zip_name contains an empty ZIP entry"
    case "$entry" in
      /*|[A-Za-z]:/*)
        die "Release artifact $zip_name contains absolute ZIP entry: $entry"
        ;;
      *\\*)
        die "Release artifact $zip_name contains backslash ZIP entry: $entry"
        ;;
      ..|../*|*/../*|*/..)
        die "Release artifact $zip_name contains traversal ZIP entry: $entry"
        ;;
      .|./*|*/./*|*/.|*//*)
        die "Release artifact $zip_name contains ambiguous ZIP entry: $entry"
        ;;
    esac

    collision_key="$(lower_ascii "${entry%/}")"
    if grep -Fqx -e "$collision_key" "$seen_entries_file"; then
      die "Release artifact $zip_name contains duplicate or case-colliding ZIP entry: $entry"
    fi
    printf '%s\n' "$collision_key" >> "$seen_entries_file"
  done < "$entries_file"

  list_zip_metadata -l "$zip" "$listing_file"
  symlink_entry="$(awk '
    substr($1, 1, 1) == "l" && length($1) == 10 && $2 ~ /^[0-9][0-9.]*$/ {
      entry = $10
      for (i = 11; i <= NF; i++) {
        entry = entry " " $i
      }
      print entry
      exit
    }
  ' "$listing_file")"
  if [ -n "$symlink_entry" ]; then
    die "Release artifact $zip_name contains symlink ZIP entry: $symlink_entry"
  fi
}

require_release_artifact_security_tools() {
  require_tool "jq"
  require_tool "jar"
  require_tool "strings"
  require_tool "unzip"
}

security_check_release_artifacts() {
  local artifact_dir="$1"
  local security_dir
  local zip
  local zip_name
  local metadata_dir
  local extract_dir
  local file
  local rel
  local artifact_count=0

  [ -d "$artifact_dir" ] || die "Release artifact security check directory does not exist: $artifact_dir"
  log "Running release artifact security check in $artifact_dir"
  security_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-release-artifact-security.XXXXXX")"
  register_cleanup_dir "$security_dir"

  while IFS= read -r -d '' zip; do
    artifact_count=$((artifact_count + 1))
    zip_name="$(basename "$zip")"
    metadata_dir="$security_dir/metadata-$artifact_count"
    extract_dir="$security_dir/artifact-$artifact_count"
    mkdir -p "$metadata_dir" "$extract_dir"
    validate_zip_entries_before_extraction "$zip" "$zip_name" "$metadata_dir"
    unzip -qq "$zip" -d "$extract_dir"

    while IFS= read -r -d '' file; do
      rel="$(artifact_relative_path "$extract_dir" "$file")"
      check_artifact_file_name "$zip_name" "$rel"
      check_artifact_file_content "$zip_name" "$rel" "$file"
      check_mcp_json_command_paths "Release artifact $zip_name" "$rel" "$file"
      check_plugin_manifest_license "Release artifact $zip_name" "$rel" "$file"
    done < <(find "$extract_dir" -type f -print0)

    check_embedded_jar_entries "$zip_name" "$extract_dir"
    case "$zip_name" in
      hexana-mcp-native-*.zip|hexana-cli-native-*.zip|hexana-cli-script-native-*.zip)
        ;;
      *)
        check_obfuscated_runtime_layout "$zip_name" "$extract_dir"
        check_obfuscated_runtime_hardening "$zip_name" "$extract_dir"
        ;;
    esac
    check_release_artifact_legal_files "$zip_name" "$extract_dir"
  done < <(find "$artifact_dir" -maxdepth 1 -type f -name '*.zip' -print0)
  [ "$artifact_count" -gt 0 ] || die "No release ZIP artifacts found in $artifact_dir"
  rm -rf "$security_dir"
}

check_public_bundle_legal_files() {
  [ ! -e "$GENERATED_PUBLIC_DIR/LICENSE.txt" ] || die "Public marketplace bundle must not contain repository-root LICENSE.txt"
  [ ! -e "$GENERATED_PUBLIC_DIR/THIRD-PARTY-NOTICES.md" ] || die "Public marketplace bundle must not contain repository-root THIRD-PARTY-NOTICES.md"
  require_nonempty_file "Public marketplace bundle" "$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME/LICENSE.txt"
  require_nonempty_file "Public marketplace bundle" "$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME/THIRD-PARTY-NOTICES.md"
  require_nonempty_file "Public marketplace bundle" "$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME/server/LICENSE.txt"
  require_nonempty_file "Public marketplace bundle" "$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME/server/THIRD-PARTY-NOTICES.md"
}

check_public_bundle_plugin_manifest_licenses() {
  local manifest="$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME/.claude-plugin/plugin.json"
  require_nonempty_file "Public marketplace bundle" "$manifest"
  check_plugin_manifest_license "Public marketplace bundle" "plugins/$PLUGIN_NAME/.claude-plugin/plugin.json" "$manifest"
}

validate_native_platform_name() {
  local platform="$1"
  case "$platform" in
    ''|*/*|.*|*..*|*[!A-Za-z0-9._-]*)
      die "Native platform must be a simple path segment: $platform"
      ;;
  esac
}

check_public_bundle_native_policy() {
  local plugin_dir="$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME"
  local readme="$plugin_dir/README.md"
  local lock_file="$plugin_dir/release.lock.json"
  local native_dir="$plugin_dir/native"
  local native_file=""
  local native_rel
  local platform
  local platform_count=0
  local expected_platforms_file
  local actual_platforms_file

  require_nonempty_file "Public marketplace bundle" "$readme"
  require_nonempty_file "Public marketplace bundle" "$lock_file"

  if [ -d "$native_dir" ]; then
    native_file="$(find "$native_dir" -type f -print -quit)"
  fi

  if [ -z "$native_file" ]; then
    if grep -F -q '`native/<platform>/hexana-mcp`: optional bundled native executable' "$readme" ||
       grep -F -q 'Native-enabled wrappers also include' "$readme"; then
      die "Public marketplace README claims bundled native files, but plugins/$PLUGIN_NAME/native is missing"
    fi
    if jq -e '.nativeArtifacts.distribution == "marketplace-bundle" or .nativeArtifacts.distribution == "bundled"' "$lock_file" >/dev/null; then
      die "Public marketplace release.lock.json claims bundled native files, but plugins/$PLUGIN_NAME/native is missing"
    fi
    grep -F -q 'Native executables are not included in this public marketplace tree.' "$readme" ||
      die "Public marketplace README must document that native executables are GitHub Release artifacts when plugins/$PLUGIN_NAME/native is absent"
    jq -e '.nativeArtifacts.distribution == "github-release-only"' "$lock_file" >/dev/null ||
      die "Public marketplace release.lock.json must document the GitHub Release-only native artifact policy when plugins/$PLUGIN_NAME/native is absent"
    return
  fi

  if ! jq -e '.nativeArtifacts.distribution == "marketplace-bundle" or .nativeArtifacts.distribution == "bundled"' "$lock_file" >/dev/null; then
    die "Public marketplace release.lock.json must document bundled native artifact policy when plugins/$PLUGIN_NAME/native is present"
  fi

  expected_platforms_file="$(mktemp "${TMPDIR:-/tmp}/hexana-native-platforms-expected.XXXXXX")"
  actual_platforms_file="$(mktemp "${TMPDIR:-/tmp}/hexana-native-platforms-actual.XXXXXX")"
  register_cleanup_dir "$expected_platforms_file"
  register_cleanup_dir "$actual_platforms_file"

  jq -r '.nativeArtifacts.platforms[]?' "$lock_file" | sort > "$expected_platforms_file"
  : > "$actual_platforms_file"
  while IFS= read -r platform; do
    platform_count=$((platform_count + 1))
    validate_native_platform_name "$platform"
    require_nonempty_file "Public marketplace bundle" "$native_dir/$platform/hexana-mcp"
  done < "$expected_platforms_file"

  [ "$platform_count" -gt 0 ] ||
    die "Public marketplace release.lock.json must list bundled native platforms when plugins/$PLUGIN_NAME/native is present"

  while IFS= read -r native_rel; do
    case "$native_rel" in
      */hexana-mcp)
        platform="$(dirname "$native_rel")"
        [ -n "$platform" ] && [ "$platform" != "." ] ||
          die "Public marketplace native executable is missing a platform directory: plugins/$PLUGIN_NAME/native/$native_rel"
        validate_native_platform_name "$platform"
        printf '%s\n' "$platform" >> "$actual_platforms_file"
        ;;
      *)
        die "Public marketplace native bundle contains unexpected file: plugins/$PLUGIN_NAME/native/$native_rel"
        ;;
    esac
  done < <(
    cd "$native_dir"
    find . -type f -print | sed 's#^\./##'
  )
  sort -u "$actual_platforms_file" -o "$actual_platforms_file"

  if ! cmp -s "$expected_platforms_file" "$actual_platforms_file"; then
    die "Public marketplace release.lock.json native platforms do not match plugins/$PLUGIN_NAME/native contents"
  fi
}

check_release_native_artifact_policy() {
  local lock_file="$GENERATED_PUBLIC_DIR/plugins/$PLUGIN_NAME/release.lock.json"
  local artifact
  local expected_count=0

  require_nonempty_file "Public marketplace bundle" "$lock_file"

  if ! jq -e '.nativeArtifacts.distribution == "github-release-only"' "$lock_file" >/dev/null; then
    return
  fi

  [ "$INCLUDE_NATIVE_ARTIFACTS" = "true" ] ||
    die "Public marketplace metadata advertises native GitHub Release artifacts; set INCLUDE_NATIVE_ARTIFACTS=true so the release uploads them."

  while IFS= read -r artifact; do
    expected_count=$((expected_count + 1))
    [ -f "$release_dir/$artifact" ] ||
      die "Public marketplace metadata advertises missing native GitHub Release artifact: $artifact"
  done < <(jq -r '.nativeArtifacts.artifacts[]?' "$lock_file")

  [ "$expected_count" -gt 0 ] ||
    die "Public marketplace metadata uses GitHub Release-only native artifacts, but release.lock.json lists no native artifact files."
}

validate_public_bundle() {
  [ -d "$GENERATED_PUBLIC_DIR" ] || die "Generated public marketplace bundle is missing: $GENERATED_PUBLIC_DIR"
  check_public_bundle_legal_files
  check_public_bundle_plugin_manifest_licenses
  check_public_bundle_native_policy

  find "$GENERATED_PUBLIC_DIR" -type f -name '*.json' -print0 |
    while IFS= read -r -d '' file; do
      jq empty "$file" >/dev/null
    done
}

sanitize_branch_part() {
  local value="$1"
  value="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')"
  value="$(printf '%s' "$value" | sed -E 's/[^a-z0-9._-]+/-/g; s/^[.-]+//; s/[.-]+$//; s/-+/-/g')"
  [ -n "$value" ] || die "Cannot sanitize empty branch part"
  printf '%.80s' "$value"
}

validate_plugin_name_for_path() {
  case "$PLUGIN_NAME" in
    ''|*/*|.*|*..*|*[!A-Za-z0-9._-]*)
      die "PLUGIN_NAME must be a simple marketplace path segment: letters, digits, dot, underscore, dash"
      ;;
  esac
}

make_askpass() {
  local askpass_dir="$1"
  local askpass="$askpass_dir/git-askpass.sh"
  cat > "$askpass" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  *Username*) printf '%s\n' "x-access-token" ;;
  *) printf '%s\n' "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ;;
esac
EOF
  chmod 700 "$askpass"
  export GIT_ASKPASS="$askpass"
  export GIT_TERMINAL_PROMPT=0
}

normalize_dir() {
  local path="$1"
  [ -d "$path" ] || die "Directory does not exist: $path"
  (
    cd "$path" >/dev/null
    pwd -P
  )
}

clone_public_repo() {
  local repo_url="https://github.com/${PUBLIC_REPO_SLUG}.git"

  if [ -n "$PUBLIC_REPO_CLONE_DIR" ]; then
    CLONE_DIR="$PUBLIC_REPO_CLONE_DIR"
    if [ -d "$CLONE_DIR/.git" ]; then
      log "Using existing public repo clone: $CLONE_DIR"
      git -C "$CLONE_DIR" remote set-url origin "$repo_url"
    elif [ -e "$CLONE_DIR" ]; then
      die "PUBLIC_REPO_CLONE_DIR exists but is not a git clone: $CLONE_DIR"
    else
      log "Cloning public repo into: $CLONE_DIR"
      git clone "$repo_url" "$CLONE_DIR"
    fi
  else
    TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/hexana-public-marketplace.XXXXXX")"
    register_cleanup_dir "$TEMP_ROOT"
    CLONE_DIR="$TEMP_ROOT/repo"
    log "Cloning public repo into a temporary directory"
    git clone "$repo_url" "$CLONE_DIR"
  fi

  git -C "$CLONE_DIR" remote -v | grep -F -- "$GH_TOKEN" >/dev/null 2>&1 && die "Token leaked into git remote URL"
  git -C "$CLONE_DIR" fetch origin "$BASE_BRANCH" --prune
  git -C "$CLONE_DIR" checkout -B "$BRANCH" "origin/$BASE_BRANCH"
  git -C "$CLONE_DIR" config user.name "$COMMIT_AUTHOR_NAME"
  git -C "$CLONE_DIR" config user.email "$COMMIT_AUTHOR_EMAIL"
}

sync_file_if_present() {
  local rel="$1"
  local src="$GENERATED_PUBLIC_DIR_ABS/$rel"
  local dst="$CLONE_DIR/$rel"
  if [ -f "$src" ]; then
    mkdir -p "$(dirname "$dst")"
    rsync -a "$src" "$dst"
    SYNCED_PATHS+=("$rel")
  fi
}

sync_dir_if_present() {
  local rel="$1"
  local src="$GENERATED_PUBLIC_DIR_ABS/$rel"
  local dst="$CLONE_DIR/$rel"
  if [ -d "$src" ]; then
    rm -rf "$dst"
    mkdir -p "$dst"
    rsync -a --delete "$src/" "$dst/"
    SYNCED_PATHS+=("$rel")
  fi
}

sync_public_bundle() {
  log "Syncing public marketplace files from $GENERATED_PUBLIC_DIR_ABS"

  sync_file_if_present ".claude-plugin/marketplace.json"
  sync_file_if_present ".agents/plugins/marketplace.json"

  sync_file_if_present "plugins/$PLUGIN_NAME/.mcp.json"
  sync_file_if_present "plugins/$PLUGIN_NAME/release.lock.json"
  sync_file_if_present "plugins/$PLUGIN_NAME/README.md"
  sync_file_if_present "plugins/$PLUGIN_NAME/LICENSE.txt"
  sync_file_if_present "plugins/$PLUGIN_NAME/THIRD-PARTY-NOTICES.md"
  sync_dir_if_present "plugins/$PLUGIN_NAME/.claude-plugin"
  sync_dir_if_present "plugins/$PLUGIN_NAME/.codex-plugin"
  sync_dir_if_present "plugins/$PLUGIN_NAME/skills"
  sync_dir_if_present "plugins/$PLUGIN_NAME/mcp"
  sync_dir_if_present "plugins/$PLUGIN_NAME/bin"
  sync_dir_if_present "plugins/$PLUGIN_NAME/native"
  sync_dir_if_present "plugins/$PLUGIN_NAME/server"

  if [ "${#SYNCED_PATHS[@]}" -eq 0 ]; then
    die "No allowlisted public files were found in GENERATED_PUBLIC_DIR"
  fi
}

is_allowed_public_path() {
  local path="$1"
  case "$path" in
    .claude-plugin/marketplace.json|.agents/plugins/marketplace.json)
      return 0
      ;;
    "plugins/$PLUGIN_NAME/.mcp.json"|"plugins/$PLUGIN_NAME/release.lock.json"|"plugins/$PLUGIN_NAME/README.md"|"plugins/$PLUGIN_NAME/LICENSE.txt"|"plugins/$PLUGIN_NAME/THIRD-PARTY-NOTICES.md")
      return 0
      ;;
    "plugins/$PLUGIN_NAME/.claude-plugin/"*|"plugins/$PLUGIN_NAME/.codex-plugin/"*)
      return 0
      ;;
    "plugins/$PLUGIN_NAME/skills/"*|"plugins/$PLUGIN_NAME/mcp/"*|"plugins/$PLUGIN_NAME/bin/"*|"plugins/$PLUGIN_NAME/native/"*|"plugins/$PLUGIN_NAME/server/"*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

stage_public_paths() {
  (
    cd "$CLONE_DIR"
    for path in "${SYNCED_PATHS[@]}"; do
      git add -- "$path"
    done
  )
}

load_staged_files() {
  local file

  STAGED_FILES=()
  while IFS= read -r file; do
    [ -n "$file" ] && STAGED_FILES+=("$file")
  done < <(git -C "$CLONE_DIR" diff --cached --name-only --diff-filter=ACMRTD)

  STAGED_CONTENT_FILES=()
  while IFS= read -r file; do
    [ -n "$file" ] && STAGED_CONTENT_FILES+=("$file")
  done < <(git -C "$CLONE_DIR" diff --cached --name-only --diff-filter=ACMRT)
}

validate_staged_json() {
  local file
  local json_count=0
  for file in "${STAGED_CONTENT_FILES[@]}"; do
    case "$file" in
      *.json)
        json_count=$((json_count + 1))
        jq empty "$CLONE_DIR/$file" >/dev/null
        ;;
    esac
  done
  VALIDATION_RESULTS+=("JSON validation: $json_count file(s) passed jq empty")
}

run_optional_client_validation() {
  local name="$1"
  local command_env="$2"
  local cli_name="$3"
  local command_value="${!command_env:-}"

  if [ -n "$command_value" ]; then
    log "Running $name validation command"
    (
      cd "$CLONE_DIR"
      bash -lc "$command_value"
    )
    VALIDATION_RESULTS+=("$name validation: passed via $command_env")
    return
  fi

  if command -v "$cli_name" >/dev/null 2>&1; then
    warn "$cli_name CLI is installed, but $command_env is not set; skipping $name validation because no stable validation command was configured"
    VALIDATION_RESULTS+=("$name validation: skipped, $command_env not configured")
  else
    warn "$cli_name CLI is not installed and $command_env is not set; skipping $name validation"
    VALIDATION_RESULTS+=("$name validation: skipped, CLI unavailable")
  fi
}

validate_plugin_manifests() {
  run_optional_client_validation "Claude" "VALIDATE_CLAUDE_CMD" "claude"
  run_optional_client_validation "Codex" "VALIDATE_CODEX_CMD" "codex"
}

check_allowed_paths() {
  local file
  for file in "${STAGED_FILES[@]}"; do
    if ! is_allowed_public_path "$file"; then
      die "Staged file is outside the public marketplace allowlist: $file"
    fi
  done
}

check_suspicious_paths() {
  local file
  for file in "${STAGED_FILES[@]}"; do
    case "$file" in
      src/*|*/src/*|build/*|*/build/*|target/*|*/target/*|out/*|*/out/*|.gradle/*|*/.gradle/*|.idea/*|*/.idea/*|.teamcity/*|*/.teamcity/*|.env|*/.env|private/*|*/private/*|secret/*|*/secret/*|secrets/*|*/secrets/*|*.java|*.kt|*.class|*.pem|*.key|*.p12|*.keystore|*.jks)
        die "Refusing to publish suspicious staged path: $file"
        ;;
      *.jar)
        if [ "$ALLOW_BUNDLED_JAR" != "true" ]; then
          die "Refusing to publish jar while ALLOW_BUNDLED_JAR=false: $file"
        fi
        case "$file" in
          "plugins/$PLUGIN_NAME/server/lib/"*) ;;
          *) die "Jar files are allowed only under plugins/$PLUGIN_NAME/server/lib/: $file" ;;
        esac
        ;;
    esac
  done
}

check_staged_symlinks() {
  local symlink
  if [ "${#STAGED_CONTENT_FILES[@]}" -eq 0 ]; then
    return
  fi
  symlink="$(git -C "$CLONE_DIR" ls-files -s -- "${STAGED_CONTENT_FILES[@]}" | awk '$1 == "120000" { print $4; exit }')"
  if [ -n "$symlink" ]; then
    die "Refusing to publish symlink: $symlink"
  fi
}

check_secret_patterns() {
  local regex
  local file
  local matches

  regex='BEGIN PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|sk-|AKIA|password=|secret=|token=|/Users/|/private/'"${LEAK_GUARD_INTERNAL_NAMES_BRANCH?leak guard internal names are not initialised}"'|git\.jetbrains\.team|GH_TOKEN|GITHUB_TOKEN'
  if [ "${#STAGED_CONTENT_FILES[@]}" -eq 0 ]; then
    return
  fi
  for file in "${STAGED_CONTENT_FILES[@]}"; do
    [ -f "$CLONE_DIR/$file" ] || continue
    set +e
    matches="$(grep -I -n -E -- "$regex" "$CLONE_DIR/$file" 2>/dev/null)"
    local status=$?
    set -e
    if [ "$status" -eq 0 ]; then
      printf '%s\n' "$matches" | sed "s#^#$file:#" >&2
      die "Secret scan matched staged content"
    fi
    if [ "$status" -ne 1 ]; then
      die "Secret scan failed for $file"
    fi
  done
}

check_staged_mcp_json_command_paths() {
  local file
  for file in "${STAGED_CONTENT_FILES[@]}"; do
    [ -f "$CLONE_DIR/$file" ] || continue
    check_mcp_json_command_paths "Public marketplace diff" "$file" "$CLONE_DIR/$file"
  done
}

check_staged_plugin_manifest_licenses() {
  local file
  for file in "${STAGED_CONTENT_FILES[@]}"; do
    [ -f "$CLONE_DIR/$file" ] || continue
    check_plugin_manifest_license "Public marketplace diff" "$file" "$CLONE_DIR/$file"
  done
}

run_safety_checks() {
  log "Running staged diff safety checks"
  check_allowed_paths
  check_suspicious_paths
  check_staged_symlinks
  check_secret_patterns
  check_staged_mcp_json_command_paths
  check_staged_plugin_manifest_licenses
  git -C "$CLONE_DIR" diff --cached --check
}

join_lines_for_pr() {
  local file
  for file in "${STAGED_FILES[@]}"; do
    printf -- '- `%s`\n' "$file"
  done
}

release_lock_summary() {
  local lock_file="$CLONE_DIR/plugins/$PLUGIN_NAME/release.lock.json"
  if [ ! -f "$lock_file" ]; then
    printf 'No `release.lock.json` was included.'
    return
  fi

  local digest
  if command -v shasum >/dev/null 2>&1; then
    digest="$(shasum -a 256 "$lock_file" | awk '{ print $1 }')"
  else
    digest="$(sha256sum "$lock_file" | awk '{ print $1 }')"
  fi
  printf '`plugins/%s/release.lock.json` sha256: `%s`' "$PLUGIN_NAME" "$digest"
}

write_pr_body() {
  PR_BODY_FILE="$CLONE_DIR/.public-marketplace-pr-body.md"
  {
    printf 'Automated public marketplace distribution update.\n\n'
    printf -- '- Plugin: `%s`\n' "$PLUGIN_NAME"
    printf -- '- Version: `%s`\n' "$PLUGIN_VERSION"
    printf -- '- Release tag: `%s`\n' "$RELEASE_TAG"
    printf '\n'
    printf 'Updated public files:\n'
    join_lines_for_pr
    printf '\n'
    printf 'Smoke tests:\n'
    printf -- '- Release tests and plugin smoke tests passed before publication.\n'
    printf '\n'
    printf 'Release lock/checksum:\n'
    printf -- '- %s\n' "$(release_lock_summary)"
    printf '\n'
    printf 'This PR is intended to contain only public distribution metadata, manifests, wrapper scripts, skills, MCP config, and approved public bundled files. It must not contain private source code, CI internals, secrets, or private build artifacts.\n'
  } > "$PR_BODY_FILE"
  require_public_github_text_file "GitHub PR body" "$PR_BODY_FILE"
}

public_pr_update_comment() {
  printf 'Updated by release automation for Hexana %s.' "$PLUGIN_VERSION"
}

apply_pr_metadata() {
  local pr_number="$1"
  local label
  local reviewer

  if [ -n "$PR_LABELS" ]; then
    IFS=',' read -r -a label_array <<< "$PR_LABELS"
    for label in "${label_array[@]}"; do
      label="${label#"${label%%[![:space:]]*}"}"
      label="${label%"${label##*[![:space:]]}"}"
      [ -n "$label" ] && gh pr edit "$pr_number" --repo "$PUBLIC_REPO_SLUG" --add-label "$label"
    done
  fi

  if [ -n "$PR_REVIEWERS" ]; then
    IFS=',' read -r -a reviewer_array <<< "$PR_REVIEWERS"
    for reviewer in "${reviewer_array[@]}"; do
      reviewer="${reviewer#"${reviewer%%[![:space:]]*}"}"
      reviewer="${reviewer%"${reviewer##*[![:space:]]}"}"
      [ -n "$reviewer" ] && gh pr edit "$pr_number" --repo "$PUBLIC_REPO_SLUG" --add-reviewer "$reviewer"
    done
  fi
}

create_or_update_pr() {
  local title="Publish ${PLUGIN_NAME} ${PLUGIN_VERSION}"
  local existing_json
  local pr_number
  local pr_url
  local pr_update_comment

  check_public_github_text "GitHub PR title" "$title"
  write_pr_body
  existing_json="$(gh pr list \
    --repo "$PUBLIC_REPO_SLUG" \
    --head "$BRANCH" \
    --base "$BASE_BRANCH" \
    --state open \
    --json number,url \
    --jq '.[0] // empty')"

  if [ -n "$existing_json" ]; then
    pr_number="$(printf '%s' "$existing_json" | jq -r '.number')"
    pr_url="$(printf '%s' "$existing_json" | jq -r '.url')"
    log "Updating existing PR #$pr_number"
    gh pr edit "$pr_number" --repo "$PUBLIC_REPO_SLUG" --title "$title" --body-file "$PR_BODY_FILE"
    pr_update_comment="$(public_pr_update_comment)"
    check_public_github_text "GitHub PR update comment" "$pr_update_comment"
    gh pr comment "$pr_number" --repo "$PUBLIC_REPO_SLUG" --body "$pr_update_comment"
  else
    log "Creating pull request"
    pr_url="$(gh pr create \
      --repo "$PUBLIC_REPO_SLUG" \
      --base "$BASE_BRANCH" \
      --head "$BRANCH" \
      --title "$title" \
      --body-file "$PR_BODY_FILE")"
    pr_number="$(gh pr view "$pr_url" --repo "$PUBLIC_REPO_SLUG" --json number --jq '.number')"
  fi

  apply_pr_metadata "$pr_number"
  PR_NUMBER="$pr_number"
  PR_URL="$pr_url"
}

enable_auto_merge_if_requested() {
  if [ "$AUTO_MERGE" != "true" ]; then
    AUTO_MERGE_STATUS="not requested"
    return
  fi
  if [ -z "${PR_NUMBER:-}" ]; then
    AUTO_MERGE_STATUS="not requested, no marketplace PR changes"
    return
  fi

  log "Enabling auto-merge with rebase for PR #$PR_NUMBER"
  gh pr merge "$PR_NUMBER" --repo "$PUBLIC_REPO_SLUG" --auto --rebase --delete-branch
  AUTO_MERGE_STATUS="queued with rebase"
}

print_summary() {
  printf '\n'
  log "Summary"
  printf '  Mode: %s\n' "$([ "$DRY_RUN" = "true" ] && printf 'dry run' || printf 'real run')"
  printf '  Publication mode: %s\n' "$PUBLICATION_MODE"
  printf '  Public repository: %s\n' "$PUBLIC_REPO_SLUG"
  printf '  Release tag: %s\n' "$RELEASE_TAG"
  printf '  Branch: %s\n' "$BRANCH"
  printf '  Base branch: %s\n' "$BASE_BRANCH"
  if [ -n "${PR_URL:-}" ]; then
    printf '  PR: %s\n' "$PR_URL"
  fi
  printf '  Auto-merge: %s\n' "${AUTO_MERGE_STATUS:-not requested}"
  printf '  Changed files:\n'
  local file
  for file in "${STAGED_FILES[@]:-}"; do
    printf '    - %s\n' "$file"
  done
}

prepare_public_marketplace_pr() {
  validate_plugin_name_for_path

  GENERATED_PUBLIC_DIR_ABS="$(normalize_dir "$GENERATED_PUBLIC_DIR")"
  BASE_BRANCH="$PUBLIC_REPO_BASE_BRANCH"
  BRANCH="automation/update-$(sanitize_branch_part "$PLUGIN_NAME")-$(sanitize_branch_part "$PLUGIN_VERSION")"
  case "$BRANCH" in
    "$BASE_BRANCH"|main|master)
      die "Refusing to use protected branch as automation branch: $BRANCH"
      ;;
  esac

  TEMP_ROOT=""
  ASKPASS_DIR=""

  ASKPASS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hexana-git-askpass.XXXXXX")"
  chmod 700 "$ASKPASS_DIR"
  register_cleanup_dir "$ASKPASS_DIR"
  make_askpass "$ASKPASS_DIR"
  gh auth status -h github.com >/dev/null 2>&1 || die "gh is not authenticated; provide a valid GH_TOKEN/GITHUB_TOKEN"

  SYNCED_PATHS=()
  VALIDATION_RESULTS=()
  STAGED_FILES=()
  STAGED_CONTENT_FILES=()
  PR_URL=""
  PR_NUMBER=""
  AUTO_MERGE_STATUS="not requested"
  PUBLIC_MARKETPLACE_HAS_CHANGES="false"

  clone_public_repo
  sync_public_bundle
  stage_public_paths
  load_staged_files

  if [ "${#STAGED_FILES[@]}" -eq 0 ] || git -C "$CLONE_DIR" diff --cached --quiet; then
    log "No public marketplace changes"
    return
  fi

  validate_staged_json
  validate_plugin_manifests
  run_safety_checks
  PUBLIC_MARKETPLACE_HAS_CHANGES="true"
}

publish_public_marketplace_pr() {
  if [ "${PUBLIC_MARKETPLACE_HAS_CHANGES:-false}" != "true" ]; then
    return
  fi

  if [ "$DRY_RUN" = "true" ]; then
    log "Dry run requested; not committing, pushing, or updating PR"
    git -C "$CLONE_DIR" diff --cached --stat
    write_pr_body
    check_public_github_text "GitHub PR update comment" "$(public_pr_update_comment)"
    return
  fi

  git -C "$CLONE_DIR" commit -m "chore: publish ${PLUGIN_NAME} ${PLUGIN_VERSION}"
  log "Pushing branch $BRANCH"
  git -C "$CLONE_DIR" push --force-with-lease origin "HEAD:refs/heads/$BRANCH"
  create_or_update_pr
}

main() {
  if [ -n "${RELEASE_ARTIFACT_SECURITY_CHECK_DIR:-}" ]; then
    init_leak_guard_internal_names "optional"
    require_release_artifact_security_tools
    security_check_release_artifacts "$RELEASE_ARTIFACT_SECURITY_CHECK_DIR"
    return
  fi
  if [ "$(to_bool "${PUBLIC_GITHUB_TEXT_GUARD_SELF_TEST:-false}" "PUBLIC_GITHUB_TEXT_GUARD_SELF_TEST")" = "true" ]; then
    self_test_public_github_text_guard
    return
  fi
  if [ "$(to_bool "${RELEASE_NATIVE_ARTIFACT_POLICY_SELF_TEST:-false}" "RELEASE_NATIVE_ARTIFACT_POLICY_SELF_TEST")" = "true" ]; then
    require_tool "jq"
    self_test_release_native_artifact_policy
    self_test_public_bundle_native_policy
    return
  fi
  if [ "$(to_bool "${RELEASE_NATIVE_ARTIFACT_DEFAULT_SELF_TEST:-false}" "RELEASE_NATIVE_ARTIFACT_DEFAULT_SELF_TEST")" = "true" ]; then
    self_test_release_native_artifact_default
    return
  fi
  if [ "$(to_bool "${MCP_CHANGELOG_RELEASE_NOTES_SELF_TEST:-false}" "MCP_CHANGELOG_RELEASE_NOTES_SELF_TEST")" = "true" ]; then
    self_test_mcp_changelog_release_notes
    return
  fi
  if [ "$(to_bool "${PUBLICATION_MODE_GUARD_SELF_TEST:-false}" "PUBLICATION_MODE_GUARD_SELF_TEST")" = "true" ]; then
    self_test_publication_mode_guard
    return
  fi
  if [ "$(to_bool "${EXISTING_RELEASE_ASSET_IMMUTABILITY_SELF_TEST:-false}" "EXISTING_RELEASE_ASSET_IMMUTABILITY_SELF_TEST")" = "true" ]; then
    self_test_existing_release_asset_immutability
    return
  fi

  require_tool "./gradlew"
  require_tool "gh"
  require_tool "jq"
  require_tool "git"
  require_tool "jar"
  require_tool "rsync"
  require_tool "strings"
  require_tool "unzip"

  GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
  export GH_TOKEN
  require_env "GH_TOKEN"
  require_env "PUBLIC_REPO_SLUG"
  init_leak_guard_internal_names "required"

  mcp_version="$(read_mcp_version)"
  [ -n "$mcp_version" ] || die "Could not read mcpServerVersion from mcp/server/gradle.properties"

  export PLUGIN_NAME="${PLUGIN_NAME:-hexana}"
  export PLUGIN_VERSION="${PLUGIN_VERSION:-$mcp_version}"
  export GENERATED_PUBLIC_DIR="${GENERATED_PUBLIC_DIR:-mcp/out/teamcity/public-marketplace}"
  export PUBLIC_REPO_BASE_BRANCH="${PUBLIC_REPO_BASE_BRANCH:-master}"
  export AUTO_MERGE="$(to_bool "${AUTO_MERGE:-false}" "AUTO_MERGE")"
  export DRY_RUN="$(to_bool "${DRY_RUN:-false}" "DRY_RUN")"
  export PUBLICATION_MODE="${PUBLICATION_MODE:-production}"
  export PRODUCTION_PUBLIC_REPO_SLUG="${PRODUCTION_PUBLIC_REPO_SLUG:-JetBrains/hexana}"
  export ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO="$(to_bool "${ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO:-false}" "ALLOW_PRODUCTION_TO_NON_CANONICAL_REPO")"
  export RELEASE_TAG="${RELEASE_TAG:-}"
  validate_publication_mode
  export ALLOW_BUNDLED_JAR="$(to_bool "${ALLOW_BUNDLED_JAR:-false}" "ALLOW_BUNDLED_JAR")"
  export INCLUDE_NATIVE_ARTIFACTS="$(to_bool "${INCLUDE_NATIVE_ARTIFACTS:-true}" "INCLUDE_NATIVE_ARTIFACTS")"
  export NATIVE_RELEASE_ARTIFACT_DIR="${NATIVE_RELEASE_ARTIFACT_DIR:-mcp/build/distributions-native}"
  export NATIVE_RELEASE_PLATFORMS="${NATIVE_RELEASE_PLATFORMS:-macos-arm64 linux-x64}"
  PUBLIC_REPO_CLONE_DIR="${PUBLIC_REPO_CLONE_DIR:-}"
  COMMIT_AUTHOR_NAME="${COMMIT_AUTHOR_NAME:-}"
  COMMIT_AUTHOR_EMAIL="${COMMIT_AUTHOR_EMAIL:-}"
  require_env "COMMIT_AUTHOR_NAME"
  require_env "COMMIT_AUTHOR_EMAIL"
  case "$COMMIT_AUTHOR_EMAIL" in
    *[[:space:]]*|*"<"*|*">"*|*".."*|*","*|*"("*|*")"*|*";"*|*":"*)
      die "COMMIT_AUTHOR_EMAIL contains characters that are unsafe for git author metadata"
      ;;
    *@*) ;;
    *) die "COMMIT_AUTHOR_EMAIL must look like an email address" ;;
  esac
  PR_LABELS="${PR_LABELS:-}"
  PR_REVIEWERS="${PR_REVIEWERS:-}"
  VALIDATE_CLAUDE_CMD="${VALIDATE_CLAUDE_CMD:-}"
  VALIDATE_CODEX_CMD="${VALIDATE_CODEX_CMD:-}"
  SKIP_RELEASE_BUILD_AND_SMOKE="$(to_bool "${SKIP_RELEASE_BUILD_AND_SMOKE:-false}" "SKIP_RELEASE_BUILD_AND_SMOKE")"
  if [ "$SKIP_RELEASE_BUILD_AND_SMOKE" = "true" ]; then
    log "Skipping Gradle package build and smoke gates because SKIP_RELEASE_BUILD_AND_SMOKE=true"
  else
    log "Building and smoke-testing plain MCP package artifacts"
    ./gradlew --no-daemon \
      :mcp:assembleMcpPackagingPlain \
      :mcp:server:plainMcpPackagingSmoke

    log "Building and smoke-testing R8-obfuscated MCP package artifacts"
    ./gradlew --no-daemon \
      :mcp:assembleMcpPackaging \
      :mcp:server:obfuscatedMcpPackagingSmoke

    log "Building and verifying split CLI release archives"
    ./gradlew --no-daemon \
      :mcp:verifyCliR8ReleaseArchives
  fi

  collect_release_artifacts
  security_check_release_artifacts "$release_dir"
  validate_public_bundle
  check_release_native_artifact_policy
  write_release_notes
  prepare_public_marketplace_pr
  publish_public_marketplace_pr
  publish_github_release
  enable_auto_merge_if_requested
  print_summary
  log "Release artifacts:"
  ls -la "$release_dir"
}

main "$@"
