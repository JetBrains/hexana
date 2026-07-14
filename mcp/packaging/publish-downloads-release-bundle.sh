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
    hexana-cli-native-*) printf 'standard-cli-native' ;;
    hexana-cli-script-native-*) printf 'script-cli-native' ;;
    hexana-claude-plugin-*) printf 'claude-plugin' ;;
    hexana-codex-plugin-*) printf 'codex-plugin' ;;
    *) die "Unexpected MCP release artifact name: $file_name" ;;
  esac
}

artifact_product_for_name() {
  local file_name="$1"
  case "$file_name" in
    hexana-mcp-native-*) printf 'hexana-mcp' ;;
    hexana-cli-native-*) printf 'hexana-cli' ;;
    hexana-cli-script-native-*) printf 'hexana-cli-script' ;;
    hexana-claude-plugin-*) printf 'hexana-claude-plugin' ;;
    hexana-codex-plugin-*) printf 'hexana-codex-plugin' ;;
    *) die "Unexpected MCP release artifact name: $file_name" ;;
  esac
}

artifact_launcher_for_name() {
  local file_name="$1"
  case "$file_name" in
    hexana-mcp-native-*) printf 'hexana-mcp' ;;
    hexana-cli-native-*) printf 'hexana-cli' ;;
    hexana-cli-script-native-*) printf 'hexana-cli-script' ;;
    hexana-claude-plugin-*) printf 'claude-plugin' ;;
    hexana-codex-plugin-*) printf 'codex-plugin' ;;
    *) die "Unexpected MCP release artifact name: $file_name" ;;
  esac
}

artifact_runtime_kind_for_name() {
  local file_name="$1"
  case "$file_name" in
    hexana-mcp-native-*|hexana-cli-native-*|hexana-cli-script-native-*) printf 'native' ;;
    hexana-claude-plugin-*|hexana-codex-plugin-*) printf 'native-wrapper' ;;
    *) die "Unexpected MCP release artifact name: $file_name" ;;
  esac
}

artifact_minimum_java_json_for_name() {
  local file_name="$1"
  case "$file_name" in
    hexana-mcp-native-*|hexana-cli-native-*|hexana-cli-script-native-*) printf 'null' ;;
    hexana-claude-plugin-*|hexana-codex-plugin-*) printf '21' ;;
    *) die "Unexpected MCP release artifact name: $file_name" ;;
  esac
}

zip_entry_sha256() {
  local zip_file="$1"
  local entry_name="$2"
  local extracted_file
  local digest

  extracted_file="$(mktemp "${TMPDIR:-/tmp}/hexana-mcp-zip-entry.XXXXXX")"
  if ! unzip -p "$zip_file" "$entry_name" > "$extracted_file"; then
    rm -f "$extracted_file"
    die "Artifact $zip_file is missing required ZIP entry: $entry_name"
  fi
  if [ ! -s "$extracted_file" ]; then
    rm -f "$extracted_file"
    die "Artifact $zip_file has empty required ZIP entry: $entry_name"
  fi

  digest="$(sha256_file "$extracted_file")"
  rm -f "$extracted_file"
  printf '%s\n' "$digest"
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

s3_join() {
  local base="$1"
  local file_name="$2"
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
  local product="$9"
  local launcher="${10}"
  local runtime_kind="${11}"
  local minimum_java="${12}"
  local license_file_sha256="${13}"
  local third_party_notices_sha256="${14}"
  local next_manifest="$manifest_tmp.next"

  jq \
    --arg name "$file_name" \
    --arg platform "$platform" \
    --arg kind "$kind" \
    --argjson size "$size" \
    --arg sha256 "$sha256" \
    --arg githubReleaseAssetUrl "$github_url" \
    --arg downloadsUrl "$downloads_url" \
    --arg product "$product" \
    --arg launcher "$launcher" \
    --arg runtimeKind "$runtime_kind" \
    --argjson minimumJava "$minimum_java" \
    --arg licenseFileSha256 "$license_file_sha256" \
    --arg thirdPartyNoticesSha256 "$third_party_notices_sha256" \
    '.artifacts += [{
      name: $name,
      platform: $platform,
      kind: $kind,
      size: $size,
      sha256: $sha256,
      githubReleaseAssetUrl: (if ($githubReleaseAssetUrl | length) > 0 then $githubReleaseAssetUrl else null end),
      downloadsUrl: $downloadsUrl,
      product: $product,
      launcher: $launcher,
      runtimeKind: $runtimeKind,
      minimumJava: $minimumJava,
      licenseFileSha256: $licenseFileSha256,
      thirdPartyNoticesSha256: $thirdPartyNoticesSha256
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
  local product
  local launcher
  local runtime_kind
  local minimum_java
  local license_file_sha256
  local third_party_notices_sha256

  require_tool "unzip"
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
      "hexana-codex-plugin-$platform-$MCP_VERSION.zip" \
      "hexana-cli-native-$platform-$MCP_VERSION.zip" \
      "hexana-cli-script-native-$platform-$MCP_VERSION.zip"; do
      source_file="$(require_one_artifact "$artifact_dir" "$artifact")"
      bundled_file="$bundle_dir/$artifact"
      cp "$source_file" "$bundled_file"
      digest="$(sha256_file "$bundled_file")"
      size="$(file_size "$bundled_file")"
      kind="$(artifact_kind_for_name "$artifact")"
      product="$(artifact_product_for_name "$artifact")"
      launcher="$(artifact_launcher_for_name "$artifact")"
      runtime_kind="$(artifact_runtime_kind_for_name "$artifact")"
      minimum_java="$(artifact_minimum_java_json_for_name "$artifact")"
      license_file_sha256="$(zip_entry_sha256 "$bundled_file" "LICENSE.txt")"
      third_party_notices_sha256="$(zip_entry_sha256 "$bundled_file" "THIRD-PARTY-NOTICES.md")"
      printf '%s  %s\n' "$digest" "$artifact" > "$bundle_dir/$artifact.sha256"
      github_url="$(url_join "$GITHUB_RELEASE_ASSET_BASE_URL" "$artifact")"
      downloads_url="$(url_join "$DOWNLOADS_PUBLIC_PREFIX" "$artifact")"
      append_manifest_artifact \
        "$manifest_tmp" \
        "$platform" \
        "$artifact" \
        "$kind" \
        "$size" \
        "$digest" \
        "$github_url" \
        "$downloads_url" \
        "$product" \
        "$launcher" \
        "$runtime_kind" \
        "$minimum_java" \
        "$license_file_sha256" \
        "$third_party_notices_sha256"
    done
  done

  jq empty "$manifest_tmp"
  mv "$manifest_tmp" "$bundle_dir/$manifest_name"
  log "Prepared downloads release bundle in $bundle_dir"
  find "$bundle_dir" -maxdepth 1 -type f -print | sort
}

fetch_existing_release_index() {
  local target_file="$1"
  local aws_error

  rm -f "$target_file"
  if [ -n "${MCP_EXISTING_RELEASES_INDEX_FILE:-}" ]; then
    [ -f "$MCP_EXISTING_RELEASES_INDEX_FILE" ] || die "Existing releases index file does not exist: $MCP_EXISTING_RELEASES_INDEX_FILE"
    cp "$MCP_EXISTING_RELEASES_INDEX_FILE" "$target_file"
  elif [ "$PUBLISH_DOWNLOADS" = "true" ]; then
    require_tool "aws"
    aws_error="$(mktemp "${TMPDIR:-/tmp}/hexana-mcp-releases-index-aws.XXXXXX")"
    if ! aws s3 cp "$DOWNLOADS_RELEASES_INDEX_S3_URI" "$target_file" --only-show-errors 2>"$aws_error"; then
      if ! grep -Eq '(404|NoSuchKey|Not Found|does not exist)' "$aws_error"; then
        cat "$aws_error" >&2
        rm -f "$aws_error" "$target_file"
        die "Failed to read existing releases index from $DOWNLOADS_RELEASES_INDEX_S3_URI"
      fi
      log "No existing releases index found at $DOWNLOADS_RELEASES_INDEX_S3_URI; creating a new one"
      rm -f "$target_file"
    fi
    rm -f "$aws_error"
  fi

  if [ -f "$target_file" ]; then
    jq -e '.schemaVersion == 1 and (.releases | type == "array")' "$target_file" >/dev/null \
      || die "Existing releases index is not a supported Hexana MCP releases index: $target_file"
  fi
}

generate_releases_index() {
  local bundle_dir="$1"
  local index_dir="$2"
  local manifest_file="$bundle_dir/hexana-mcp-release-$MCP_VERSION.json"
  local existing_index="$index_dir/.existing-releases.json"
  local index_tmp="$index_dir/releases.json.tmp"
  local manifest_url

  [ -f "$manifest_file" ] || die "Missing generated release manifest: $manifest_file"
  mkdir -p "$index_dir"
  fetch_existing_release_index "$existing_index"

  manifest_url="$(url_join "$DOWNLOADS_PUBLIC_PREFIX" "hexana-mcp-release-$MCP_VERSION.json")"

  if [ -f "$existing_index" ]; then
    jq \
      --slurpfile manifest "$manifest_file" \
      --arg version "$MCP_VERSION" \
      --arg downloadsRoot "$DOWNLOADS_ROOT_PUBLIC_PREFIX" \
      --arg manifestUrl "$manifest_url" \
      --arg generatedAt "$GENERATED_AT" \
      '
      def semver_version:
        .version | sub("^v"; "");
      def semver_parts:
        semver_version
        | capture("^(?<core>[0-9]+\\.[0-9]+\\.[0-9]+)(?:-(?<prerelease>[0-9A-Za-z.-]+))?(?:\\+.*)?$");
      def prerelease_identifier_key:
        if test("^[0-9]+$") then [0, tonumber] else [1, .] end;
      def version_key:
        semver_parts as $version |
          ($version.core | split(".") | map(tonumber)) +
          [if ($version.prerelease // "") == "" then 1 else 0 end] +
          (if ($version.prerelease // "") == "" then [] else ($version.prerelease | split(".") | map(prerelease_identifier_key)) end);
      def current_release:
        {
          version: $version,
          manifestUrl: $manifestUrl,
          downloadsPrefix: $manifest[0].downloadsPrefix,
          generatedAt: $manifest[0].generatedAt,
          gitRevision: $manifest[0].gitRevision,
          teamcity: $manifest[0].teamcity,
          platforms: ($manifest[0].artifacts | map(.platform) | unique),
          artifactKinds: ($manifest[0].artifacts | map(.kind) | unique)
        };
      ([current_release] + ((.releases // []) | map(select(.version != $version))))
        | sort_by(version_key)
        | reverse
        as $releases
      | {
          schemaVersion: 1,
          latest: $releases[0].version,
          downloadsRoot: $downloadsRoot,
          generatedAt: $generatedAt,
          releases: $releases
        }
      ' "$existing_index" > "$index_tmp"
  else
    jq -n \
      --slurpfile manifest "$manifest_file" \
      --arg version "$MCP_VERSION" \
      --arg downloadsRoot "$DOWNLOADS_ROOT_PUBLIC_PREFIX" \
      --arg manifestUrl "$manifest_url" \
      --arg generatedAt "$GENERATED_AT" \
      '
      {
        schemaVersion: 1,
        latest: $version,
        downloadsRoot: $downloadsRoot,
        generatedAt: $generatedAt,
        releases: [
          {
            version: $version,
            manifestUrl: $manifestUrl,
            downloadsPrefix: $manifest[0].downloadsPrefix,
            generatedAt: $manifest[0].generatedAt,
            gitRevision: $manifest[0].gitRevision,
            teamcity: $manifest[0].teamcity,
            platforms: ($manifest[0].artifacts | map(.platform) | unique),
            artifactKinds: ($manifest[0].artifacts | map(.kind) | unique)
          }
        ]
      }
      ' > "$index_tmp"
  fi

  jq empty "$index_tmp"
  mv "$index_tmp" "$index_dir/releases.json"
  rm -f "$existing_index"
  log "Prepared downloads releases index in $index_dir"
  find "$index_dir" -maxdepth 1 -type f -print | sort
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

publish_releases_index() {
  local index_dir="$1"
  local destination="$2"
  local index_file="$index_dir/releases.json"

  require_tool "aws"
  [ -f "$index_file" ] || die "Releases index does not exist: $index_file"
  case "$destination" in
    s3://downloads-bucket.example.invalid/resources/hexana/mcp/releases.json) ;;
    *) die "Refusing to publish releases index outside the Hexana MCP downloads root: $destination" ;;
  esac

  log "Uploading releases index to $destination"
  aws s3 cp "$index_file" "$destination" --only-show-errors
}

verify_checksum_files() {
  local dir="$1"
  if command -v shasum >/dev/null 2>&1; then
    (cd "$dir" && shasum -a 256 -c *.sha256 >/dev/null)
  else
    (cd "$dir" && sha256sum -c *.sha256 >/dev/null)
  fi
}

create_self_test_zip_fixture() {
  local artifact_file="$1"
  local license_text="$2"
  local notices_text="$3"
  local include_wrapper_runtime="$4"
  local fixture_dir

  fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-mcp-downloads-zip-fixture.XXXXXX")"
  rm -f "$artifact_file"
  printf '%s' "$license_text" > "$fixture_dir/LICENSE.txt"
  printf '%s' "$notices_text" > "$fixture_dir/THIRD-PARTY-NOTICES.md"
  if [ "$include_wrapper_runtime" = "true" ]; then
    mkdir -p "$fixture_dir/server/bin" "$fixture_dir/server/lib"
    printf '#!/usr/bin/env sh\nexec java -jar "$(dirname "$0")/../lib/hexana-runtime-obfuscated.jar" "$@"\n' > "$fixture_dir/server/bin/server"
    printf 'runtime fixture\n' > "$fixture_dir/server/lib/hexana-runtime-obfuscated.jar"
    chmod +x "$fixture_dir/server/bin/server"
    (cd "$fixture_dir" && zip -q -r "$artifact_file" LICENSE.txt THIRD-PARTY-NOTICES.md server)
  else
    (cd "$fixture_dir" && zip -q "$artifact_file" LICENSE.txt THIRD-PARTY-NOTICES.md)
  fi
  rm -rf "$fixture_dir"
}

self_test_downloads_bundle() {
  local temp_dir
  local artifact_dir
  local bundle_dir
  local index_dir
  local existing_index
  local releases_index
  local manifest
  local artifact_count
  local sha_count
  local scan_status
  local license_sha256
  local notices_sha256

  require_tool "zip"
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-mcp-downloads-bundle.XXXXXX")"
  trap "rm -rf '$temp_dir'" RETURN
  artifact_dir="$temp_dir/artifacts"
  bundle_dir="$temp_dir/bundle"
  index_dir="$temp_dir/index"
  existing_index="$temp_dir/existing-releases.json"
  mkdir -p "$artifact_dir"

  create_self_test_zip_fixture "$artifact_dir/hexana-mcp-native-linux-x64-0.2.0.zip" "Hexana MCP license fixture"$'\n' "Hexana MCP notices fixture"$'\n' "false"
  create_self_test_zip_fixture "$artifact_dir/hexana-claude-plugin-linux-x64-0.2.0.zip" "Hexana Claude plugin license fixture"$'\n' "Hexana Claude plugin notices fixture"$'\n' "true"
  create_self_test_zip_fixture "$artifact_dir/hexana-codex-plugin-linux-x64-0.2.0.zip" "Hexana Codex plugin license fixture"$'\n' "Hexana Codex plugin notices fixture"$'\n' "true"
  create_self_test_zip_fixture "$artifact_dir/hexana-cli-native-linux-x64-0.2.0.zip" "Hexana CLI license fixture"$'\n' "Hexana CLI notices fixture"$'\n' "false"
  create_self_test_zip_fixture "$artifact_dir/hexana-cli-script-native-linux-x64-0.2.0.zip" "Hexana CLI script license fixture"$'\n' "Hexana CLI script notices fixture"$'\n' "false"

  MCP_VERSION="0.2.0"
  MCP_RELEASE_PLATFORMS="linux-x64"
  DOWNLOADS_ROOT_PUBLIC_PREFIX="https://download.jetbrains.com/resources/hexana/mcp/"
  DOWNLOADS_PUBLIC_PREFIX="$(url_join "$DOWNLOADS_ROOT_PUBLIC_PREFIX" "$MCP_VERSION")"
  GITHUB_RELEASE_ASSET_BASE_URL="https://github.com/JetBrains/hexana/releases/download/v$MCP_VERSION"
  TEAMCITY_BUILD_ID="12345"
  TEAMCITY_BUILD_URL="https://ci.example.invalid/buildConfiguration/InnoHub_HexFlow_McpPublishDownloads/12345"
  GIT_REVISION="abcdef1234567890"
  GENERATED_AT="2026-06-23T00:00:00Z"
  PUBLISH_DOWNLOADS="false"

  jq -n \
    '{
      schemaVersion: 1,
      latest: "0.2.0-rc.2",
      downloadsRoot: "https://download.jetbrains.com/resources/hexana/mcp",
      generatedAt: "2026-06-01T00:00:00Z",
      releases: [
        {
          version: "0.2.0-rc.10",
          manifestUrl: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-rc.10/hexana-mcp-release-0.2.0-rc.10.json",
          downloadsPrefix: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-rc.10",
          generatedAt: "2026-06-17T00:00:00Z",
          gitRevision: "4444444444444444",
          teamcity: { buildId: "44444", buildUrl: null },
          platforms: ["linux-x64"],
          artifactKinds: ["native-executable"]
        },
        {
          version: "0.2.0-rc.2",
          manifestUrl: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-rc.2/hexana-mcp-release-0.2.0-rc.2.json",
          downloadsPrefix: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-rc.2",
          generatedAt: "2026-06-15T00:00:00Z",
          gitRevision: "2222222222222222",
          teamcity: { buildId: "22222", buildUrl: null },
          platforms: ["linux-x64"],
          artifactKinds: ["native-executable"]
        },
        {
          version: "0.2.0-beta.11",
          manifestUrl: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-beta.11/hexana-mcp-release-0.2.0-beta.11.json",
          downloadsPrefix: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-beta.11",
          generatedAt: "2026-06-13T00:00:00Z",
          gitRevision: "5555555555555555",
          teamcity: { buildId: "55555", buildUrl: null },
          platforms: ["linux-x64"],
          artifactKinds: ["native-executable"]
        },
        {
          version: "0.2.0-beta.2",
          manifestUrl: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-beta.2/hexana-mcp-release-0.2.0-beta.2.json",
          downloadsPrefix: "https://download.jetbrains.com/resources/hexana/mcp/0.2.0-beta.2",
          generatedAt: "2026-06-12T00:00:00Z",
          gitRevision: "3333333333333333",
          teamcity: { buildId: "33333", buildUrl: null },
          platforms: ["linux-x64"],
          artifactKinds: ["native-executable"]
        },
        {
          version: "0.1.0",
          manifestUrl: "https://download.jetbrains.com/resources/hexana/mcp/0.1.0/hexana-mcp-release-0.1.0.json",
          downloadsPrefix: "https://download.jetbrains.com/resources/hexana/mcp/0.1.0",
          generatedAt: "2026-06-01T00:00:00Z",
          gitRevision: "1111111111111111",
          teamcity: { buildId: "11111", buildUrl: null },
          platforms: ["linux-x64"],
          artifactKinds: ["native-executable"]
        }
      ]
    }' > "$existing_index"
  MCP_EXISTING_RELEASES_INDEX_FILE="$existing_index"

  generate_downloads_bundle "$artifact_dir" "$bundle_dir"
  generate_releases_index "$bundle_dir" "$index_dir"
  manifest="$bundle_dir/hexana-mcp-release-0.2.0.json"
  releases_index="$index_dir/releases.json"
  artifact_count="$(jq '.artifacts | length' "$manifest")"
  [ "$artifact_count" -eq 5 ] || die "Self-test expected 5 manifest artifacts, got $artifact_count"
  jq -e '.version == "0.2.0"' "$manifest" >/dev/null
  jq -e '.teamcity.buildId == "12345"' "$manifest" >/dev/null
  jq -e '.downloadsPrefix == "https://download.jetbrains.com/resources/hexana/mcp/0.2.0"' "$manifest" >/dev/null
  jq -e 'all(.artifacts[].downloadsUrl; contains("/mcp//") | not)' "$manifest" >/dev/null
  license_sha256="$(printf 'Hexana MCP license fixture\n' | sha256_file /dev/stdin)"
  notices_sha256="$(printf 'Hexana MCP notices fixture\n' | sha256_file /dev/stdin)"
  jq -e \
    --arg licenseFileSha256 "$license_sha256" \
    --arg thirdPartyNoticesSha256 "$notices_sha256" \
    '.artifacts[] | select(
      .name == "hexana-mcp-native-linux-x64-0.2.0.zip"
      and .platform == "linux-x64"
      and .kind == "native-executable"
      and .product == "hexana-mcp"
      and .launcher == "hexana-mcp"
      and .runtimeKind == "native"
      and .minimumJava == null
      and .licenseFileSha256 == $licenseFileSha256
      and .thirdPartyNoticesSha256 == $thirdPartyNoticesSha256
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )' "$manifest" >/dev/null
  license_sha256="$(printf 'Hexana CLI license fixture\n' | sha256_file /dev/stdin)"
  notices_sha256="$(printf 'Hexana CLI notices fixture\n' | sha256_file /dev/stdin)"
  jq -e \
    --arg licenseFileSha256 "$license_sha256" \
    --arg thirdPartyNoticesSha256 "$notices_sha256" \
    '.artifacts[] | select(
      .name == "hexana-cli-native-linux-x64-0.2.0.zip"
      and .platform == "linux-x64"
      and .kind == "standard-cli-native"
      and .product == "hexana-cli"
      and .launcher == "hexana-cli"
      and .runtimeKind == "native"
      and .minimumJava == null
      and .licenseFileSha256 == $licenseFileSha256
      and .thirdPartyNoticesSha256 == $thirdPartyNoticesSha256
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )' "$manifest" >/dev/null
  license_sha256="$(printf 'Hexana CLI script license fixture\n' | sha256_file /dev/stdin)"
  notices_sha256="$(printf 'Hexana CLI script notices fixture\n' | sha256_file /dev/stdin)"
  jq -e \
    --arg licenseFileSha256 "$license_sha256" \
    --arg thirdPartyNoticesSha256 "$notices_sha256" \
    '.artifacts[] | select(
      .name == "hexana-cli-script-native-linux-x64-0.2.0.zip"
      and .platform == "linux-x64"
      and .kind == "script-cli-native"
      and .product == "hexana-cli-script"
      and .launcher == "hexana-cli-script"
      and .runtimeKind == "native"
      and .minimumJava == null
      and .licenseFileSha256 == $licenseFileSha256
      and .thirdPartyNoticesSha256 == $thirdPartyNoticesSha256
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )' "$manifest" >/dev/null
  license_sha256="$(printf 'Hexana Claude plugin license fixture\n' | sha256_file /dev/stdin)"
  notices_sha256="$(printf 'Hexana Claude plugin notices fixture\n' | sha256_file /dev/stdin)"
  jq -e \
    --arg licenseFileSha256 "$license_sha256" \
    --arg thirdPartyNoticesSha256 "$notices_sha256" \
    '.artifacts[] | select(
      .name == "hexana-claude-plugin-linux-x64-0.2.0.zip"
      and .platform == "linux-x64"
      and .kind == "claude-plugin"
      and .product == "hexana-claude-plugin"
      and .launcher == "claude-plugin"
      and .runtimeKind == "native-wrapper"
      and .minimumJava == 21
      and .licenseFileSha256 == $licenseFileSha256
      and .thirdPartyNoticesSha256 == $thirdPartyNoticesSha256
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )' "$manifest" >/dev/null
  license_sha256="$(printf 'Hexana Codex plugin license fixture\n' | sha256_file /dev/stdin)"
  notices_sha256="$(printf 'Hexana Codex plugin notices fixture\n' | sha256_file /dev/stdin)"
  jq -e \
    --arg licenseFileSha256 "$license_sha256" \
    --arg thirdPartyNoticesSha256 "$notices_sha256" \
    '.artifacts[] | select(
      .name == "hexana-codex-plugin-linux-x64-0.2.0.zip"
      and .platform == "linux-x64"
      and .kind == "codex-plugin"
      and .product == "hexana-codex-plugin"
      and .launcher == "codex-plugin"
      and .runtimeKind == "native-wrapper"
      and .minimumJava == 21
      and .licenseFileSha256 == $licenseFileSha256
      and .thirdPartyNoticesSha256 == $thirdPartyNoticesSha256
      and (.sha256 | test("^[0-9a-f]{64}$"))
    )' "$manifest" >/dev/null
  sha_count="$(find "$bundle_dir" -maxdepth 1 -type f -name '*.sha256' | wc -l | tr -d '[:space:]')"
  [ "$sha_count" -eq 5 ] || die "Self-test expected 5 checksum files, got $sha_count"
  verify_checksum_files "$bundle_dir"
  jq -e '.schemaVersion == 1 and .latest == "0.2.0" and .downloadsRoot == "https://download.jetbrains.com/resources/hexana/mcp/"' "$releases_index" >/dev/null
  jq -e '.releases | length == 6' "$releases_index" >/dev/null
  jq -e '.releases[0] | .version == "0.2.0" and .manifestUrl == "https://download.jetbrains.com/resources/hexana/mcp/0.2.0/hexana-mcp-release-0.2.0.json"' "$releases_index" >/dev/null
  jq -e '.releases[1] | .version == "0.2.0-rc.10"' "$releases_index" >/dev/null
  jq -e '.releases[2] | .version == "0.2.0-rc.2"' "$releases_index" >/dev/null
  jq -e '.releases[3] | .version == "0.2.0-beta.11"' "$releases_index" >/dev/null
  jq -e '.releases[4] | .version == "0.2.0-beta.2"' "$releases_index" >/dev/null
  jq -e '.releases[0].platforms == ["linux-x64"] and (.releases[0].artifactKinds | index("native-executable")) and (.releases[0].artifactKinds | index("standard-cli-native")) and (.releases[0].artifactKinds | index("script-cli-native"))' "$releases_index" >/dev/null
  jq -e 'all(.releases[].manifestUrl; contains("/mcp//") | not) and all(.releases[].downloadsPrefix; contains("/mcp//") | not)' "$releases_index" >/dev/null

  rm "$artifact_dir/hexana-cli-script-native-linux-x64-0.2.0.zip"
  set +e
  (
    set -e
    generate_downloads_bundle "$artifact_dir" "$temp_dir/missing-cli-script-native-bundle"
  ) >"$temp_dir/missing-cli-script-native.out" 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Self-test did not require the script CLI native artifact"
  grep -F -q "Missing release artifact: $artifact_dir/hexana-cli-script-native-linux-x64-0.2.0.zip" "$temp_dir/missing-cli-script-native.out" ||
    die "Self-test did not record the missing script CLI native artifact failure"
  log "Downloads bundle self-test recorded missing hexana-cli-script-native-linux-x64-0.2.0.zip failure"

  create_self_test_zip_fixture "$artifact_dir/hexana-cli-script-native-linux-x64-0.2.0.zip" "Hexana CLI script license fixture"$'\n' "" "false"
  set +e
  (
    set -e
    generate_downloads_bundle "$artifact_dir" "$temp_dir/empty-notices-bundle"
  ) >"$temp_dir/empty-notices.out" 2>&1
  scan_status=$?
  set -e
  [ "$scan_status" -ne 0 ] || die "Self-test did not reject an empty THIRD-PARTY-NOTICES.md entry"
  grep -F -q "has empty required ZIP entry: THIRD-PARTY-NOTICES.md" "$temp_dir/empty-notices.out" ||
    die "Self-test did not record the empty THIRD-PARTY-NOTICES.md metadata failure"
  log "Downloads bundle self-test recorded empty THIRD-PARTY-NOTICES.md metadata failure"

  create_self_test_zip_fixture "$artifact_dir/hexana-cli-script-native-linux-x64-0.2.0.zip" "Hexana CLI script license fixture"$'\n' "Hexana CLI script notices fixture"$'\n' "false"
  generate_downloads_bundle "$artifact_dir" "$temp_dir/restored-bundle"

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
  MCP_DOWNLOADS_INDEX_DIR="${MCP_DOWNLOADS_INDEX_DIR:-mcp/out/teamcity/downloads-index}"
  DOWNLOADS_ROOT_PUBLIC_PREFIX="${DOWNLOADS_ROOT_PUBLIC_PREFIX:-https://download.jetbrains.com/resources/hexana/mcp}"
  DOWNLOADS_PUBLIC_PREFIX="${DOWNLOADS_PUBLIC_PREFIX:-$(url_join "$DOWNLOADS_ROOT_PUBLIC_PREFIX" "$MCP_VERSION")}"
  DOWNLOADS_ROOT_S3_PREFIX="${DOWNLOADS_ROOT_S3_PREFIX:-s3://downloads-bucket.example.invalid/resources/hexana/mcp/}"
  DOWNLOADS_S3_PREFIX="${DOWNLOADS_S3_PREFIX:-$(s3_join "$DOWNLOADS_ROOT_S3_PREFIX" "$MCP_VERSION")/}"
  DOWNLOADS_RELEASES_INDEX_S3_URI="${DOWNLOADS_RELEASES_INDEX_S3_URI:-$(s3_join "$DOWNLOADS_ROOT_S3_PREFIX" "releases.json")}"
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
  generate_releases_index "$MCP_DOWNLOADS_BUNDLE_DIR" "$MCP_DOWNLOADS_INDEX_DIR"

  if [ "$PUBLISH_DOWNLOADS" = "true" ]; then
    publish_downloads_bundle "$MCP_DOWNLOADS_BUNDLE_DIR" "$DOWNLOADS_S3_PREFIX"
    publish_releases_index "$MCP_DOWNLOADS_INDEX_DIR" "$DOWNLOADS_RELEASES_INDEX_S3_URI"
  else
    log "PUBLISH_DOWNLOADS=false; upload skipped"
  fi
}

main "$@"
