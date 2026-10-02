#!/usr/bin/env bash
set -euo pipefail
set +x

log() {
  printf '[cli-downloads-release] %s\n' "$*"
}

die() {
  printf '[cli-downloads-release] ERROR: %s\n' "$*" >&2
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

readonly SEMVER_JQ='
  def semver_parts:
    try capture("^(?<base>(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*))(-(?<pre>((0|[1-9][0-9]*)|([0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))(\\.((0|[1-9][0-9]*)|([0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)))*))?(\\+(?<build>[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*))?$") catch null;
  def valid_semver: semver_parts != null;
  def stable_semver:
    semver_parts as $parts |
    $parts != null and $parts.pre == null;
  def version_parts:
    .version | semver_parts as $parts |
    ($parts.base | split(".") | map(tonumber)) +
    [if ($parts.pre // "") == "" then 1 else 0 end] +
    (if ($parts.pre // "") == "" then [] else ($parts.pre | split(".") | map(if test("^[0-9]+$") then [0, tonumber] else [1, .] end)) end);
'

validate_semver() {
  jq -en --arg version "$2" "$SEMVER_JQ"$'\n''$version | valid_semver' >/dev/null || die "$1 must be a valid SemVer version: $2"
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print $1 }'
  else
    sha256sum "$1" | awk '{ print $1 }'
  fi
}

file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

join_url() {
  if [ -n "$1" ]; then
    printf '%s/%s' "${1%/}" "$2"
  else
    printf ''
  fi
}

join_s3() {
  printf '%s/%s' "${1%/}" "$2"
}

validate_segment() {
  case "$2" in
    ''|.*|*/*|*..*|*[!A-Za-z0-9._+-]*) die "$1 must be a simple path segment: $2" ;;
  esac
}

read_cli_version() {
  local properties_file='gradle.properties'
  [ -f "$properties_file" ] || die "Missing $properties_file"
  awk -F= '
    /^[[:space:]]*hexanaToolsVersion[[:space:]]*=/ {
      value = $0
      sub(/^[^=]*=/, "", value)
      sub(/[[:space:]]+#.*/, "", value)
      gsub(/[[:space:]]/, "", value)
      print value
      exit
    }
  ' "$properties_file"
}

resolve_cli_version() {
  printf '%s' "${CLI_VERSION:-$(read_cli_version)}"
}

archive_name() {
  local platform="$1"
  local product="$2"
  printf '%s-native-%s-%s.zip\n' "$product" "$platform" "$CLI_VERSION"
}

validate_input() {
  CLI_MATRIX_FILE="$1"
  python3 cli/packaging/validate_release_artifacts.py \
    "$CLI_RELEASE_DESCRIPTOR" "$CLI_RELEASE_ARTIFACT_DIR" "$CLI_VERSION" "$CLI_MATRIX_FILE" || die 'CLI release input validation failed'
  CLI_SOURCE_REVISION="$(jq -r .sourceRevision "$CLI_MATRIX_FILE")"

  [ -z "$GIT_REVISION" ] || {
    [[ "$GIT_REVISION" =~ ^[0-9a-f]{40}$ ]] && [ "$GIT_REVISION" = "$CLI_SOURCE_REVISION" ]
  } || die 'Publication revision must match the input source revision'
}

render_readme() {
  local target="$1"
  local line
  [ -f "$CLI_README_TEMPLATE" ] || die "Missing CLI README template: $CLI_README_TEMPLATE"
  grep -F -q '@VERSION@' "$CLI_README_TEMPLATE" || die 'CLI README template lacks @VERSION@'
  grep -F -q '@DOWNLOADS_PREFIX@' "$CLI_README_TEMPLATE" || die 'CLI README template lacks @DOWNLOADS_PREFIX@'
  grep -F -q '@DOWNLOADS_TABLE@' "$CLI_README_TEMPLATE" || die 'CLI README template lacks @DOWNLOADS_TABLE@'

  : > "$target"
  while IFS= read -r line || [ -n "$line" ]; do
    if [ "$line" = '@DOWNLOADS_TABLE@' ]; then
      jq -r --arg prefix "${CLI_PUBLIC_PREFIX%/}" '
        . as $matrix |
        "| Product | " + ($matrix.targets | map(.displayName) | join(" | ")) + " |",
        "| --- | " + ($matrix.targets | map("---") | join(" | ")) + " |",
        ($matrix.products[] as $product |
          "| `" + $product.id + "` | " +
          ([$matrix.targets[] as $target | $matrix.artifacts[] |
            select(.target == $target.id and .product == $product.id) |
            "[Download](" + $prefix + "/" + .name + ")"] | join(" | ")) + " |")
      ' "$CLI_MATRIX_FILE" >> "$target"
      continue
    fi
    line="${line//@VERSION@/$CLI_VERSION}"
    line="${line//@DOWNLOADS_PREFIX@/${CLI_PUBLIC_PREFIX%/}}"
    printf '%s\n' "$line" >> "$target"
  done < "$CLI_README_TEMPLATE"

  [ -s "$target" ] || die 'Rendered CLI README is empty'
  ! grep -E -q '@[A-Z][A-Z0-9_]*@|(/Users/|/private/|git\.jetbrains\.team|buildserver|BEGIN PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|sk-|AKIA|password=|secret=|token=)' "$target" || die 'Rendered CLI README is unsafe or unresolved'
}

append_manifest_artifact() {
  local manifest="$1"
  local platform="$2"
  local product="$3"
  local archive="$4"
  local kind="$5"
  local launcher="$6"
  local license_hash="$7"
  local notices_hash="$8"
  local next github_url archive_file
  next="$manifest.next"
  github_url="$(join_url "$GITHUB_RELEASE_ASSET_BASE_URL" "$archive")"
  archive_file="$(dirname "$manifest")/$archive"

  jq \
    --arg platform "$platform" \
    --arg product "$product" \
    --arg kind "$kind" \
    --arg name "$archive" \
    --arg downloads_url "$(join_url "$CLI_PUBLIC_PREFIX" "$archive")" \
    --arg github_url "$github_url" \
    --arg archive_hash "$(sha256_file "$archive_file")" \
    --arg launcher "${launcher#bin/}" \
    --arg license_hash "$license_hash" \
    --arg notices_hash "$notices_hash" \
    --argjson archive_size "$(file_size "$archive_file")" \
    '.artifacts += [{
      name: $name,
      platform: $platform,
      kind: $kind,
      size: $archive_size,
      sha256: $archive_hash,
      githubReleaseAssetUrl: (if $github_url == "" then null else $github_url end),
      downloadsUrl: $downloads_url,
      product: $product,
      launcher: $launcher,
      runtimeKind: "native",
      minimumJava: null,
      licenseFileSha256: $license_hash,
      thirdPartyNoticesSha256: $notices_hash
    }]' "$manifest" > "$next"
  mv "$next" "$manifest"
}

make_bundle() (
  local bundle_dir="$1"
  local manifest ledger platform product archive producer_build kind launcher matrix row license_hash notices_hash executable_hash
  manifest="$bundle_dir/hexana-cli-release-$CLI_VERSION.json"
  ledger="$bundle_dir/hexana-cli-release-$CLI_VERSION.ledger.json"
  matrix="$(mktemp "${TMPDIR:-/tmp}/cli-download-matrix.XXXXXX")"
  trap 'rm -f "$matrix"' EXIT

  validate_input "$matrix"
  rm -rf "$bundle_dir"
  mkdir -p "$bundle_dir"

  jq -n \
    --arg version "$CLI_VERSION" \
    --arg revision "$GIT_REVISION" \
    --arg build_id "$TEAMCITY_BUILD_ID" \
    --arg build_url "$TEAMCITY_BUILD_URL" \
    --arg downloads_prefix "$CLI_PUBLIC_PREFIX" \
    --arg generated_at "$GENERATED_AT" \
    '{
      version: $version,
      gitRevision: (if $revision == "" then null else $revision end),
      teamcity: {buildId: (if $build_id == "" then null else $build_id end), buildUrl: (if $build_url == "" then null else $build_url end)},
      downloadsPrefix: $downloads_prefix,
      generatedAt: $generated_at,
      artifacts: []
    }' > "$manifest"

  jq -n \
    --arg version "$CLI_VERSION" \
    --arg revision "$CLI_SOURCE_REVISION" \
    --arg generated_at "$GENERATED_AT" \
    '{schemaVersion: 1, kind: "hexana-cli-downloads-ledger", version: $version, sourceRevision: $revision, generatedAt: $generated_at, artifacts: []}' > "$ledger"

  while IFS= read -r row; do
      platform="$(jq -r .target <<< "$row")"
      product="$(jq -r .product <<< "$row")"
      archive="$(jq -r .name <<< "$row")"
      producer_build="$(jq -r .producerBuildId <<< "$row")"
      kind="$(jq -r .kind <<< "$row")"
      launcher="$(jq -r .launcher <<< "$row")"
      license_hash="$(jq -r .licenseSha256 <<< "$row")"
      notices_hash="$(jq -r .noticesSha256 <<< "$row")"
      executable_hash="$(jq -r .launcherSha256 <<< "$row")"
      cp "$CLI_RELEASE_ARTIFACT_DIR/$archive" "$bundle_dir/$archive"
      [ "$(sha256_file "$bundle_dir/$archive")" = "$(jq -r .sha256 <<< "$row")" ] || die "Copied archive does not match identity: $archive"
      printf '%s  %s\n' "$(sha256_file "$bundle_dir/$archive")" "$archive" > "$bundle_dir/$archive.sha256"
      append_manifest_artifact "$manifest" "$platform" "$product" "$archive" "$kind" "$launcher" "$license_hash" "$notices_hash"
      jq \
        --arg platform "$platform" \
        --arg product "$product" \
        --arg revision "$CLI_SOURCE_REVISION" \
        --arg producer_build "$producer_build" \
        --arg name "$archive" \
        --arg url "$(join_url "$CLI_PUBLIC_PREFIX" "$archive")" \
        --arg archive_hash "$(sha256_file "$bundle_dir/$archive")" \
        --arg launcher "$launcher" \
        --arg executable_hash "$executable_hash" \
        --argjson archive_size "$(file_size "$bundle_dir/$archive")" \
        '.artifacts += [{
          product: $product,
          platform: $platform,
          sourceRevision: $revision,
          producerBuildId: $producer_build,
          archive: {name: $name, url: $url, size: $archive_size, sha256: $archive_hash},
          executable: {path: $launcher, sha256: $executable_hash}
        }]' "$ledger" > "$ledger.next"
      mv "$ledger.next" "$ledger"
  done < <(jq -c '.artifacts[]' "$matrix")

  render_readme "$bundle_dir/README.md"
  jq -e --argjson count "$(jq '.artifacts | length' "$matrix")" '.artifacts | length == $count' "$manifest" >/dev/null
  jq -e --argjson count "$(jq '.artifacts | length' "$matrix")" '.artifacts | length == $count' "$ledger" >/dev/null
)

bundle_names() {
  (cd "$1" && find . -maxdepth 1 -type f -exec basename {} \; | sort)
}

remote_if_present() {
  local uri="$1"
  local target="$2"
  local error
  error="$(mktemp "${TMPDIR:-/tmp}/cli-download-remote.XXXXXX")"
  rm -f "$target"
  if aws s3 cp "$uri" "$target" --only-show-errors 2> "$error"; then
    rm -f "$error"
    return 0
  fi
  if grep -Eq '(404|NoSuchKey|Not Found|does not exist)' "$error"; then
    rm -f "$error" "$target"
    return 1
  fi
  cat "$error" >&2
  rm -f "$error" "$target"
  die "Cannot inspect remote object: $uri"
}

validate_destinations() {
  [ "$CLI_ROOT_PUBLIC_PREFIX" = 'https://download.jetbrains.com/resources/hexana/cli' ] || die 'CLI public root is not exact'
  [ "$CLI_PUBLIC_PREFIX" = "$CLI_ROOT_PUBLIC_PREFIX/$CLI_VERSION" ] || die 'CLI public version prefix is not exact'
  [ "$CLI_ROOT_S3_PREFIX" = 's3://downloads-bucket.example.invalid/resources/hexana/cli/' ] || die 'CLI storage root is not exact'
  [ "$CLI_S3_PREFIX" = "$CLI_ROOT_S3_PREFIX$CLI_VERSION/" ] || die 'CLI storage version prefix is not exact'
  [ "$CLI_INDEX_S3_URI" = "${CLI_ROOT_S3_PREFIX}releases.json" ] || die 'CLI index destination is not exact'
}

remote_names() {
  local remote="$1"
  local key
  key="${remote#s3://downloads-bucket.example.invalid/}"
  aws s3 ls "$remote" --recursive |
    awk '{print $4}' |
    sed "s!^$key!!" |
    sed '/^$/d' |
    sort
}

compare_json_ignoring() {
  local remote="$1"
  local local_file="$2"
  local fields="$3"
  local remote_normalized local_normalized status
  remote_normalized="$(mktemp "${TMPDIR:-/tmp}/cli-download-json.XXXXXX")"
  local_normalized="$(mktemp "${TMPDIR:-/tmp}/cli-download-json.XXXXXX")"
  jq -S "$fields" "$remote" > "$remote_normalized"
  jq -S "$fields" "$local_file" > "$local_normalized"
  set +e
  cmp -s "$remote_normalized" "$local_normalized"
  status=$?
  set -e
  rm -f "$remote_normalized" "$local_normalized"
  return "$status"
}

reconcile_prefix() {
  local bundle_dir="$1"
  local remote="$2"
  local cache_dir="$3"
  local ledger name present_names
  ledger="hexana-cli-release-$CLI_VERSION.ledger.json"
  mkdir -p "$cache_dir"
  present_names="$(remote_names "$remote")"
  [ -z "$present_names" ] && return

  while IFS= read -r name; do
    printf '%s\n' "$(bundle_names "$bundle_dir")" | grep -F -x -- "$name" >/dev/null || die "Remote CLI prefix has unexpected object: $name"
  done <<< "$present_names"

  remote_if_present "$(join_s3 "$remote" "$ledger")" "$cache_dir/ledger" || die 'Remote CLI prefix lacks immutable ledger'
  compare_json_ignoring "$cache_dir/ledger" "$bundle_dir/$ledger" 'del(.generatedAt)' || die 'Remote CLI ledger conflicts with requested immutable identity'

  while IFS= read -r name; do
    remote_if_present "$(join_s3 "$remote" "$name")" "$cache_dir/$name" || die "Remote CLI prefix is incomplete: $name"
    case "$name" in
      "$ledger") ;;
      "hexana-cli-release-$CLI_VERSION.json")
        compare_json_ignoring "$cache_dir/$name" "$bundle_dir/$name" 'del(.generatedAt, .teamcity)' || die 'Remote CLI manifest conflicts with immutable identity'
        cp "$cache_dir/$name" "$bundle_dir/$name"
        ;;
      *)
        [ "$(sha256_file "$cache_dir/$name")" = "$(sha256_file "$bundle_dir/$name")" ] || die "Remote CLI object conflicts with accepted bytes: $name"
        ;;
    esac
  done <<< "$present_names"
}

upload_prefix() {
  local bundle_dir="$1"
  local remote="$2"
  local cache_dir="$3"
  local name after_upload
  reconcile_prefix "$bundle_dir" "$remote" "$cache_dir"

  for name in "hexana-cli-release-$CLI_VERSION.ledger.json" $(bundle_names "$bundle_dir" | grep -F -x -v "hexana-cli-release-$CLI_VERSION.ledger.json"); do
    if ! remote_if_present "$(join_s3 "$remote" "$name")" "$cache_dir/$name"; then
      aws s3 cp "$bundle_dir/$name" "$(join_s3 "$remote" "$name")" --only-show-errors
      remote_if_present "$(join_s3 "$remote" "$name")" "$cache_dir/$name" || die "CLI upload missing object: $name"
      [ "$(sha256_file "$cache_dir/$name")" = "$(sha256_file "$bundle_dir/$name")" ] || die "CLI upload mismatch: $name"
    fi
  done

  reconcile_prefix "$bundle_dir" "$remote" "$cache_dir"
  after_upload="$(remote_names "$remote")"
  [ "$after_upload" = "$(bundle_names "$bundle_dir")" ] || die 'CLI upload incomplete; index remains unpromoted'
}

validate_index() {
  local index_filter
  index_filter="$SEMVER_JQ"$'\n'
  index_filter+='
    .schemaVersion == 1 and
    .downloadsRoot == $root and
    (.releases | type == "array") and
    ([.releases[].version] | unique | length) == (.releases | length) and
    all(.releases[];
      (.version | valid_semver) and
      .manifestUrl == ($root + "/" + .version + "/hexana-cli-release-" + .version + ".json") and
      .downloadsPrefix == ($root + "/" + .version) and
      (.platforms | type == "array" and length > 0 and all(.[]; type == "string" and test("^[a-z][a-z0-9-]*$"))) and
      ([.platforms[]] | unique | length) == (.platforms | length) and
      (.artifactKinds | type == "array" and length > 0 and all(.[]; type == "string" and test("^[a-z][a-z0-9-]*$"))) and
      ([.artifactKinds[]] | unique | length) == (.artifactKinds | length)
    )
  '
  jq -e --arg root "$CLI_ROOT_PUBLIC_PREFIX" "$index_filter" "$1" >/dev/null || die 'Existing CLI index has an incompatible retained release'
}

make_index() {
  local bundle_dir="$1"
  local index_dir="$2"
  local old_index manifest index_filter
  old_index="$index_dir/old.json"
  manifest="$bundle_dir/hexana-cli-release-$CLI_VERSION.json"
  mkdir -p "$index_dir"
  rm -f "$old_index"

  if [ -n "$CLI_EXISTING_RELEASES_INDEX_FILE" ]; then
    cp "$CLI_EXISTING_RELEASES_INDEX_FILE" "$old_index"
  elif [ "$PUBLISH_DOWNLOADS" = true ]; then
    remote_if_present "$CLI_INDEX_S3_URI" "$old_index" || :
  fi

  if [ -f "$old_index" ]; then
    validate_index "$old_index"
    CLI_INDEX_SNAPSHOT="$(sha256_file "$old_index")"
  else
    CLI_INDEX_SNAPSHOT='absent'
    jq -n --arg root "$CLI_ROOT_PUBLIC_PREFIX" '{schemaVersion: 1, downloadsRoot: $root, releases: []}' > "$old_index"
  fi

  index_filter="$SEMVER_JQ"$'\n'
  index_filter+='
    def release:
      {
        version: $version,
        manifestUrl: ($root + "/" + $version + "/hexana-cli-release-" + $version + ".json"),
        downloadsPrefix: $manifest[0].downloadsPrefix,
        generatedAt: $manifest[0].generatedAt,
        gitRevision: $manifest[0].gitRevision,
        teamcity: $manifest[0].teamcity,
        platforms: ($manifest[0].artifacts | map(.platform) | unique),
        artifactKinds: ($manifest[0].artifacts | map(.kind) | unique)
      };
    ([release] + (($old[0].releases // []) | map(select(.version != $version))) | sort_by(version_parts) | reverse) as $releases |
    {
      schemaVersion: 1,
      latest: $releases[0].version,
      downloadsRoot: $root,
      generatedAt: $generated_at,
      releases: $releases
    } |
    .stable = ([.releases[] | select(.version | stable_semver)][0] // null |
      if . == null then null else {version, manifestUrl, downloadsPrefix} end)
  '
  jq -n \
    --slurpfile old "$old_index" \
    --slurpfile manifest "$manifest" \
    --arg version "$CLI_VERSION" \
    --arg root "$CLI_ROOT_PUBLIC_PREFIX" \
    --arg generated_at "$GENERATED_AT" "$index_filter" > "$index_dir/releases.json"
  jq empty "$index_dir/releases.json"
  validate_index "$index_dir/releases.json"
  rm -f "$old_index"
}

promote_index() {
  local current="$1"
  local actual
  if remote_if_present "$CLI_INDEX_S3_URI" "$current"; then
    actual="$(sha256_file "$current")"
  else
    actual='absent'
  fi
  [ "$actual" = "$CLI_INDEX_SNAPSHOT" ] || die 'CLI index changed since snapshot; stale promotion rejected'
  aws s3 cp "$CLI_INDEX_DIR/releases.json" "$CLI_INDEX_S3_URI" --only-show-errors
}

fixture_zip() {
  local archive="$1"
  local product="$2"
  local payload="$3"
  local notices="${4-notices}"
  local fixture_dir
  fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/cli-download-fixture.XXXXXX")"
  mkdir -p "$fixture_dir/bin"
  printf '%s\n' "$payload" > "$fixture_dir/LICENSE.txt"
  printf 'Fixture archive for %s\n' "$product" > "$fixture_dir/README.md"
  printf '%s' "$notices" > "$fixture_dir/THIRD-PARTY-NOTICES.md"
  printf '#!/bin/sh\necho %s\n' "$product" > "$fixture_dir/bin/$product"
  chmod +x "$fixture_dir/bin/$product"
  python3 - "$fixture_dir" "$archive" "$product" <<'PY'
import sys
import zipfile
from pathlib import Path

fixture_dir = Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2], "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in ("LICENSE.txt", "README.md", "THIRD-PARTY-NOTICES.md", f"bin/{sys.argv[3]}"):
        archive.write(fixture_dir / name, arcname=name)
PY
  rm -rf "$fixture_dir"
}

fixture_identity() {
  local input_dir="$1"
  local platform="$2"
  local archive artifacts
  artifacts="$(while IFS= read -r archive; do
    jq -n --arg name "$archive" --arg sha256 "$(sha256_file "$input_dir/$archive")" '{name: $name, sha256: $sha256}'
  done < <(jq -r --arg platform "$platform" --arg version "$CLI_VERSION" '
    .artifacts[] | select(.target == $platform) | .archiveNameTemplate | gsub("\\{version\\}"; $version)
  ' "$CLI_RELEASE_DESCRIPTOR") | jq -s .)"
  jq -n \
    --arg platform "$platform" \
    --arg version "$CLI_VERSION" \
    --argjson artifacts "$artifacts" \
    '{
      sourceRevision: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      teamCityBuildId: (if $platform == "macos-arm64" then "501" else "502" end),
      platform: $platform,
      version: $version,
      artifacts: $artifacts
    }' > "$input_dir/$platform-identity.json"
}

fixture_release_input() {
  local input_dir="$1"
  local row platform product executable archive
  mkdir -p "$input_dir"
  while IFS= read -r row; do
    product="$(jq -r .product <<< "$row")"
    executable="$(jq -r .executable <<< "$row")"
    archive="$(jq -r --arg version "$CLI_VERSION" '.archiveNameTemplate | gsub("\\{version\\}"; $version)' <<< "$row")"
    fixture_zip "$input_dir/$archive" "$executable" "$product"
  done < <(jq -c '.artifacts[] as $artifact | .products[] | select(.id == $artifact.product) |
    $artifact + {executable}' "$CLI_RELEASE_DESCRIPTOR")
  while IFS= read -r platform; do
    fixture_identity "$input_dir" "$platform"
  done < <(jq -r '.targets[].id' "$CLI_RELEASE_DESCRIPTOR")
}

assert_six_archive_bundle() {
  local bundle_dir="$1" manifest ledger name
  manifest="$bundle_dir/hexana-cli-release-$CLI_VERSION.json"
  ledger="$bundle_dir/hexana-cli-release-$CLI_VERSION.ledger.json"
  jq -e --arg version "$CLI_VERSION" '
    (.artifacts | length == 6) and
    ([.artifacts[] | [.platform, .product]] | sort ==
      (["macos-arm64", "linux-x64"] | [.[] as $target |
        ["hexana-cli", "hexana-cli-script", "hexana-wasm-tools"][] | [$target, .]] | sort)) and
    ([.artifacts[].kind] | unique == ["hexana-wasm-tools-native", "script-cli-native", "standard-cli-native"]) and
    all(.artifacts[];
      .name == (.product + "-native-" + .platform + "-" + $version + ".zip") and
      .launcher == .product and .runtimeKind == "native" and .minimumJava == null and
      (.licenseFileSha256 | test("^[0-9a-f]{64}$")) and (.thirdPartyNoticesSha256 | test("^[0-9a-f]{64}$")))
  ' "$manifest" >/dev/null || die 'Self-test manifest is not the exact six-archive CLI release'
  jq -e --slurpfile manifest "$manifest" '
    ([.artifacts[] | [.product, .platform, .archive.name, .archive.sha256, .executable.path]] | sort) ==
    ([$manifest[0].artifacts[] | [.product, .platform, .name, .sha256, ("bin/" + .launcher)]] | sort) and
    all(.artifacts[]; (.executable.sha256 | test("^[0-9a-f]{64}$")))
  ' "$ledger" >/dev/null || die 'Self-test ledger differs from the six-archive manifest'
  [ "$(find "$bundle_dir" -maxdepth 1 -name '*.zip' -type f | wc -l | tr -d ' ')" = 6 ] || die 'Self-test bundle ZIP count differs'
  [ "$(find "$bundle_dir" -maxdepth 1 -name '*.zip.sha256' -type f | wc -l | tr -d ' ')" = 6 ] || die 'Self-test checksum count differs'
  while IFS= read -r name; do
    [ "$(cat "$bundle_dir/$name.sha256")" = "$(sha256_file "$bundle_dir/$name")  $name" ] || die "Self-test wrong checksum for $name"
    grep -F -q "$name" "$bundle_dir/README.md" || die "Self-test README omitted $name"
  done < <(jq -r '.artifacts[].name' "$manifest")
}

create_fake_aws() {
  local fake_dir="$1"
  mkdir -p "$fake_dir"
  cat > "$fake_dir/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
path() { printf '%s/%s\n' "$SELF_STORAGE" "${1#s3://downloads-bucket.example.invalid/}"; }
printf '%s\n' "$*" >> "$SELF_CALLS"
case "$2" in
  cp)
    source="$3"
    destination="$4"
    if [[ "$source" == s3://* ]]; then
      source="$(path "$source")"
      [ -f "$source" ] || { printf 'NoSuchKey\n' >&2; exit 1; }
      cp "$source" "$destination"
    else
      [[ -z "${SELF_FAIL_UPLOAD:-}" || "$source" != *"$SELF_FAIL_UPLOAD" ]] || exit 1
      destination="$(path "$destination")"
      mkdir -p "$(dirname "$destination")"
      cp "$source" "$destination"
    fi
    ;;
  ls)
    directory="$(path "$3")"
    [ ! -d "$directory" ] || find "$directory" -type f | sort | while IFS= read -r file; do
      printf 'x x x %s\n' "${file#"$SELF_STORAGE"/}"
    done
    ;;
  *) exit 2 ;;
esac
EOF
  chmod +x "$fake_dir/aws"
}

self_test() {
  local temp input bundle index storage fake calls old_index status remote_ledger remote_manifest
  local original_archive changed_bundle regenerated_bundle prerelease_input prerelease_bundle prerelease_index
  local metadata_input metadata_bundle metadata_index wasm_input wasm_bundle
  local calls_before cleanup_done resolved_version malformed_bundle malformed_index malformed_version valid_version
  resolved_version="$(CLI_VERSION='' PLUGIN_VERSION='999.999.999' resolve_cli_version)"
  [ "$resolved_version" = "$(read_cli_version)" ] || die 'Self-test accepted PLUGIN_VERSION as the CLI release authority'
  temp="$(mktemp -d "${TMPDIR:-/tmp}/cli-downloads-self-test.XXXXXX")"
  cleanup() { rm -rf "$temp"; cleanup_done=true; }
  trap cleanup RETURN
  input="$temp/input"
  bundle="$temp/bundle"
  index="$temp/index"
  storage="$temp/storage"
  fake="$temp/fake"
  calls="$temp/calls"
  mkdir -p "$input" "$storage/resources/hexana/cli"

  CLI_VERSION='0.2.0'
  CLI_ROOT_PUBLIC_PREFIX='https://download.jetbrains.com/resources/hexana/cli'
  CLI_PUBLIC_PREFIX="$CLI_ROOT_PUBLIC_PREFIX/$CLI_VERSION"
  CLI_ROOT_S3_PREFIX='s3://downloads-bucket.example.invalid/resources/hexana/cli/'
  CLI_S3_PREFIX="$CLI_ROOT_S3_PREFIX$CLI_VERSION/"
  CLI_INDEX_S3_URI="${CLI_ROOT_S3_PREFIX}releases.json"
  CLI_INDEX_DIR="$index"
  CLI_README_TEMPLATE='cli/packaging/CLI_DOWNLOADS_README.md.template'
  CLI_RELEASE_DESCRIPTOR='cli/metadata/src/main/gen/cli-release-artifacts.json'
  CLI_RELEASE_ARTIFACT_DIR="$input"
  GITHUB_RELEASE_ASSET_BASE_URL=''
  GIT_REVISION='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  TEAMCITY_BUILD_ID='12345'
  TEAMCITY_BUILD_URL='https://build.example/12345'
  GENERATED_AT='2026-09-09T00:00:00Z'
  PUBLISH_DOWNLOADS=false
  CLI_EXISTING_RELEASES_INDEX_FILE=''

  fixture_release_input "$input"

  for valid_version in '1.2.3-alpha.1' '1.2.3+build-1' '1.2.3-alpha.1+build-1'; do
    validate_semver CLI_VERSION "$valid_version"
  done
  for malformed_version in \
    '1.2.3not-semver' '.1.2.3' '1..2.3' '1.2.3.' \
    '1.2.3-.alpha' '1.2.3-alpha..beta' '1.2.3-alpha.' \
    '1.2.3+.build' '1.2.3+build..meta' '1.2.3+build.' \
    '01.2.3' '1.2.3-01' '1.2.3+build+again'; do
    malformed_bundle="$temp/malformed-$malformed_version-bundle"
    malformed_index="$temp/malformed-$malformed_version-index"
    set +e
    env CLI_DOWNLOADS_BUNDLE_SELF_TEST=false \
      CLI_VERSION="$malformed_version" \
      CLI_RELEASE_ARTIFACT_DIR="$input" \
      CLI_DOWNLOADS_BUNDLE_DIR="$malformed_bundle" \
      CLI_DOWNLOADS_INDEX_DIR="$malformed_index" \
      PUBLISH_DOWNLOADS=false \
      bash "$0" > "$temp/malformed-$malformed_version.out" 2>&1
    status=$?
    set -e
    [ "$status" -ne 0 ] && [ ! -e "$malformed_bundle" ] && [ ! -e "$malformed_index" ] || die "Self-test allowed a malformed CLI_VERSION mutation: $malformed_version"
  done

  printf 'unexpected\n' > "$input/not-a-cli-input.txt"
  set +e
  (validate_input "$temp/wrong-product-matrix.json") > "$temp/wrong-product.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || die 'Self-test accepted a wrong product input'
  rm -f "$input/not-a-cli-input.txt"

  jq '.sourceRevision = "not-a-sha"' "$input/macos-arm64-identity.json" > "$temp/invalid-identity.json"
  mv "$temp/invalid-identity.json" "$input/macos-arm64-identity.json"
  set +e
  (validate_input "$temp/invalid-identity-matrix.json") > "$temp/invalid-identity.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || die 'Self-test accepted an invalid identity'
  fixture_identity "$input" macos-arm64

  old_index="$temp/old-index.json"
  jq -n --arg root "$CLI_ROOT_PUBLIC_PREFIX" '
    {schemaVersion: 1, downloadsRoot: $root, releases: [{
      version: "0.1.5",
      manifestUrl: ($root + "/0.1.5/hexana-cli-release-0.1.5.json"),
      downloadsPrefix: ($root + "/0.1.5"),
      platforms: ["linux-x64", "macos-arm64"],
      artifactKinds: ["script-cli-native", "standard-cli-native"]
    }]}' > "$old_index"
  CLI_EXISTING_RELEASES_INDEX_FILE="$old_index"
  make_bundle "$bundle"
  assert_six_archive_bundle "$bundle"
  make_bundle "$temp/trailing-bundle/"
  cmp -s "$bundle/hexana-cli-release-$CLI_VERSION.json" "$temp/trailing-bundle/hexana-cli-release-$CLI_VERSION.json" || die 'Self-test trailing-slash bundle differs'
  make_index "$bundle" "$index"
  jq -e '.stable.version == "0.2.0" and (.releases | length == 2)' "$index/releases.json" >/dev/null
  jq -e '.releases[] | select(.version == "0.2.0") |
    .platforms == ["linux-x64", "macos-arm64"] and
    .artifactKinds == ["hexana-wasm-tools-native", "script-cli-native", "standard-cli-native"]' \
    "$index/releases.json" >/dev/null || die 'Self-test index omitted the Wasm product or changed the target matrix'
  jq -e 'all(.artifacts[]; .githubReleaseAssetUrl == null)' "$bundle/hexana-cli-release-$CLI_VERSION.json" >/dev/null || die 'Self-test did not accept an empty GitHub base URL'
  original_archive="$temp/original-linux-standard.zip"
  cp "$input/$(archive_name linux-x64 hexana-cli)" "$original_archive"

  create_fake_aws "$fake"
  SELF_STORAGE="$storage"
  SELF_CALLS="$calls"
  export SELF_STORAGE SELF_CALLS
  PATH="$fake:$PATH"
  PUBLISH_DOWNLOADS=true
  cp "$old_index" "$storage/resources/hexana/cli/releases.json"

  local saved_public_prefix="$CLI_PUBLIC_PREFIX"
  CLI_PUBLIC_PREFIX="$CLI_ROOT_PUBLIC_PREFIX/wrong/$CLI_VERSION"
  set +e
  (validate_destinations) > "$temp/wrong-prefix.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] && [ ! -e "$calls" ] || die 'Self-test allowed a wrong destination prefix'
  CLI_PUBLIC_PREFIX="$saved_public_prefix"
  validate_destinations

  SELF_FAIL_UPLOAD="$(archive_name linux-x64 hexana-wasm-tools)"
  export SELF_FAIL_UPLOAD
  set +e
  (upload_prefix "$bundle" "$CLI_S3_PREFIX" "$temp/incomplete-cache") > "$temp/incomplete.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || die 'Self-test accepted an incomplete upload'
  [ "$(sha256_file "$storage/resources/hexana/cli/releases.json")" = "$(sha256_file "$old_index")" ] || die 'Self-test promoted an incomplete upload'
  unset SELF_FAIL_UPLOAD

  upload_prefix "$bundle" "$CLI_S3_PREFIX" "$temp/retry-cache"
  [ "$(remote_names "$CLI_S3_PREFIX")" = "$(bundle_names "$bundle")" ] || die 'Self-test incomplete-upload retry lost a CLI artifact'
  remote_ledger="$(sha256_file "$storage/resources/hexana/cli/$CLI_VERSION/hexana-cli-release-$CLI_VERSION.ledger.json")"
  remote_manifest="$(sha256_file "$storage/resources/hexana/cli/$CLI_VERSION/hexana-cli-release-$CLI_VERSION.json")"
  make_index "$bundle" "$index"
  promote_index "$temp/index-snapshot.json"

  fixture_zip "$input/$(archive_name linux-x64 hexana-cli)" hexana-cli changed
  fixture_identity "$input" linux-x64
  changed_bundle="$temp/changed-bundle"
  make_bundle "$changed_bundle"
  calls_before="$(grep -F -c "s3 cp $changed_bundle/" "$calls" || true)"
  set +e
  (upload_prefix "$changed_bundle" "$CLI_S3_PREFIX" "$temp/changed-cache") > "$temp/changed.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || die 'Self-test accepted changed bytes for an immutable version'
  [ "$calls_before" = "$(grep -F -c "s3 cp $changed_bundle/" "$calls" || true)" ] || die 'Self-test mutated storage before rejecting changed bytes'

  CLI_INDEX_SNAPSHOT="$(sha256_file "$storage/resources/hexana/cli/releases.json")"
  jq '.generatedAt = "later"' "$storage/resources/hexana/cli/releases.json" > "$temp/stale-index.json"
  mv "$temp/stale-index.json" "$storage/resources/hexana/cli/releases.json"
  set +e
  (promote_index "$temp/stale-snapshot.json") > "$temp/stale.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || die 'Self-test accepted a stale index promotion'

  cp "$original_archive" "$input/$(archive_name linux-x64 hexana-cli)"
  fixture_identity "$input" linux-x64
  GENERATED_AT='2026-09-10T00:00:00Z'
  TEAMCITY_BUILD_ID='67890'
  TEAMCITY_BUILD_URL='https://build.example/67890'
  regenerated_bundle="$temp/regenerated-bundle"
  make_bundle "$regenerated_bundle"
  upload_prefix "$regenerated_bundle" "$CLI_S3_PREFIX" "$temp/regenerated-cache"
  [ "$(sha256_file "$regenerated_bundle/hexana-cli-release-$CLI_VERSION.json")" = "$remote_manifest" ] || die 'Self-test did not retain the accepted manifest during retry'
  [ "$(sha256_file "$storage/resources/hexana/cli/$CLI_VERSION/hexana-cli-release-$CLI_VERSION.ledger.json")" = "$remote_ledger" ] || die 'Self-test replaced an accepted ledger during retry'

  CLI_VERSION='0.3.0-rc.1'
  CLI_PUBLIC_PREFIX="$CLI_ROOT_PUBLIC_PREFIX/$CLI_VERSION"
  CLI_S3_PREFIX="$CLI_ROOT_S3_PREFIX$CLI_VERSION/"
  prerelease_input="$temp/prerelease-input"
  fixture_release_input "$prerelease_input"
  CLI_RELEASE_ARTIFACT_DIR="$prerelease_input"
  CLI_EXISTING_RELEASES_INDEX_FILE="$index/releases.json"
  PUBLISH_DOWNLOADS=false
  prerelease_bundle="$temp/prerelease-bundle"
  prerelease_index="$temp/prerelease-index"
  make_bundle "$prerelease_bundle"
  make_index "$prerelease_bundle" "$prerelease_index"
  jq -e '.latest == "0.3.0-rc.1" and .stable.version == "0.2.0"' "$prerelease_index/releases.json" >/dev/null || die 'Self-test promoted a prerelease to stable'

  CLI_VERSION='0.2.1+build-1'
  CLI_PUBLIC_PREFIX="$CLI_ROOT_PUBLIC_PREFIX/$CLI_VERSION"
  CLI_S3_PREFIX="$CLI_ROOT_S3_PREFIX$CLI_VERSION/"
  metadata_input="$temp/metadata-input"
  fixture_release_input "$metadata_input"
  CLI_RELEASE_ARTIFACT_DIR="$metadata_input"
  CLI_EXISTING_RELEASES_INDEX_FILE="$index/releases.json"
  metadata_bundle="$temp/metadata-bundle"
  metadata_index="$temp/metadata-index"
  make_bundle "$metadata_bundle"
  make_index "$metadata_bundle" "$metadata_index"
  jq -e '.stable.version == "0.2.1+build-1"' "$metadata_index/releases.json" >/dev/null || die 'Self-test treated build metadata as a prerelease'

  CLI_VERSION='0.4.0'
  CLI_PUBLIC_PREFIX="$CLI_ROOT_PUBLIC_PREFIX/$CLI_VERSION"
  CLI_S3_PREFIX="$CLI_ROOT_S3_PREFIX$CLI_VERSION/"
  wasm_input="$temp/wasm-input"
  wasm_bundle="$temp/wasm-bundle"
  fixture_release_input "$wasm_input"
  CLI_RELEASE_ARTIFACT_DIR="$wasm_input"
  make_bundle "$wasm_bundle"
  assert_six_archive_bundle "$wasm_bundle"

  rm "$wasm_input/$(archive_name linux-x64 hexana-wasm-tools)" "$wasm_input/$(archive_name macos-arm64 hexana-wasm-tools)"
  set +e
  (make_bundle "$temp/old-four-bundle") > "$temp/old-four.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] && grep -F -q 'missing=' "$temp/old-four.out" && [ ! -e "$temp/old-four-bundle" ] || die 'Self-test accepted the old four-archive input'
  fixture_release_input "$wasm_input"

  rm "$wasm_input/$(archive_name linux-x64 hexana-wasm-tools)"
  set +e
  (make_bundle "$temp/missing-third-bundle") > "$temp/missing-third.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] && grep -F -q 'missing=' "$temp/missing-third.out" || die 'Self-test accepted a missing descriptor-required archive'
  fixture_release_input "$wasm_input"

  printf 'extra\n' > "$wasm_input/extra-product.zip"
  set +e
  (make_bundle "$temp/extra-third-bundle") > "$temp/extra-third.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] && grep -F -q 'extra=' "$temp/extra-third.out" || die 'Self-test accepted an extra product archive'
  rm "$wasm_input/extra-product.zip"

  jq '(.artifacts[] | select(.name | startswith("hexana-wasm-tools-")) | .name) = "wrong-product.zip"' \
    "$wasm_input/linux-x64-identity.json" > "$temp/wrong-product-identity.json"
  mv "$temp/wrong-product-identity.json" "$wasm_input/linux-x64-identity.json"
  set +e
  (make_bundle "$temp/wrong-third-bundle") > "$temp/wrong-third.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] && grep -F -q 'Unknown archive in linux-x64-identity.json' "$temp/wrong-third.out" || die 'Self-test accepted a wrong product identity'
  fixture_identity "$wasm_input" linux-x64

  fixture_zip "$wasm_input/$(archive_name linux-x64 hexana-wasm-tools)" hexana-cli fixture
  fixture_identity "$wasm_input" linux-x64
  set +e
  (make_bundle "$temp/wrong-launcher-bundle") > "$temp/wrong-launcher.out" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] && grep -F -q 'wrong layout' "$temp/wrong-launcher.out" || die 'Self-test accepted a wrong archive launcher'
  fixture_release_input "$wasm_input"
  make_bundle "$temp/restored-wasm-bundle"
  assert_six_archive_bundle "$temp/restored-wasm-bundle"
  log 'Breaking proofs passed: old four/missing Wasm archive, wrong identity and wrong launcher rejected; exact six restored'

  PUBLISH_DOWNLOADS=true
  upload_prefix "$wasm_bundle" "$CLI_S3_PREFIX" "$temp/wasm-cache"
  upload_prefix "$wasm_bundle" "$CLI_S3_PREFIX" "$temp/wasm-retry-cache"
  [ "$(remote_names "$CLI_S3_PREFIX")" = "$(bundle_names "$wasm_bundle")" ] || die 'Self-test six-archive retry changed the release inventory'

  trap - RETURN
  cleanup
  [ "$cleanup_done" = true ] && [ ! -e "$temp" ] || die 'Self-test cleanup failed'
  log 'CLI downloads self-test passed'
}

main() {
  require_tool jq
  require_tool python3
  if [ "$(to_bool "${CLI_DOWNLOADS_BUNDLE_SELF_TEST:-false}" CLI_DOWNLOADS_BUNDLE_SELF_TEST)" = true ]; then
    self_test
    return
  fi

  CLI_VERSION="$(resolve_cli_version)"
  [ -n "$CLI_VERSION" ] || die 'Could not read hexanaToolsVersion from gradle.properties'
  validate_semver CLI_VERSION "$CLI_VERSION"
  validate_segment CLI_VERSION "$CLI_VERSION"
  CLI_RELEASE_ARTIFACT_DIR="${CLI_RELEASE_ARTIFACT_DIR:?CLI_RELEASE_ARTIFACT_DIR is required}"
  CLI_RELEASE_DESCRIPTOR="${CLI_RELEASE_DESCRIPTOR:-cli/metadata/src/main/gen/cli-release-artifacts.json}"
  CLI_DOWNLOADS_BUNDLE_DIR="${CLI_DOWNLOADS_BUNDLE_DIR:-cli/out/downloads-release}"
  CLI_DOWNLOADS_INDEX_DIR="${CLI_DOWNLOADS_INDEX_DIR:-cli/out/downloads-index}"
  CLI_DOWNLOADS_README_TEMPLATE="${CLI_DOWNLOADS_README_TEMPLATE:-cli/packaging/CLI_DOWNLOADS_README.md.template}"
  CLI_DOWNLOADS_ROOT_PUBLIC_PREFIX="${CLI_DOWNLOADS_ROOT_PUBLIC_PREFIX:-https://download.jetbrains.com/resources/hexana/cli}"
  CLI_DOWNLOADS_PUBLIC_PREFIX="${CLI_DOWNLOADS_PUBLIC_PREFIX:-$(join_url "$CLI_DOWNLOADS_ROOT_PUBLIC_PREFIX" "$CLI_VERSION")}"
  CLI_DOWNLOADS_ROOT_S3_PREFIX="${CLI_DOWNLOADS_ROOT_S3_PREFIX:-s3://downloads-bucket.example.invalid/resources/hexana/cli/}"
  CLI_DOWNLOADS_S3_PREFIX="${CLI_DOWNLOADS_S3_PREFIX:-$(join_s3 "$CLI_DOWNLOADS_ROOT_S3_PREFIX" "$CLI_VERSION")/}"
  CLI_DOWNLOADS_RELEASES_INDEX_S3_URI="${CLI_DOWNLOADS_RELEASES_INDEX_S3_URI:-$(join_s3 "$CLI_DOWNLOADS_ROOT_S3_PREFIX" releases.json)}"

  CLI_BUNDLE_DIR="$CLI_DOWNLOADS_BUNDLE_DIR"
  CLI_INDEX_DIR="$CLI_DOWNLOADS_INDEX_DIR"
  CLI_README_TEMPLATE="$CLI_DOWNLOADS_README_TEMPLATE"
  CLI_ROOT_PUBLIC_PREFIX="$CLI_DOWNLOADS_ROOT_PUBLIC_PREFIX"
  CLI_PUBLIC_PREFIX="$CLI_DOWNLOADS_PUBLIC_PREFIX"
  CLI_ROOT_S3_PREFIX="$CLI_DOWNLOADS_ROOT_S3_PREFIX"
  CLI_S3_PREFIX="$CLI_DOWNLOADS_S3_PREFIX"
  CLI_INDEX_S3_URI="$CLI_DOWNLOADS_RELEASES_INDEX_S3_URI"
  CLI_EXISTING_RELEASES_INDEX_FILE="${CLI_EXISTING_RELEASES_INDEX_FILE:-}"
  GITHUB_RELEASE_ASSET_BASE_URL="${GITHUB_RELEASE_ASSET_BASE_URL:-}"
  if [ -z "$GITHUB_RELEASE_ASSET_BASE_URL" ] && [ -n "${PUBLIC_REPO_SLUG:-}" ]; then
    GITHUB_RELEASE_ASSET_BASE_URL="https://github.com/$PUBLIC_REPO_SLUG/releases/download/${RELEASE_TAG:-v$CLI_VERSION}"
  fi
  GIT_REVISION="${GIT_REVISION:-${BUILD_VCS_NUMBER:-}}"
  TEAMCITY_BUILD_ID="${TEAMCITY_BUILD_ID:-${BUILD_ID:-}}"
  TEAMCITY_BUILD_URL="${TEAMCITY_BUILD_URL:-${BUILD_URL:-}}"
  GENERATED_AT="${GENERATED_AT:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}"
  PUBLISH_DOWNLOADS="$(to_bool "${PUBLISH_DOWNLOADS:-false}" PUBLISH_DOWNLOADS)"

  [ "$PUBLISH_DOWNLOADS" = false ] || {
    [ "$CLI_RELEASE_DESCRIPTOR" = 'cli/metadata/src/main/gen/cli-release-artifacts.json' ] || die 'Publication requires the checked-in generated CLI release descriptor'
    require_tool aws
    validate_destinations
  }
  make_bundle "$CLI_BUNDLE_DIR"
  make_index "$CLI_BUNDLE_DIR" "$CLI_INDEX_DIR"
  if [ "$PUBLISH_DOWNLOADS" = true ]; then
    upload_prefix "$CLI_BUNDLE_DIR" "$CLI_S3_PREFIX" "$CLI_INDEX_DIR/.remote"
    make_index "$CLI_BUNDLE_DIR" "$CLI_INDEX_DIR"
    promote_index "$CLI_INDEX_DIR/.snapshot"
  else
    log 'PUBLISH_DOWNLOADS=false; upload skipped'
  fi
}

main "$@"
