import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

const revision = process.env.HEXANA_SOURCE_REVISION;
const root = resolve(import.meta.dirname, "..");
const fullRevision = /^[0-9a-f]{40}$/;

function fail(message) {
  throw new Error(`Cloud Console staging gate: ${message}`);
}

if (!fullRevision.test(revision ?? "")) {
  fail("HEXANA_SOURCE_REVISION must be a lowercase 40-character Git SHA");
}
const head = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
if (head !== revision) {
  fail("HEXANA_SOURCE_REVISION must match the checked-out Git revision");
}
const changes = execFileSync("git", ["status", "--porcelain", "--untracked-files=all"], { cwd: root, encoding: "utf8" });
if (changes) {
  fail("the checked-out source revision must have a clean working tree");
}
if (process.env.HEXANA_WEBAPP_RELEASE_BRANCH) {
  fail("staging must not set HEXANA_WEBAPP_RELEASE_BRANCH");
}
execFileSync("./gradlew", [":webapp:stageCloudConsoleStagingPackage", `-PhexanaWebappRevision=${revision}`], { cwd: root, stdio: "inherit" });
execFileSync("node", ["webapp/oci-evidence-contract.mjs", "verify-staging-package", "--package-dir", "webapp/oci-package/staging-package", "--revision", revision], { cwd: root, stdio: "inherit" });
