import { readFileSync } from "node:fs";

const semanticVersionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const releaseBranchPattern = /^release\/webapp\/(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const releaseHeadingPattern = /^## \[([^\]]+)] - (\d{4}-\d{2}-\d{2})$/;

function fail(message) {
  throw new Error(`Invalid webapp release contract: ${message}`);
}

function parseArguments(argumentsList) {
  const values = new Map();
  for (let index = 0; index < argumentsList.length; index += 2) {
    const option = argumentsList[index];
    const value = argumentsList[index + 1];
    if (!option?.startsWith("--") || value === undefined || values.has(option)) {
      fail("expected each command-line option exactly once with a value");
    }
    values.set(option, value);
  }
  const version = values.get("--version");
  const changelog = values.get("--changelog");
  if (!version || !changelog) fail("--version and --changelog are required");
  if (values.size !== 2 && values.size !== 3) fail("unexpected command-line option");
  return { version, changelog, releaseBranch: values.get("--release-branch") };
}

function validateDate(value) {
  const parsed = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== value) {
    fail(`release date ${value} is not a calendar date in YYYY-MM-DD format`);
  }
}

function parseChangelog(changelog) {
  const lines = changelog.replace(/\r\n/g, "\n").split("\n");
  if (lines[0] !== "# Changelog") fail("CHANGELOG.md must start with # Changelog");
  const headings = [];
  let unreleased = undefined;
  let current = undefined;
  lines.forEach((line, index) => {
    if (line === "## [Unreleased]") {
      if (unreleased !== undefined) fail("CHANGELOG.md has more than one Unreleased section");
      unreleased = { line: index + 1, content: [] };
      current = unreleased;
      return;
    }
    const match = releaseHeadingPattern.exec(line);
    if (match) {
      const [, version, date] = match;
      if (!semanticVersionPattern.test(version)) fail(`release heading has a non-semantic version ${version}`);
      validateDate(date);
      if (headings.some((heading) => heading.version === version)) fail(`duplicate release section ${version}`);
      const heading = { version, date, line: index + 1 };
      headings.push(heading);
      current = undefined;
      return;
    }
    if (unreleased && current === unreleased && line.trim() !== "") unreleased.content.push(line);
  });
  if (!unreleased) fail("CHANGELOG.md must contain ## [Unreleased]");
  if (headings.length === 0) fail("CHANGELOG.md must contain at least one dated release section");
  if (unreleased.line > headings[0].line) fail("Unreleased must precede all release sections");
  for (let index = 1; index < headings.length; index += 1) {
    if (headings[index - 1].date < headings[index].date) {
      fail("release sections must be ordered newest-first by date");
    }
  }
  return { headings, unreleased };
}

function validateReleaseContract({ version, changelog, releaseBranch }) {
  if (!semanticVersionPattern.test(version)) fail(`checked-in version ${version} is not semantic versioning`);
  const parsedChangelog = parseChangelog(changelog);
  if (!releaseBranch) return;
  const branchMatch = releaseBranchPattern.exec(releaseBranch);
  if (!branchMatch) fail(`release branch must be release/webapp/<major>.<minor>, got ${releaseBranch}`);
  const [, major, minor] = branchMatch;
  if (!version.startsWith(`${major}.${minor}.`)) {
    fail(`version ${version} does not belong to release branch ${releaseBranch}`);
  }
  const release = parsedChangelog.headings.find((heading) => heading.version === version);
  if (!release) fail(`CHANGELOG.md has no dated section for ${version}`);
  if (parsedChangelog.unreleased.content.length !== 0) {
    fail("Unreleased must be empty while packaging a release");
  }
}

const options = parseArguments(process.argv.slice(2));
validateReleaseContract({
  version: options.version,
  changelog: readFileSync(options.changelog, "utf8"),
  releaseBranch: options.releaseBranch,
});
