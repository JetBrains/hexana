#!/usr/bin/env bash
set -euo pipefail
set +x

CLEANUP_DIRS=()

cleanup_on_exit() {
  local dir
  for dir in "${CLEANUP_DIRS[@]:-}"; do
    [ -n "$dir" ] && rm -rf "$dir"
  done
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

versioned_release_artifact() {
  local dir="$1"
  local file_name="$2"
  local file="$dir/$file_name"
  if [ -f "$file" ]; then
    printf '%s\n' "$file"
  fi
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

  [ -n "$server_zip" ] || die "No server-$PLUGIN_VERSION.zip produced under mcp/build/distributions or mcp/server/build/distributions"
  [ -n "$claude_zip" ] || die "No hexana-claude-plugin-$PLUGIN_VERSION.zip produced under mcp/build/distributions"
  [ -n "$codex_zip" ] || die "No hexana-codex-plugin-$PLUGIN_VERSION.zip produced under mcp/build/distributions"

  cp "$server_zip" "$release_dir/"
  cp "$claude_zip" "$release_dir/"
  cp "$codex_zip" "$release_dir/"
}

write_release_notes() {
  release_notes="$release_dir/release-notes.md"
  {
    printf 'Hexana MCP plugin distribution %s\n\n' "$PLUGIN_VERSION"
    if [ -n "$BUILD_URL_VALUE" ]; then
      printf -- '- TeamCity build: %s\n' "$BUILD_URL_VALUE"
    fi
    if [ -n "$TEAMCITY_BUILD_BRANCH_VALUE" ]; then
      printf -- '- Branch: %s\n' "$TEAMCITY_BUILD_BRANCH_VALUE"
    fi
    printf -- '- Claude and Codex plugin smoke tests passed in the TeamCity build chain.\n'
    printf -- '- Public marketplace repository update is published through a pull request.\n'
  } > "$release_notes"
}

publish_github_release() {
  if [ "$DRY_RUN" = "true" ]; then
    log "Dry run requested; not creating or updating GitHub release $RELEASE_TAG"
    return
  fi

  log "Publishing GitHub release $RELEASE_TAG in $PUBLIC_REPO_SLUG"
  if gh release view "$RELEASE_TAG" --repo "$PUBLIC_REPO_SLUG" >/dev/null 2>&1; then
    gh release upload "$RELEASE_TAG" "$release_dir"/*.zip --repo "$PUBLIC_REPO_SLUG" --clobber
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
  local forbidden_content='(/Users/|/private/|PUBLISH_GITHUB_TOKEN_PARAM|TEST_GITHUB_TOKEN_PARAM|git\.jetbrains\.team|GH_TOKEN|GITHUB_TOKEN|TEAMCITY_BUILD|%hexana\.github|BEGIN PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|sk-|AKIA|password=|secret=|token=)'

  if strings "$file" | grep -E -q "$forbidden_content"; then
    die "Release artifact $zip_name contains forbidden string pattern in $rel"
  fi
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
}

check_embedded_jar_entries() {
  local zip_name="$1"
  local root="$2"
  local jar_file
  local jar_rel
  local suspicious_entry

  while IFS= read -r jar_file; do
    jar_rel="$(artifact_relative_path "$root" "$jar_file")"
    suspicious_entry="$(jar tf "$jar_file" | grep -E '(^|/)(src|build|target|out|\.gradle|\.idea|\.teamcity|private|secret|secrets)(/|$)|(^|/)\.env$|(^|/)mapping\.txt$|\.(java|kt|kts|pem|key|p12|keystore|jks)$' | head -n 1 || true)"
    if [ -n "$suspicious_entry" ]; then
      die "Release artifact $zip_name contains suspicious entry in $jar_rel: $suspicious_entry"
    fi
  done < <(find "$root" -type f -name '*.jar' -print)
}

check_obfuscated_runtime_layout() {
  local zip_name="$1"
  local root="$2"
  local jar_file
  local jar_rel
  local found_obfuscated="false"
  local leaked_hexana_class

  while IFS= read -r jar_file; do
    jar_rel="$(artifact_relative_path "$root" "$jar_file")"
    case "$(basename "$jar_file")" in
      hexana-runtime-obfuscated.jar)
        found_obfuscated="true"
        ;;
      analytics-jvm.jar|binaryProvider-jvm.jar|core.jar|dwarf-jvm.jar|encdec-jvm.jar|hexanaDocument-jvm.jar|server-*.jar|wasmParser-jvm.jar)
        die "Release artifact $zip_name contains pre-obfuscation Hexana runtime jar: $jar_rel"
        ;;
      *)
        leaked_hexana_class="$(jar tf "$jar_file" | grep -E '^org/jetbrains/hexana/.*\.class$' | head -n 1 || true)"
        if [ -n "$leaked_hexana_class" ]; then
          die "Release artifact $zip_name contains non-obfuscated Hexana classes in $jar_rel: $leaked_hexana_class"
        fi
        ;;
    esac
  done < <(find "$root" -type f -name '*.jar' -print)

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

  while IFS= read -r jar_file; do
    jar_rel="$(artifact_relative_path "$root" "$jar_file")"

    suspicious_class="$(
      jar tf "$jar_file" |
        grep -E '^org/jetbrains/hexana/.*\.class$' |
        grep -Ev '^org/jetbrains/hexana/mcp/model/|^org/jetbrains/hexana/mcp/server/(MainKt|HexanaLogLevel)\.class$|^org/jetbrains/hexana/providers/a\.class$' |
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
  done < <(find "$root" -type f -name 'hexana-runtime-obfuscated.jar' -print)
}

security_check_release_artifacts() {
  local security_dir
  local zip
  local zip_name
  local extract_dir
  local file
  local rel

  log "Running release artifact security check"
  security_dir="$(mktemp -d "${TMPDIR:-/tmp}/hexana-release-artifact-security.XXXXXX")"
  register_cleanup_dir "$security_dir"

  for zip in "$release_dir"/*.zip; do
    [ -f "$zip" ] || die "No release ZIP artifacts found in $release_dir"
    zip_name="$(basename "$zip")"
    extract_dir="$security_dir/${zip_name%.zip}"
    mkdir -p "$extract_dir"
    unzip -qq "$zip" -d "$extract_dir"

    while IFS= read -r file; do
      rel="$(artifact_relative_path "$extract_dir" "$file")"
      check_artifact_file_name "$zip_name" "$rel"
      check_artifact_file_content "$zip_name" "$rel" "$file"
      check_mcp_json_command_paths "Release artifact $zip_name" "$rel" "$file"
    done < <(find "$extract_dir" -type f -print)

    check_embedded_jar_entries "$zip_name" "$extract_dir"
    check_obfuscated_runtime_layout "$zip_name" "$extract_dir"
    check_obfuscated_runtime_hardening "$zip_name" "$extract_dir"
  done
  rm -rf "$security_dir"
}

validate_public_bundle() {
  [ -d "$GENERATED_PUBLIC_DIR" ] || die "Generated public marketplace bundle is missing: $GENERATED_PUBLIC_DIR"

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
  sync_dir_if_present "plugins/$PLUGIN_NAME/.claude-plugin"
  sync_dir_if_present "plugins/$PLUGIN_NAME/.codex-plugin"
  sync_dir_if_present "plugins/$PLUGIN_NAME/skills"
  sync_dir_if_present "plugins/$PLUGIN_NAME/mcp"
  sync_dir_if_present "plugins/$PLUGIN_NAME/bin"
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
    "plugins/$PLUGIN_NAME/.mcp.json"|"plugins/$PLUGIN_NAME/release.lock.json"|"plugins/$PLUGIN_NAME/README.md")
      return 0
      ;;
    "plugins/$PLUGIN_NAME/.claude-plugin/"*|"plugins/$PLUGIN_NAME/.codex-plugin/"*)
      return 0
      ;;
    "plugins/$PLUGIN_NAME/skills/"*|"plugins/$PLUGIN_NAME/mcp/"*|"plugins/$PLUGIN_NAME/bin/"*|"plugins/$PLUGIN_NAME/server/"*)
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
  local regex='BEGIN PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|sk-|AKIA|password=|secret=|token=|/Users/|/private/|PUBLISH_GITHUB_TOKEN_PARAM|TEST_GITHUB_TOKEN_PARAM|git\.jetbrains\.team|GH_TOKEN|GITHUB_TOKEN|%hexana\.github'
  local file
  local matches
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

run_safety_checks() {
  log "Running staged diff safety checks"
  check_allowed_paths
  check_suspicious_paths
  check_staged_symlinks
  check_secret_patterns
  check_staged_mcp_json_command_paths
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
    if [ -n "$BUILD_URL_VALUE" ]; then
      printf -- '- TeamCity build: %s\n' "$BUILD_URL_VALUE"
    fi
    printf -- '- Branch: `%s`\n' "$BRANCH"
    printf '\n'
    printf 'Updated public files:\n'
    join_lines_for_pr
    printf '\n'
    printf 'Validation:\n'
    local result
    for result in "${VALIDATION_RESULTS[@]}"; do
      printf -- '- %s\n' "$result"
    done
    printf '\n'
    printf 'Smoke tests:\n'
    printf -- '- %s\n' "$SMOKE_TEST_SUMMARY"
    printf '\n'
    printf 'Release lock/checksum:\n'
    printf -- '- %s\n' "$(release_lock_summary)"
    printf '\n'
    printf 'This PR is intended to contain only public distribution metadata, manifests, wrapper scripts, skills, MCP config, and approved public bundled files. It must not contain private source code, TeamCity internals, secrets, or private build artifacts.\n'
  } > "$PR_BODY_FILE"
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
    gh pr comment "$pr_number" --repo "$PUBLIC_REPO_SLUG" --body "Updated from TeamCity build ${BUILD_URL_VALUE:-unknown build}."
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
    return
  fi

  git -C "$CLONE_DIR" commit -m "chore: publish ${PLUGIN_NAME} ${PLUGIN_VERSION}"
  log "Pushing branch $BRANCH"
  git -C "$CLONE_DIR" push --force-with-lease origin "HEAD:refs/heads/$BRANCH"
  create_or_update_pr
}

main() {
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

  mcp_version="$(read_mcp_version)"
  [ -n "$mcp_version" ] || die "Could not read mcpServerVersion from mcp/server/gradle.properties"

  export PLUGIN_NAME="${PLUGIN_NAME:-hexana}"
  export PLUGIN_VERSION="${PLUGIN_VERSION:-$mcp_version}"
  export RELEASE_TAG="${RELEASE_TAG:-v$PLUGIN_VERSION}"
  export GENERATED_PUBLIC_DIR="${GENERATED_PUBLIC_DIR:-mcp/out/teamcity/public-marketplace}"
  export PUBLIC_REPO_BASE_BRANCH="${PUBLIC_REPO_BASE_BRANCH:-main}"
  export AUTO_MERGE="$(to_bool "${AUTO_MERGE:-false}" "AUTO_MERGE")"
  export DRY_RUN="$(to_bool "${DRY_RUN:-false}" "DRY_RUN")"
  export ALLOW_BUNDLED_JAR="$(to_bool "${ALLOW_BUNDLED_JAR:-false}" "ALLOW_BUNDLED_JAR")"
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
  export SMOKE_TEST_SUMMARY="${SMOKE_TEST_SUMMARY:-TeamCity Tests, Claude Plugin Smoke, Codex Plugin Smoke, pre-R8 MCP package smoke, and R8-obfuscated MCP package smoke passed.}"
  BUILD_URL_VALUE="${BUILD_URL:-${TEAMCITY_BUILD_URL:-}}"
  TEAMCITY_BUILD_BRANCH_VALUE="${TEAMCITY_BUILD_BRANCH:-}"

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
  fi

  collect_release_artifacts
  security_check_release_artifacts
  validate_public_bundle
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
