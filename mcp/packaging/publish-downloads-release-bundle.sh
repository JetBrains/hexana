#!/usr/bin/env bash
set -euo pipefail
set +x

log() {
  printf '[mcp-downloads-release] %s\n' "$*"
}

die() {
  printf '[mcp-downloads-release] ERROR: %s\n' "$*" >&2
  exit 1
}

require_tool() {
  command -v "$1" >/dev/null 2>&1 || die "Required tool '$1' is not installed"
}

to_bool() {
  case "${1:-false}" in
    true|TRUE|True|1|yes|YES|Yes) printf 'true' ;;
    false|FALSE|False|0|no|NO|No|'') printf 'false' ;;
    *) die "Expected boolean value for $2, got '$1'" ;;
  esac
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

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{ print $1 }'
  else
    sha256sum "$file" | awk '{ print $1 }'
  fi
}

file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

validate_path_segment() {
  local name="$1"
  local value="$2"
  case "$value" in
    ''|.*|*/*|*..*|*[!A-Za-z0-9._-]*)
      die "$name must be a simple path segment, got: $value"
      ;;
  esac
}

require_one_artifact() {
  local dir="$1"
  local file_name="$2"
  local file="$dir/$file_name"
  [ -f "$file" ] || die "Missing release artifact: $file"
  [ -s "$file" ] || die "Release artifact is empty: $file"
  printf '%s\n' "$file"
}

artifact_kind_for_name() {
  local file_name="$1"
  case "$file_name" in
    hexana-mcp-native-*) printf 'native-executable' ;;
    hexana-claude-plugin-*) printf 'claude-plugin' ;;
    hexana-codex-plugin-*) printf 'codex-plugin' ;;
    *) die "Unexpected MCP release artifact name: $file_name" ;;
  esac
}

url_join() {
  local base="$1"
  local file_name="$2"
  if [ -z "$base" ]; then
    printf ''
    return
  fi
  printf '%s/%s' "${base%/}" "$file_name"
}

prepare_manifest_json() {
  local manifest_tmp="$1"
  jq -n \
    --arg version "$MCP_VERSION" \
    --arg gitRevision "$GIT_REVISION" \
    --arg teamcityBuildId "$TEAMCITY_BUILD_ID" \
    --arg teamcityBuildUrl "$TEAMCITY_BUILD_URL" \
    --arg downloadsPrefix "$DOWNLOADS_PUBLIC_PREFIX" \
    --arg generatedAt "$GENERATED_AT" \
    '{
      version: $version,
      gitRevision: (if ($gitRevision | length) > 0 then $gitRevision else null end),
      teamcity: {
        buildId: (if ($teamcityBuildId | length) > 0 then $teamcityBuildId else null end),
        buildUrl: (if ($teamcityBuildUrl | length) > 0 then $teamcityBuildUrl else null end)
      },
      downloadsPrefix: $downloadsPrefix,
      generatedAt: $generatedAt,
      artifacts: []
    }' > "$manifest_tmp"
}

append_manifest_artifact() {
  local manifest_tmp="$1"
  local platform="$2"
  local file_name="$3"
  local kind="$4"
  local size="$5"
  local sha256="$6"
  local github_url="$7"
  local downloads_url="$8"
  local next_manifest="$manifest_tmp.next"

  jq \
    --arg name "$file_name" \
    --arg platform "$platform" \
    --arg kind "$kind" \
    --argjson size "$size" \
    --arg sha256 "$sha256" \
    --arg githubReleaseAssetUrl "$github_url" \
    --arg downloadsUrl "$downloads_url" \
    '.artifacts += [{
      name: $name,
      platform: $platform,
      kind: $kind,
      size: $size,
      sha256: $sha256,
      githubReleaseAssetUrl: (if ($githubReleaseAssetUrl | length) > 0 then $githubReleaseAssetUrl else null end),
      downloadsUrl: $downloadsUrl
    }]' "$manifest_tmp" > "$next_manifest"
  mv "$next_manifest" "$manifest_tmp"
}

generate_downloads_bundle() {
  local artifact_dir="$1"
  local bundle_dir="$2"
  local manifest_name="hexana-mcp-release-$MCP_VERSION.json"
  local manifest_tmp
  local platform
  local artifact
  local source_file
  local bundled_file
  local digest
  local size
  local kind
  local github_url
  local downloads_url

  validate_path_segment "MCP_VERSION" "$MCP_VERSION"
  rm -rf "$bundle_dir"
  mkdir -p "$bundle_dir"
  manifest_tmp="$bundle_dir/$manifest_name.tmp"
  prepare_manifest_json "$manifest_tmp"

  for platform in $MCP_RELEASE_PLATFORMS; do
    validate_path_segment "MCP release platform" "$platform"
    for artifact in \
      "hexana-mcp-native-$platform-$MCP_VERSION.zip" \
      "hexana-claude-plugin-$platform-$MCP_VERSION.zip" \
      "hexana-codex-plugin-$platform-$MCP_VERSION.zip"; do
      source_file="$(require_one_artifact "$artifact_dir" "$artifact")"
      bundled_file="$bundle_dir/$artifact"
      cp "$source_file" "$bundled_file"
      digest="$(sha256_file "$bundled_file")"
      size="$(file_size "$bundled_file")"
      kind="$(artifact_kind_for_name "$artifact")"
      printf '%s  %s\n' "$digest" "$artifact" > "$bundle_dir/$artifact.sha256"
      github_url="$(url_join "$GITHUB_RELEASE_ASSET_BASE_URL" "$artifact")"
      downloads_url="$(url_join "$DOWNLOADS_PUBLIC_PREFIX" "$artifact")"
      append_manifest_artifact "$manifest_tmp" "$platform" "$artifact" "$kind" "$size" "$digest" "$github_url" "$downloads_url"
    done
  done

  jq empty "$manifest_tmp"
  mv "$manifest_tmp" "$bundle_dir/$manifest_name"
  log "Prepared downloads release bundle in $bundle_dir"
  find "$bundle_dir" -maxdepth 1 -type f -print | sort
}

publish_downloads_bundle() {
  local bundle_dir="$1"
  local destination="$2"

  require_tool "aws"
  [ -d "$bundle_dir" ] || die "Bundle directory does not exist: $bundle_dir"
  case "$destination" in
    s3://downloads-bucket.example.invalid/resources/hexana/mcp/*) ;;
    *) die "Refusing to publish outside the Hexana MCP downloads prefix: $destination" ;;
  esac

  log "Uploading bundle to $destination"
  aws s3 cp "$bundle_dir" "$destination" --recursive --only-show-errors
}

verify_checksum_files() {
  local dir="$1"
  if command -v shasum >/dev/null 2>&1; then
    (cd "$dir" && shasum -a 256 -c *.sha256 >/dev/null)
  else
    (cd "$dir" && sha256sum -c *.sha256 >/dev/null)
  fi
}

self_test_downloads_bundle() {
  local temp_dir
  local artifact_dir
  local bundle_dir
  local manifest
  local artifact_count
  local sha_count

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-mcp-downloads-bundle.XXXXXX")"
  trap "rm -rf '$temp_dir'" RETURN
  artifact_dir="$temp_dir/artifacts"
  bundle_dir="$temp_dir/bundle"
  mkdir -p "$artifact_dir"

  printf 'native fixture\n' > "$artifact_dir/hexana-mcp-native-linux-x64-0.2.0.zip"
  printf 'claude fixture\n' > "$artifact_dir/hexana-claude-plugin-linux-x64-0.2.0.zip"
  printf 'codex fixture\n' > "$artifact_dir/hexana-codex-plugin-linux-x64-0.2.0.zip"

  MCP_VERSION="0.2.0"
  MCP_RELEASE_PLATFORMS="linux-x64"
  DOWNLOADS_PUBLIC_PREFIX="https://download.jetbrains.com/resources/hexana/mcp/$MCP_VERSION"
  GITHUB_RELEASE_ASSET_BASE_URL="https://github.com/JetBrains/hexana/releases/download/v$MCP_VERSION"
  TEAMCITY_BUILD_ID="12345"
  TEAMCITY_BUILD_URL="https://ci.example.invalid/buildConfiguration/InnoHub_HexFlow_McpPublishDownloads/12345"
  GIT_REVISION="abcdef1234567890"
  GENERATED_AT="2026-06-23T00:00:00Z"

  generate_downloads_bundle "$artifact_dir" "$bundle_dir"
  manifest="$bundle_dir/hexana-mcp-release-0.2.0.json"
  artifact_count="$(jq '.artifacts | length' "$manifest")"
  [ "$artifact_count" -eq 3 ] || die "Self-test expected 3 manifest artifacts, got $artifact_count"
  jq -e '.version == "0.2.0"' "$manifest" >/dev/null
  jq -e '.teamcity.buildId == "12345"' "$manifest" >/dev/null
  jq -e '.artifacts[] | select(.name == "hexana-mcp-native-linux-x64-0.2.0.zip" and .platform == "linux-x64" and .kind == "native-executable" and (.sha256 | test("^[0-9a-f]{64}$")))' "$manifest" >/dev/null
  sha_count="$(find "$bundle_dir" -maxdepth 1 -type f -name '*.sha256' | wc -l | tr -d '[:space:]')"
  [ "$sha_count" -eq 3 ] || die "Self-test expected 3 checksum files, got $sha_count"
  verify_checksum_files "$bundle_dir"

  log "Downloads bundle self-test passed"
}

main() {
  require_tool "jq"
  if [ "$(to_bool "${MCP_DOWNLOADS_BUNDLE_SELF_TEST:-false}" "MCP_DOWNLOADS_BUNDLE_SELF_TEST")" = "true" ]; then
    self_test_downloads_bundle
    return
  fi

  MCP_VERSION="${MCP_VERSION:-${PLUGIN_VERSION:-$(read_mcp_version)}}"
  [ -n "$MCP_VERSION" ] || die "Could not determine MCP_VERSION"
  MCP_RELEASE_PLATFORMS="${MCP_RELEASE_PLATFORMS:-${NATIVE_RELEASE_PLATFORMS:-macos-arm64 linux-x64}}"
  MCP_RELEASE_ARTIFACT_DIR="${MCP_RELEASE_ARTIFACT_DIR:-${NATIVE_RELEASE_ARTIFACT_DIR:-mcp/build/distributions-native}}"
  MCP_DOWNLOADS_BUNDLE_DIR="${MCP_DOWNLOADS_BUNDLE_DIR:-mcp/out/teamcity/downloads-release}"
  DOWNLOADS_PUBLIC_PREFIX="${DOWNLOADS_PUBLIC_PREFIX:-https://download.jetbrains.com/resources/hexana/mcp/$MCP_VERSION}"
  DOWNLOADS_S3_PREFIX="${DOWNLOADS_S3_PREFIX:-s3://downloads-bucket.example.invalid/resources/hexana/mcp/$MCP_VERSION/}"
  GITHUB_RELEASE_ASSET_BASE_URL="${GITHUB_RELEASE_ASSET_BASE_URL:-}"
  if [ -z "$GITHUB_RELEASE_ASSET_BASE_URL" ] && [ -n "${PUBLIC_REPO_SLUG:-}" ]; then
    RELEASE_TAG="${RELEASE_TAG:-v$MCP_VERSION}"
    GITHUB_RELEASE_ASSET_BASE_URL="https://github.com/$PUBLIC_REPO_SLUG/releases/download/$RELEASE_TAG"
  fi
  TEAMCITY_BUILD_ID="${TEAMCITY_BUILD_ID:-${BUILD_ID:-}}"
  TEAMCITY_BUILD_URL="${TEAMCITY_BUILD_URL:-${BUILD_URL:-}}"
  GIT_REVISION="${GIT_REVISION:-${BUILD_VCS_NUMBER:-}}"
  GENERATED_AT="${GENERATED_AT:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}"
  PUBLISH_DOWNLOADS="$(to_bool "${PUBLISH_DOWNLOADS:-false}" "PUBLISH_DOWNLOADS")"

  generate_downloads_bundle "$MCP_RELEASE_ARTIFACT_DIR" "$MCP_DOWNLOADS_BUNDLE_DIR"

  if [ "$PUBLISH_DOWNLOADS" = "true" ]; then
    publish_downloads_bundle "$MCP_DOWNLOADS_BUNDLE_DIR" "$DOWNLOADS_S3_PREFIX"
  else
    log "PUBLISH_DOWNLOADS=false; upload skipped"
  fi
}

main "$@"
