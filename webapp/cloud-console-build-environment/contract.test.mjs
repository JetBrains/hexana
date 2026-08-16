import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const directory = import.meta.dirname;
const dockerfile = await readFile(resolve(directory, "Dockerfile"), "utf8");
const readme = await readFile(resolve(directory, "README.md"), "utf8");

const requiredSources = [
    "eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8",
    "node:22.14.0-bookworm-slim@sha256:1c18d9ab3af4585870b92e4dbc5cac5a0dc77dd13df1a5905cea89fc720eb05b",
];

test("pins the maintained Node 22 and JDK 21 image sources", () => {
    for (const source of requiredSources) assert.match(dockerfile, new RegExp(`^FROM ${source.replaceAll(".", "\\.")}\\b`, "m"));
    assert.equal((dockerfile.match(/^FROM /gm) ?? []).length, requiredSources.length);
});

test("declares and validates the staging hook tools", () => {
    assert.match(dockerfile, /apt-get install --no-install-recommends --yes bash findutils git/);
    assert.match(dockerfile, /COPY --from=jdk \/opt\/java\/openjdk \/opt\/java\/openjdk/);
    assert.match(dockerfile, /ENV JAVA_HOME=\/opt\/java\/openjdk/);
    for (const command of ["node", "java", "javac", "bash", "git", "xargs"]) {
        assert.match(dockerfile, new RegExp(`&& ${command} --version|RUN ${command} --version`));
    }
});

test("uses a non-root TeamCity-compatible runtime user", () => {
    const users = [...dockerfile.matchAll(/^USER (.+)$/gm)].map(match => match[1]);
    assert.deepEqual(users, ["node"]);
    assert.match(dockerfile, /^WORKDIR \/home\/node$/m);
});

test("contains no deployment identity or credential configuration", () => {
    assert.doesNotMatch(dockerfile, /credential|secret|token|password|registry/i);
});

test("documents the external publication and Console configuration boundary", () => {
    assert.match(readme, /immutable\s+image digest/i);
    assert.match(readme, /Cloud Console \*\*Build environment\s+\(container image\)\*\*/);
});
