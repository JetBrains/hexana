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

## Master deployment profile

Cloud Console must select `deploy_only_master` when deploying a SHA-pinned
master image as `hexana-webapp-master`. The profile removes Skaffold's source
build, consumes only the supplied `HEXANA_WEBAPP_IMAGE`, and uses
`values_staging.yaml`. It does not itself verify or deliver the image; a master
deployment bridge and TeamCity chain must provide that gate before rollout.

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

## Production deployment readiness

The release deployment bridge queues the pinned Cloud Console deploy-only build
only after validating the Build receipt, passing Scan summary, generated build
configuration, VCS root, branch, revision, and immutable image digest. Its build
deadline starts after Cloud Console returns a valid build ID. The default and
maximum build deadline is one hour (`HEXANA_DEPLOY_TIMEOUT_MS=3600000`). A
timeout does not cancel the remote build; it may continue at the recorded build
URL.

Production readiness begins only after that exact build finishes with
`SUCCESS`. It has a separate five-minute default and maximum deadline
(`HEXANA_READINESS_TIMEOUT_MS=300000`). Every retry round checks the approved
release endpoint:

- `https://hexana-webapp.example.invalid/`

`GET /healthz` must return exactly `204`, an empty body,
`Cache-Control: no-store`, and no redirect. Then a cache-busted `GET /` must
return exactly `200` without redirect and embed `hexana-release-info` with
schema version `1`, product `hexana-webapp`, and the exact approved semantic
version and full revision. Network failures, temporary status failures, and an
old release identity are retried within the single readiness deadline. The
endpoint must pass the health and identity probes within one attempt. Health
streaming is canceled on its first byte; root bodies are capped at 1 MiB by
both `Content-Length` and streamed-byte checks, and all body reads remain under
the shared deadline.

`HEXANA_DEPLOY_POLL_INTERVAL_MS` defaults to `5000`, accepts `100..60000`, and
must be shorter than both deadlines. Each deadline accepts a minimum of `1000`;
the build and readiness maxima are `3600000` and `300000` respectively.

Success and every caught failure atomically replace
`webapp/build/release-deployment/deployment-evidence.json`. The evidence records
the source and Cloud Console build identities, deadlines, poll observations,
per-host readiness, and a concise failure when applicable. The immutable image
digest remains evidence from the approved build and scan contracts; HTTP does
not prove it, so deployment evidence always records `digestHttpProven: false`.
If a queue POST may have created a build that was not conclusively observed
finished, failure evidence records `buildMayContinue: true`; no timeout or
ambiguous response is reported as cancellation.
