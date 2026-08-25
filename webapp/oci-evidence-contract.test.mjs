import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { verifyReceipt, repository } from "./oci-evidence-contract.mjs";

const sha = text => createHash("sha256").update(text).digest("hex");
function fixture(mode = "release") {
  const root = mkdtempSync(join(tmpdir(), "hexana-webapp-oci-"));
  const revision = "0123456789abcdef0123456789abcdef01234567";
  const identity = mode === "master"
    ? { schemaVersion: 1, product: "hexana-webapp", version: revision, versionSource: "git-revision", revision }
    : { schemaVersion: 1, product: "hexana-webapp", version: "0.1.0", versionSource: "webapp/gradle.properties", revision };
  const values = {
    "build-identity.json": `${JSON.stringify(identity)}\n`,
    "package-manifest.json": "{}\n", "provenance.json": "{}\n", "release-info.json": "{}\n", "resolved-dependencies.json": "{}\n", "sbom.cdx.json": "{}\n", "index.html": "static payload\n",
  };
  for (const [name, value] of Object.entries(values)) writeFileSync(join(root, name), value);
  const checksums = Object.entries(values).map(([name, value]) => `${sha(value)}  ${name}`).sort().join("\n") + "\n";
  writeFileSync(join(root, "checksums.sha256"), checksums);
  writeFileSync(join(root, "package.sha256"), `${sha(checksums)}\n`);
  const evidence = Object.fromEntries(["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"].map(name => [name, sha(readFileSync(join(root, name)))]));
  const manifestDigest = `sha256:${"a".repeat(64)}`;
  const image = { reference: `${repository}@${manifestDigest}`, manifestDigest, configDigest: `sha256:${"b".repeat(64)}`, manifestMediaType: "application/vnd.oci.image.manifest.v1+json", configMediaType: "application/vnd.oci.image.config.v1+json", platform: { os: "linux", architecture: "amd64" } };
  const packageEvidence = { identity, aggregateSha256: sha(checksums), evidence };
  const receipt = mode === "master"
    ? { schemaVersion: 2, kind: "hexana-webapp-oci-evidence", source: { branch: "master", revision }, image, package: packageEvidence }
    : { schemaVersion: 1, kind: "hexana-webapp-oci-evidence", releaseBranch: "release/webapp/0.1", sourceRevision: revision, image, package: packageEvidence };
  return {
    root,
    receipt,
  };
}
function withFixture(action, mode) { const value = fixture(mode); try { action(value); } finally { rmSync(value.root, { recursive: true, force: true }); } }

const ociManifest = "application/vnd.oci.image.manifest.v1+json";
const ociConfig = "application/vnd.oci.image.config.v1+json";
const dockerManifest = "application/vnd.docker.distribution.manifest.v2+json";
const dockerConfig = "application/vnd.docker.container.image.v1+json";
function setMediaTypes(receipt, manifestMediaType, configMediaType) { Object.assign(receipt.image, { manifestMediaType, configMediaType }); }

test("accepts an OCI manifest and config", () => withFixture(({ root, receipt }) => assert.doesNotThrow(() => verifyReceipt(receipt, root))));
test("accepts a Docker schema-2 manifest and container config", () => withFixture(({ root, receipt }) => { setMediaTypes(receipt, dockerManifest, dockerConfig); assert.doesNotThrow(() => verifyReceipt(receipt, root)); }));
test("rejects an OCI manifest with a Docker config", () => withFixture(({ root, receipt }) => { setMediaTypes(receipt, ociManifest, dockerConfig); assert.throws(() => verifyReceipt(receipt, root), /matching config/); }));
test("rejects a Docker manifest with an OCI config", () => withFixture(({ root, receipt }) => { setMediaTypes(receipt, dockerManifest, ociConfig); assert.throws(() => verifyReceipt(receipt, root), /matching config/); }));
test("rejects unknown image media types", () => withFixture(({ root, receipt }) => { setMediaTypes(receipt, "application/example-manifest", "application/example-config"); assert.throws(() => verifyReceipt(receipt, root), /matching config/); }));
test("rejects mutation of a packaged file", () => withFixture(({ root, receipt }) => { writeFileSync(join(root, "index.html"), "changed\n"); assert.throws(() => verifyReceipt(receipt, root), /packaged file hash differs/); }));
test("rejects a substituted manifest digest", () => withFixture(({ root, receipt }) => { receipt.image.reference = `${repository}@sha256:${"c".repeat(64)}`; assert.throws(() => verifyReceipt(receipt, root), /digest-form reference/); }));
test("accepts an exact schema-v2 master receipt and returns its generic source identity", () => withFixture(({ root, receipt }) => {
  assert.deepEqual(verifyReceipt(receipt, root), {
    receiptSchemaVersion: 2,
    branch: "master",
    revision: "0123456789abcdef0123456789abcdef01234567",
    version: "0123456789abcdef0123456789abcdef01234567",
  });
}, "master"));
test("retains the schema-v1 release source identity", () => withFixture(({ root, receipt }) => {
  assert.deepEqual(verifyReceipt(receipt, root), {
    receiptSchemaVersion: 1,
    branch: "release/webapp/0.1",
    revision: "0123456789abcdef0123456789abcdef01234567",
    version: "0.1.0",
  });
}));
test("rejects master receipts with the wrong branch or non-SHA identity", () => withFixture(({ root, receipt }) => {
  receipt.source.branch = "release/webapp/0.1";
  assert.throws(() => verifyReceipt(receipt, root), /must identify master/);
  receipt.source.branch = "master";
  receipt.package.identity.version = "0.1.0";
  assert.throws(() => verifyReceipt(receipt, root), /identity or aggregate/);
}, "master"));
test("rejects mixed schemas and release/master substitution", () => {
  withFixture(({ root, receipt }) => {
    receipt.schemaVersion = 2;
    assert.throws(() => verifyReceipt(receipt, root), /unsupported fields/);
  });
  withFixture(({ root, receipt }) => {
    receipt.source = { branch: "master", revision: receipt.sourceRevision };
    assert.throws(() => verifyReceipt(receipt, root), /unsupported receipt schema/);
  });
  withFixture(({ root, receipt }) => {
    receipt.schemaVersion = 1;
    assert.throws(() => verifyReceipt(receipt, root), /unsupported receipt schema/);
  }, "master");
});
test("rejects extra master source, package, image, platform, and identity fields", () => withFixture(({ root, receipt }) => {
  receipt.source.releaseBranch = "release/webapp/0.1";
  assert.throws(() => verifyReceipt(receipt, root), /source has unsupported fields/);
  delete receipt.source.releaseBranch;
  receipt.package.releaseBranch = "release/webapp/0.1";
  assert.throws(() => verifyReceipt(receipt, root), /package has unsupported fields/);
  delete receipt.package.releaseBranch;
  receipt.image.tag = "master";
  assert.throws(() => verifyReceipt(receipt, root), /image has unsupported fields/);
  delete receipt.image.tag;
  receipt.image.platform.variant = "v1";
  assert.throws(() => verifyReceipt(receipt, root), /platform has unsupported fields/);
  delete receipt.image.platform.variant;
  receipt.package.identity.releaseBranch = "release/webapp/0.1";
  assert.throws(() => verifyReceipt(receipt, root), /identity has unsupported fields/);
}, "master"));
test("rejects comma-key collisions in exact master shapes", () => withFixture(({ root, receipt }) => {
  receipt.source = { "branch,revision": `${receipt.source.branch}:${receipt.source.revision}` };
  assert.throws(() => verifyReceipt(receipt, root), /source has unsupported fields/);
}, "master"));
