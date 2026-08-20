import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { resolve, relative, sep } from "node:path";

export const repository = "registry.jetbrains.team/p/hx/containers/hexana-webapp";
const digest = /^sha256:[0-9a-f]{64}$/;
const revision = /^[0-9a-f]{40}$/;
const version = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const branch = /^release\/webapp\/(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const evidenceNames = ["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"];
const imageMediaTypePairs = [
  ["application/vnd.oci.image.manifest.v1+json", "application/vnd.oci.image.config.v1+json"],
  ["application/vnd.docker.distribution.manifest.v2+json", "application/vnd.docker.container.image.v1+json"],
];

function fail(message) { throw new Error(`Invalid webapp OCI evidence: ${message}`); }
function hash(file) { return createHash("sha256").update(readFileSync(file)).digest("hex"); }
function options(args) {
  const result = new Map();
  for (let i = 0; i < args.length; i += 2) {
    if (!args[i]?.startsWith("--") || args[i + 1] === undefined || result.has(args[i])) fail("options must be unique key/value pairs");
    result.set(args[i], args[i + 1]);
  }
  return result;
}
function files(root, directory = root) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = resolve(directory, entry.name);
    if (entry.isDirectory()) return files(root, file);
    if (!entry.isFile()) fail(`package contains unsupported entry ${relative(root, file)}`);
    return [relative(root, file).split(sep).join("/")];
  }).sort();
}
function checksums(root) {
  const entries = readFileSync(resolve(root, "checksums.sha256"), "utf8").trimEnd().split("\n").map(line => {
    const match = /^([0-9a-f]{64}) {2}([^/][^\0]*)$/.exec(line);
    if (!match || match[2].split("/").some(part => !part || part === "." || part === "..")) fail(`malformed checksum entry ${line}`);
    return [match[2], match[1]];
  });
  if (!entries.length || new Set(entries.map(([path]) => path)).size !== entries.length) fail("checksums must be non-empty and unique");
  return entries;
}
function identity(root) {
  try { return JSON.parse(readFileSync(resolve(root, "build-identity.json"), "utf8")); } catch { fail("build-identity.json is not JSON"); }
}
function requireReleaseIdentity(value, expected = {}) {
  if (value?.schemaVersion !== 1 || value.product !== "hexana-webapp" || !version.test(value.version) || value.versionSource !== "webapp/gradle.properties" || !revision.test(value.revision)) fail("package identity is not a semantic release identity");
  const match = branch.exec(expected.releaseBranch ?? value.releaseBranch ?? "");
  if (!match || !value.version.startsWith(`${match[1]}.${match[2]}.`)) fail("package version does not match its release branch");
  if (expected.revision && value.revision !== expected.revision) fail("package revision differs from the expected full revision");
  if (expected.version && value.version !== expected.version) fail("package version differs from checked-in webappVersion");
}
export function verifyPackage(packageDir, expected = {}) {
  const root = resolve(packageDir);
  if (!statSync(root).isDirectory()) fail("package directory is missing");
  const entries = checksums(root);
  const expectedFiles = entries.map(([path]) => path).concat("checksums.sha256", "package.sha256").sort();
  if (JSON.stringify(files(root)) !== JSON.stringify(expectedFiles)) fail("package file allowlist differs from checksums");
  for (const [path, value] of entries) if (hash(resolve(root, path)) !== value) fail(`packaged file hash differs for ${path}`);
  if (readFileSync(resolve(root, "package.sha256"), "utf8") !== `${hash(resolve(root, "checksums.sha256"))}\n`) fail("package aggregate checksum differs");
  const packageIdentity = identity(root);
  requireReleaseIdentity(packageIdentity, expected);
  return { identity: packageIdentity, aggregateSha256: hash(resolve(root, "checksums.sha256")), evidence: Object.fromEntries(evidenceNames.map(name => [name, hash(resolve(root, name))])) };
}
export function verifyStagingPackage(packageDir, expected = {}) {
  const root = resolve(packageDir);
  if (!statSync(root).isDirectory()) fail("package directory is missing");
  const entries = checksums(root);
  const expectedFiles = entries.map(([path]) => path).concat("checksums.sha256", "package.sha256").sort();
  if (JSON.stringify(files(root)) !== JSON.stringify(expectedFiles)) fail("package file allowlist differs from checksums");
  for (const [path, value] of entries) if (hash(resolve(root, path)) !== value) fail(`packaged file hash differs for ${path}`);
  if (readFileSync(resolve(root, "package.sha256"), "utf8") !== `${hash(resolve(root, "checksums.sha256"))}\n`) fail("package aggregate checksum differs");
  const packageIdentity = identity(root);
  if (packageIdentity?.schemaVersion !== 1 || packageIdentity.product !== "hexana-webapp" || !revision.test(packageIdentity.version) || packageIdentity.versionSource !== "git-revision" || !revision.test(packageIdentity.revision) || packageIdentity.version !== packageIdentity.revision) fail("package identity is not a SHA-only staging identity");
  if (!revision.test(expected.revision ?? "") || packageIdentity.revision !== expected.revision) fail("staging package revision differs from the expected full revision");
  return { identity: packageIdentity, aggregateSha256: hash(resolve(root, "checksums.sha256")), evidence: Object.fromEntries(evidenceNames.map(name => [name, hash(resolve(root, name))])) };
}
function validateImage(image) {
  if (!image || image.reference !== `${repository}@${image.manifestDigest}` || !digest.test(image.manifestDigest) || !digest.test(image.configDigest)) fail("image must use the canonical digest-form reference");
  const matchedMediaTypes = imageMediaTypePairs.some(([manifest, config]) => image.manifestMediaType === manifest && image.configMediaType === config);
  if (!matchedMediaTypes || image.platform?.os !== "linux" || image.platform?.architecture !== "amd64") fail("image must identify one linux/amd64 OCI or Docker schema-2 manifest and matching config");
}
export function verifyReceipt(receipt, packageDir) {
  if (receipt?.schemaVersion !== 1 || receipt.kind !== "hexana-webapp-oci-evidence" || !branch.test(receipt.releaseBranch ?? "") || !revision.test(receipt.sourceRevision ?? "")) fail("unsupported receipt schema");
  validateImage(receipt.image);
  const actual = verifyPackage(packageDir, { releaseBranch: receipt.releaseBranch });
  const claimed = receipt.package?.identity;
  if (!["schemaVersion", "product", "version", "versionSource", "revision"].every(key => claimed?.[key] === actual.identity[key]) || receipt.sourceRevision !== actual.identity.revision || receipt.package.aggregateSha256 !== actual.aggregateSha256) fail("receipt package identity or aggregate differs from the static payload");
  if (Object.keys(receipt.package.evidence ?? {}).sort().join() !== evidenceNames.join() || evidenceNames.some(name => receipt.package.evidence[name] !== actual.evidence[name])) fail("receipt evidence hashes differ from the static payload");
}
function checkedInVersion(file) {
  const match = /^webappVersion=(.+)$/m.exec(readFileSync(file, "utf8"));
  if (!match || !version.test(match[1])) fail("checked-in webappVersion is not semantic");
  return match[1];
}
function main() {
  const [command, ...args] = process.argv.slice(2);
  const input = options(args);
  const packageDir = input.get("--package-dir");
  if (!packageDir) fail("--package-dir is required");
  if (command === "verify-package") {
    const releaseBranch = input.get("--release-branch");
    const sourceRevision = input.get("--revision");
    const versionFile = input.get("--version-file");
    if (!releaseBranch || !revision.test(sourceRevision ?? "") || !versionFile || input.size !== 4) fail("verify-package requires package-dir, release-branch, revision, and version-file");
    verifyPackage(packageDir, { releaseBranch, revision: sourceRevision, version: checkedInVersion(versionFile) });
  } else if (command === "verify-staging-package") {
    const sourceRevision = input.get("--revision");
    if (!revision.test(sourceRevision ?? "") || input.size !== 2) fail("verify-staging-package requires package-dir and revision");
    verifyStagingPackage(packageDir, { revision: sourceRevision });
  } else if (command === "verify-receipt") {
    const receipt = input.get("--receipt");
    if (!receipt || input.size !== 2) fail("verify-receipt requires package-dir and receipt");
    try { verifyReceipt(JSON.parse(readFileSync(receipt, "utf8")), packageDir); } catch (error) { if (error instanceof SyntaxError) fail("receipt is not JSON"); throw error; }
  } else fail("expected verify-package or verify-receipt");
}
if (process.argv[1] === new URL(import.meta.url).pathname) main();
