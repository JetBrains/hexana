import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import test from "node:test";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const directory = import.meta.dirname;
const readme = await readFile(resolve(directory, "README.md"), "utf8");
const skaffold = await readFile(resolve(directory, "../skaffold.yaml"), "utf8");
const publishedHelper = "registry.jetbrains.team/p/hx/containers/hexana-webapp-build-environment@sha256:c9637fa0bb073a5009cba86bba38b564756ded1e75157251f76216292d68582c";

test("keeps this directory consumer-only", () => {
    assert.equal(existsSync(resolve(directory, "Dockerfile")), false);
    assert.match(readme, /TeamCity configuration\s+repository/i);
    assert.match(readme, /contains only the consumer contract/i);
    assert.match(readme, /pinned by immutable digest/i);
    assert.match(readme, /does not update that reference automatically/i);
});

test("documents the blank Console environment boundary", () => {
    assert.match(readme, /blank Build Environment/i);
    assert.match(readme, /default SRE agent provides the host `docker` CLI/i);
    assert.match(readme, /Helm, registry login, and deployment\s+remain outside/i);
});

test("staging hook invokes only the published digest-pinned helper", () => {
    assert.match(skaffold, /command: \["sh", "-ec", "source_root=/);
    assert.match(skaffold, /revision=\\"\$\(git -C \\"\$\{source_root\}\\" rev-parse --verify HEAD\^\{commit\}\)\\"/);
    assert.match(skaffold, /printf '%s\\\\n' \\"\$\{revision\}\\" \| grep -Eq '\^\[0-9a-f\]\{40\}\$'/);
    assert.match(skaffold, /exec docker run --rm/);
    assert.match(skaffold, /--user \\"\$\(id -u\):\$\(id -g\)\\"/);
    assert.match(skaffold, /--mount \\"type=bind,src=\$\{source_root\},dst=\/workspace\\"/);
    assert.match(skaffold, /--workdir \/workspace/);
    assert.match(skaffold, /--env \\"HEXANA_SOURCE_REVISION=\$\{revision\}\\"/);
    assert.doesNotMatch(skaffold, /HEXANA_SOURCE_REVISION=\$\{HEXANA_SOURCE_REVISION:-\}/);
    assert.match(skaffold, /--env \\"GRADLE_USER_HOME=\/tmp\/hexana-webapp-staging-gradle\\"/);
    assert.match(skaffold, new RegExp(publishedHelper.replaceAll(".", "\\.")));
    const helperReferences = [...skaffold.matchAll(/registry\.jetbrains\.team\/p\/hx\/containers\/hexana-webapp-build-environment(?:@sha256:[0-9a-f]+|:[^\s"]+)?/g)].map(match => match[0]);
    assert.deepEqual(helperReferences, [publishedHelper]);
    assert.match(skaffold, /node webapp\/stage-cloud-console-staging-package\.mjs/);
    assert.doesNotMatch(skaffold, /docker\.sock|--privileged|--network(?:=|\s+)host|insecure|tls-verify=false/i);
});

test("staging hands the full Git SHA tag to Docker without changing the base production contract", () => {
    const staging = skaffold.slice(skaffold.indexOf("  - name: staging"), skaffold.indexOf("  - name: production"));
    const production = skaffold.slice(skaffold.indexOf("  - name: production"));

    assert.match(skaffold, /^  tagPolicy:\n    sha256: \{\}$/m);
    assert.match(skaffold, /^          HEXANA_SOURCE_REVISION: "\{\{\.HEXANA_SOURCE_REVISION\}\}"$/m);
    assert.match(staging, /op: replace\n        path: \/build\/tagPolicy\n        value:\n          gitCommit:\n            variant: CommitSha/);
    assert.match(staging, /op: replace\n        path: \/build\/artifacts\/0\/docker\/buildArgs\/HEXANA_SOURCE_REVISION\n        value: "\{\{\.IMAGE_TAG\}\}"/);
    assert.doesNotMatch(staging, /value: "\{\{\.HEXANA_SOURCE_REVISION\}\}"/);
    assert.doesNotMatch(staging, /\{\{\s*(?:cmd|shell)\b/);
    assert.doesNotMatch(production, /tagPolicy|HEXANA_SOURCE_REVISION|HEXANA_WEBAPP_RELEASE_BRANCH/);
});

function stagingHook() {
    const command = skaffold.split("\n").find(line => line.includes("stage-cloud-console-staging-package.mjs"));
    return JSON.parse(command.trim().replace(/^- command: /, ""));
}

function withDockerStub(action) {
    const root = mkdtempSync(join(tmpdir(), "hexana-webapp-docker-stub-"));
    const docker = join(root, "docker");
    const received = join(root, "received");
    writeFileSync(docker, `#!/bin/sh\nprintf '%s\\n' "$@" > "${received}"\n`);
    chmodSync(docker, 0o755);
    try {
        action({ root, received });
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

test("derives the staging revision from the checkout instead of an inherited environment value", () => withDockerStub(({ root, received }) => {
    const [shell, flags, command] = stagingHook();
    const checkout = resolve(directory, "../..");
    const expected = execFileSync("git", ["rev-parse", "--verify", "HEAD^{commit}"], { cwd: checkout, encoding: "utf8" }).trim();
    execFileSync(shell, [flags, command], {
        cwd: checkout,
        env: { ...process.env, PATH: `${root}:${process.env.PATH}`, HEXANA_SOURCE_REVISION: "malformed-operator-override" },
    });
    assert.match(readFileSync(received, "utf8"), new RegExp(`^HEXANA_SOURCE_REVISION=${expected}$`, "m"));
}));

test("fails before Docker staging when the controller is outside a Git checkout", () => withDockerStub(({ root, received }) => {
    const [shell, flags, command] = stagingHook();
    assert.throws(() => execFileSync(shell, [flags, command], {
        cwd: root,
        env: { ...process.env, PATH: `${root}:${process.env.PATH}` },
        stdio: "pipe",
    }));
    assert.throws(() => readFileSync(received, "utf8"));
}));
