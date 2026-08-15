import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { verifyPackage, verifyReceipt, verifyStagingPackage, repository } from "./oci-evidence-contract.mjs";

const sha = text => createHash("sha256").update(text).digest("hex");
const revision = "0123456789abcdef0123456789abcdef01234567";
const stageScript = new URL("./stage-cloud-console-staging-package.mjs", import.meta.url).pathname;

function fixture(identity) {
  const root = mkdtempSync(join(tmpdir(), "hexana-webapp-staging-"));
  const values = {
    "build-identity.json": `${JSON.stringify(identity)}\n`,
    "package-manifest.json": "{}\n", "provenance.json": "{}\n", "release-info.json": "{}\n", "resolved-dependencies.json": "{}\n", "sbom.cdx.json": "{}\n", "index.html": "static payload\n",
  };
  for (const [name, value] of Object.entries(values)) writeFileSync(join(root, name), value);
  const checksums = Object.entries(values).map(([name, value]) => `${sha(value)}  ${name}`).sort().join("\n") + "\n";
  writeFileSync(join(root, "checksums.sha256"), checksums);
  writeFileSync(join(root, "package.sha256"), `${sha(checksums)}\n`);
  return { root, checksums };
}

function withFixture(identity, action) {
  const value = fixture(identity);
  try { action(value); } finally { rmSync(value.root, { recursive: true, force: true }); }
}

test("accepts SHA identity only in staging verification", () => withFixture({ schemaVersion: 1, product: "hexana-webapp", version: revision, versionSource: "git-revision", revision }, ({ root }) => {
  assert.doesNotThrow(() => verifyStagingPackage(root, { revision }));
  assert.throws(() => verifyPackage(root, { releaseBranch: "release/webapp/0.1", revision, version: "0.1.0" }), /semantic release identity/);
  const receipt = { schemaVersion: 1, kind: "hexana-webapp-oci-evidence", releaseBranch: "release/webapp/0.1", sourceRevision: revision, image: { reference: `${repository}@sha256:${"a".repeat(64)}`, manifestDigest: `sha256:${"a".repeat(64)}`, configDigest: `sha256:${"b".repeat(64)}`, manifestMediaType: "application/vnd.oci.image.manifest.v1+json", configMediaType: "application/vnd.oci.image.config.v1+json", platform: { os: "linux", architecture: "amd64" } }, package: { identity: { schemaVersion: 1, product: "hexana-webapp", version: revision, versionSource: "git-revision", revision }, aggregateSha256: sha(readFileSync(join(root, "checksums.sha256"))), evidence: {} } };
  assert.throws(() => verifyReceipt(receipt, root), /semantic release identity/);
}));

test("rejects semantic release identity in staging verification", () => withFixture({ schemaVersion: 1, product: "hexana-webapp", version: "0.1.0", versionSource: "webapp/gradle.properties", revision }, ({ root }) => {
  assert.throws(() => verifyStagingPackage(root, { revision }), /SHA-only staging identity/);
}));

function runGate(environment) {
  const env = { ...process.env, ...environment };
  if (environment.HEXANA_SOURCE_REVISION === undefined) delete env.HEXANA_SOURCE_REVISION;
  if (environment.HEXANA_WEBAPP_RELEASE_BRANCH === undefined) delete env.HEXANA_WEBAPP_RELEASE_BRANCH;
  return () => {
    try {
      execFileSync("node", [stageScript], { env, encoding: "utf8", stdio: "pipe" });
    } catch (error) {
      throw new Error(error.stderr);
    }
  };
}

test("pre-build gate rejects missing source revision before Gradle", () => {
  assert.throws(runGate({ HEXANA_SOURCE_REVISION: undefined, HEXANA_WEBAPP_RELEASE_BRANCH: undefined }), /HEXANA_SOURCE_REVISION must be a lowercase 40-character Git SHA/);
});

test("pre-build gate rejects malformed source revision before Gradle", () => {
  assert.throws(runGate({ HEXANA_SOURCE_REVISION: "not-a-revision", HEXANA_WEBAPP_RELEASE_BRANCH: undefined }), /HEXANA_SOURCE_REVISION must be a lowercase 40-character Git SHA/);
});
