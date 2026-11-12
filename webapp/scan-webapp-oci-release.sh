#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

release_branch="${HEXANA_WEBAPP_RELEASE_BRANCH:-}"
source_branch="${HEXANA_WEBAPP_SOURCE_BRANCH:-}"
revision="${HEXANA_SOURCE_REVISION:-}"
input_dir="$repo_root/webapp/build/oci-release-scan-input"
receipt="$input_dir/evidence/receipt.json"
package_dir="$input_dir/static-package"
work_dir="$repo_root/webapp/build/vulnerability-scan-work"
cache_dir="$work_dir/trivy-cache"
image_archive="$work_dir/image.tar"
output_dir="$repo_root/webapp/build/vulnerability-scan-evidence"

[[ "$#" == 0 ]] || { echo "scan-webapp-oci-release.sh does not accept arguments." >&2; exit 2; }
branch_regex='^release/webapp/(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
revision_regex='^[0-9a-f]{40}$'
if [[ -n "$release_branch" && -n "$source_branch" ]]; then
  echo "Set exactly one of HEXANA_WEBAPP_RELEASE_BRANCH or HEXANA_WEBAPP_SOURCE_BRANCH." >&2
  exit 2
elif [[ -n "$release_branch" ]]; then
  [[ "$release_branch" =~ $branch_regex ]] || {
    echo "Scan Webapp OCI Release requires HEXANA_WEBAPP_RELEASE_BRANCH=release/webapp/<numeric-major>.<numeric-minor>; current branch: $release_branch" >&2
    exit 2
  }
  branch="$release_branch"
elif [[ "$source_branch" == "master" ]]; then
  branch="$source_branch"
else
  echo "Set HEXANA_WEBAPP_RELEASE_BRANCH=release/webapp/<numeric-major>.<numeric-minor> or HEXANA_WEBAPP_SOURCE_BRANCH=master." >&2
  exit 2
fi
[[ "$revision" =~ $revision_regex ]] || {
  echo "Webapp OCI scanning requires HEXANA_SOURCE_REVISION to be a lowercase full 40-character VCS revision." >&2
  exit 2
}
image_repository="${HEXANA_WEBAPP_IMAGE_REPOSITORY:-}"
[[ -n "$image_repository" ]] || {
  echo "Webapp OCI scanning requires HEXANA_WEBAPP_IMAGE_REPOSITORY." >&2
  exit 2
}
export HEXANA_WEBAPP_IMAGE_REPOSITORY
for command in docker node; do
  command -v "$command" >/dev/null || { echo "Required command is unavailable: $command" >&2; exit 2; }
done
scanner="$(node webapp/vulnerability-report-contract.mjs scanner-reference)" || {
  echo "Could not resolve the approved scanner reference." >&2
  exit 2
}
[[ -n "$scanner" ]] || { echo "The approved scanner reference is empty." >&2; exit 2; }
[[ -f "$receipt" ]] || { echo "Required OCI evidence receipt is missing: $receipt" >&2; exit 2; }
[[ -f "$package_dir/sbom.cdx.json" ]] || { echo "Required bundled SBOM is missing: $package_dir/sbom.cdx.json" >&2; exit 2; }
node webapp/oci-evidence-contract.mjs verify-receipt \
  --package-dir "$package_dir" --receipt "$receipt" \
  --source-branch "$branch" --revision "$revision" || {
  echo "Downloaded OCI evidence does not match the requested source identity." >&2
  exit 2
}

gate_reached=false
cleanup() {
  rm -f "$image_archive"
  rm -rf "$cache_dir"
}
finish() {
  status="$?"
  trap - EXIT
  cleanup || status=2
  if [[ "$gate_reached" != true && "$status" != 0 ]]; then status=2; fi
  exit "$status"
}
trap finish EXIT
rm -rf "$work_dir" "$output_dir"
mkdir -p "$cache_dir" "$output_dir"

image_ref="$(node - "$receipt" <<'NODE'
const fs = require("node:fs");
const [receiptFile] = process.argv.slice(2);
const receipt = JSON.parse(fs.readFileSync(receiptFile, "utf8"));
const digest = /^sha256:[0-9a-f]{64}$/;
const reference = receipt.image?.reference;
if (typeof reference !== "string" || reference !== `${process.env.HEXANA_WEBAPP_IMAGE_REPOSITORY}@${receipt.image?.manifestDigest}` ||
    !digest.test(receipt.image?.manifestDigest ?? "") || receipt.image?.platform?.os !== "linux" ||
    receipt.image?.platform?.architecture !== "amd64") {
  throw new Error("Downloaded receipt does not contain the canonical linux/amd64 digest reference");
}
process.stdout.write(reference);
NODE
)"

./gradlew :webapp:verifyWebappOciEvidenceReceipt \
  "-PhexanaWebappOciReceipt=$receipt" \
  "-PhexanaWebappOciPackageDirectory=$package_dir"

docker version >/dev/null
docker info >/dev/null
docker pull --platform linux/amd64 "$scanner" >/dev/null
scanner_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$scanner")"
[[ "$scanner_platform" == "linux/amd64" ]] || { echo "Pinned Trivy image is not linux/amd64." >&2; exit 2; }
docker pull --platform linux/amd64 "$image_ref" >/dev/null
docker save "$image_ref" -o "$image_archive"
test -s "$image_archive"

uid="$(id -u)"
gid="$(id -g)"
hardened=(--rm --platform linux/amd64 --cap-drop ALL --security-opt no-new-privileges --user "$uid:$gid")
docker run "${hardened[@]}" \
  --mount "type=bind,src=$cache_dir,dst=/cache" \
  "$scanner" image --cache-dir /cache --download-db-only --no-progress

docker run "${hardened[@]}" --network none \
  --mount "type=bind,src=$cache_dir,dst=/cache,readonly" \
  "$scanner" version --cache-dir /cache --format json \
  > "$output_dir/scanner-version.json"

scan_flags=(--cache-dir /cache --skip-db-update --skip-java-db-update --skip-version-check
  --offline-scan --disable-telemetry --scanners vuln
  --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL --list-all-pkgs --format json)
docker run "${hardened[@]}" --network none \
  --mount "type=bind,src=$cache_dir,dst=/cache" \
  --mount "type=bind,src=$image_archive,dst=/input/image.tar,readonly" \
  --mount "type=bind,src=$output_dir,dst=/output" \
  "$scanner" image "${scan_flags[@]}" --output /output/image-report.json --input /input/image.tar
docker run "${hardened[@]}" --network none \
  --mount "type=bind,src=$cache_dir,dst=/cache" \
  --mount "type=bind,src=$package_dir/sbom.cdx.json,dst=/input/sbom.cdx.json,readonly" \
  --mount "type=bind,src=$output_dir,dst=/output" \
  "$scanner" sbom "${scan_flags[@]}" --output /output/sbom-report.json /input/sbom.cdx.json

evaluated_at="$(node -p 'new Date().toISOString()')"
contract_status=0
node webapp/vulnerability-report-contract.mjs verify \
  --receipt "$receipt" --package-dir "$package_dir" \
  --image-archive "$image_archive" \
  --image-report "$output_dir/image-report.json" \
  --sbom-report "$output_dir/sbom-report.json" \
  --version-json "$output_dir/scanner-version.json" \
  --scanner-ref "$scanner" --evaluated-at "$evaluated_at" \
  --output "$output_dir/summary.json" || contract_status="$?"

if [[ "$contract_status" == 0 || "$contract_status" == 1 ]]; then
  test -f "$output_dir/summary.json"
  node - "$output_dir/summary.json" "$output_dir/summary.txt" <<'NODE'
const fs = require("node:fs");
const [input, output] = process.argv.slice(2);
const evidence = JSON.parse(fs.readFileSync(input, "utf8"));
const severityOrder = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
const total = severityOrder.reduce((sum, severity) => sum + evidence.counts[severity], 0);
const quoted = value => JSON.stringify(value);
const sourceLine = evidence.schemaVersion === 1
  ? `release=${evidence.release.branch} ${evidence.release.version} ${evidence.release.revision}`
  : `source=${evidence.source.branch} ${evidence.source.version} ${evidence.source.revision}`;
const lines = [
  `decision=${evidence.decision}`,
  `image=${evidence.image.reference}`,
  sourceLine,
  `scanner=${evidence.scanner.version} ${evidence.scanner.digest}`,
  `database=schema-${evidence.database.version} updated=${evidence.database.updatedAt} downloaded=${evidence.database.downloadedAt} next-update=${evidence.database.nextUpdate}`,
  `findings=${severityOrder.map(severity => `${severity}:${evidence.counts[severity]}`).join(" ")} TOTAL:${total}`,
];
evidence.findings.forEach((finding, index) => lines.push([
  `finding[${index + 1}]`,
  `severity=${quoted(finding.severity)}`,
  `vulnerability=${quoted(finding.vulnerabilityId)}`,
  `package=${quoted(finding.packageName)}`,
  `installed=${quoted(finding.installedVersion)}`,
  `fixed=${quoted(finding.fixedVersion || "unavailable")}`,
  `report=${quoted(finding.report)}`,
  `target=${quoted(finding.target)}`,
].join(" ")));
fs.writeFileSync(output, lines.join("\n") + "\n");
for (const severity of severityOrder) {
  console.log(`##teamcity[buildStatisticValue key='hexana.webapp.vulnerabilities.${severity.toLowerCase()}' value='${evidence.counts[severity]}']`);
}
console.log(`##teamcity[buildStatisticValue key='hexana.webapp.vulnerabilities.total' value='${total}']`);
if (evidence.decision === "fail") {
  console.log(`##teamcity[buildProblem identity='hexanaWebappOciHighCriticalVulnerabilities' description='Webapp OCI vulnerability gate found HIGH=${evidence.counts.HIGH}, CRITICAL=${evidence.counts.CRITICAL}']`);
}
NODE
else
  test "$contract_status" == 2
  test ! -e "$output_dir/summary.json"
  echo "Webapp vulnerability evidence validation failed operationally; no summary evidence was published." >&2
fi

gate_reached=true
exit "$contract_status"
