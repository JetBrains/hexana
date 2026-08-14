import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";

const script = new URL("./release-contract.mjs", import.meta.url).pathname;
const changelog = readFileSync(new URL("./CHANGELOG.md", import.meta.url), "utf8");

function run({ version = "0.1.0", branch, content = changelog }) {
  const directory = mkdtempSync(join(tmpdir(), "hexana-webapp-release-contract-"));
  const changelogPath = join(directory, "CHANGELOG.md");
  writeFileSync(changelogPath, content);
  const args = [script, "--version", version, "--changelog", changelogPath];
  if (branch) args.push("--release-branch", branch);
  return () => {
    try {
      return execFileSync("node", args, { encoding: "utf8", stdio: "pipe" });
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  };
}

test("accepts the checked-in 0.1 release contract", () => {
  assert.doesNotThrow(run({ branch: "release/webapp/0.1" }));
});

test("accepts patch releases from the same release branch", () => {
  const patch = changelog.replace("0.1.0", "0.1.7").replace("2026-08-14", "2026-08-15");
  assert.doesNotThrow(run({ version: "0.1.7", branch: "release/webapp/0.1", content: patch }));
});

test("rejects a mismatched version family", () => {
  assert.throws(run({ version: "0.2.0", branch: "release/webapp/0.1" }), /does not belong/);
});

test("rejects a missing release section", () => {
  assert.throws(run({ version: "0.1.1", branch: "release/webapp/0.1" }), /no dated section/);
});

test("rejects malformed and non-chronological dates", () => {
  assert.throws(run({ branch: "release/webapp/0.1", content: changelog.replace("2026-08-14", "2026-02-30") }), /not a calendar date/);
  const unordered = changelog.replace("## [0.1.0] - 2026-08-14", "## [0.1.0] - 2026-08-13\n\n## [0.0.1] - 2026-08-14");
  assert.throws(run({ branch: "release/webapp/0.1", content: unordered }), /newest-first/);
});

test("rejects non-empty Unreleased in release mode", () => {
  assert.throws(run({ branch: "release/webapp/0.1", content: changelog.replace("## [Unreleased]\n", "## [Unreleased]\n\n- Pending\n") }), /Unreleased must be empty/);
});
