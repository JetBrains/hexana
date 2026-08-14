import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { verifyReceipt, repository } from "./oci-evidence-contract.mjs";

const sha = text => createHash("sha256").update(text).digest("hex");
function fixture() {
  const root = mkdtempSync(join(tmpdir(), "hexana-webapp-oci-"));
  const values = {
    "build-identity.json": '{"schemaVersion":1,"product":"hexana-webapp","version":"0.1.0","versionSource":"webapp/gradle.properties","revision":"0123456789abcdef0123456789abcdef01234567"}\n',
    "package-manifest.json": "{}\n", "provenance.json": "{}\n", "release-info.json": "{}\n", "resolved-dependencies.json": "{}\n", "sbom.cdx.json": "{}\n", "index.html": "static payload\n",
  };
  for (const [name, value] of Object.entries(values)) writeFileSync(join(root, name), value);
  const checksums = Object.entries(values).map(([name, value]) => `${sha(value)}  ${name}`).sort().join("\n") + "\n";
  writeFileSync(join(root, "checksums.sha256"), checksums);
  writeFileSync(join(root, "package.sha256"), `${sha(checksums)}\n`);
  const evidence = Object.fromEntries(["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"].map(name => [name, sha(readFileSync(join(root, name)))]));
  const manifestDigest = `sha256:${"a".repeat(64)}`;
  return {
    root,
    receipt: { schemaVersion: 1, kind: "hexana-webapp-oci-evidence", releaseBranch: "release/webapp/0.1", sourceRevision: "0123456789abcdef0123456789abcdef01234567", image: { reference: `${repository}@${manifestDigest}`, manifestDigest, configDigest: `sha256:${"b".repeat(64)}`, manifestMediaType: "application/vnd.oci.image.manifest.v1+json", configMediaType: "application/vnd.oci.image.config.v1+json", platform: { os: "linux", architecture: "amd64" } }, package: { identity: JSON.parse(values["build-identity.json"]), aggregateSha256: sha(checksums), evidence } },
  };
}
function withFixture(action) { const value = fixture(); try { action(value); } finally { rmSync(value.root, { recursive: true, force: true }); } }

test("accepts a detached receipt for an extracted static image root", () => withFixture(({ root, receipt }) => assert.doesNotThrow(() => verifyReceipt(receipt, root))));
test("rejects mutation of a packaged file", () => withFixture(({ root, receipt }) => { writeFileSync(join(root, "index.html"), "changed\n"); assert.throws(() => verifyReceipt(receipt, root), /packaged file hash differs/); }));
test("rejects a substituted manifest digest", () => withFixture(({ root, receipt }) => { receipt.image.reference = `${repository}@sha256:${"c".repeat(64)}`; assert.throws(() => verifyReceipt(receipt, root), /digest-form reference/); }));
