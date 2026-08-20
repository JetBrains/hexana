# Webapp release contract

`gradle.properties` is the checked-in authority for the next Webapp semantic version.
Release packaging must receive `-PhexanaWebappReleaseBranch=release/webapp/<major>.<minor>`.
The branch family must match the version's major and minor components.

For a `0.1.x` patch release:

1. Update `webappVersion` in `gradle.properties`.
2. Move the intended release notes from `Unreleased` to a dated `## [0.1.x] - YYYY-MM-DD` section at the top of `CHANGELOG.md`.
3. Leave `Unreleased` empty before release packaging.
4. Run `./gradlew :webapp:testWebappReleaseContract :webapp:verifyWebappReleaseContract -PhexanaWebappReleaseBranch=release/webapp/0.1`.

Release packages record the checked-in semantic version and the full Git revision.
Builds without the release-branch property keep the revision as their package version.
`hexanaWebappVersion` is accepted only when it equals the checked-in authority.

## Live deployment smoke

Run the source-owned smoke from the repository root after deployment. Master uses
its full revision as both version and revision; production uses the release's
strict semantic version. The image digest is required deployment evidence but is
not inferred from HTTP headers.

```bash
HEXANA_WEBAPP_SMOKE_TARGET=production \
HEXANA_WEBAPP_EXPECTED_VERSION=0.1.0 \
HEXANA_WEBAPP_EXPECTED_REVISION=0123456789abcdef0123456789abcdef01234567 \
HEXANA_WEBAPP_EXPECTED_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
node webapp/live-webapp-smoke.mjs
```

The smoke writes `summary.json`, `junit.xml`, and per-host HTTP/browser evidence
under `webapp/build/live-smoke`. Production checks both approved hosts and requires
their release identities to match.

## Vulnerability scan

The source-owned OCI release scan requires Bash, Node.js, a running Docker daemon,
registry credentials for the Hexana release image, and the repository's
Gradle/JDK toolchain. The pinned Trivy image is anonymously readable. Prepare
these fixed inputs first:

- `webapp/build/oci-release-scan-input/evidence/receipt.json`
- `webapp/build/oci-release-scan-input/static-package/`, including `sbom.cdx.json`

From any working directory, invoke the checked-in script with the exact release
branch and lowercase full source revision:

```bash
HEXANA_WEBAPP_RELEASE_BRANCH=release/webapp/0.1 \
HEXANA_SOURCE_REVISION=0123456789abcdef0123456789abcdef01234567 \
/path/to/Hexana/webapp/scan-webapp-oci-release.sh
```

The script accepts no arguments or scanner override. It deletes and recreates
`webapp/build/vulnerability-scan-work` and
`webapp/build/vulnerability-scan-evidence`, downloads the vulnerability database
before scanning, then runs the image and SBOM scans without network access. Its
exit codes are `0` for a validated clean result, `1` for validated HIGH or
CRITICAL findings, and `2` for an operational or evidence-contract failure. It
always removes the saved image archive and scanner cache. Validated reports,
scanner metadata, `summary.json`, and `summary.txt` remain in
`webapp/build/vulnerability-scan-evidence`.

In TeamCity, the **Scan Webapp OCI Release** chain supplies both environment
values from its checkout and the fixed input artifacts from **Build Webapp OCI
Release**. TeamCity publishes the resulting evidence directory.
