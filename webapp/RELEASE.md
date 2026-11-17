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
`values_staging.yaml`. `deploy-webapp-master.mjs` is the source-owned bridge: it
accepts only matching schema-v2 master receipt/scan evidence, preflights the
configured generated Cloud Console target, queues exactly `refs/heads/master` at the
approved full revision, and verifies each endpoint in `HEXANA_WEBAPP_MASTER_ENDPOINTS`
before recording deployment evidence. The TeamCity nightly chain that supplies
the bridge inputs and token is defined in the companion
CI configuration; it becomes operational only after
that DSL is applied and live-validated in TeamCity.

The deployment target and policy come from environment variables that the TeamCity
configuration supplies. The bridge exits before any request, naming the variable,
when one is missing or malformed:

- `HEXANA_CLOUD_CONSOLE_SERVER_URL`, `HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID`,
  `HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID`, `HEXANA_CLOUD_CONSOLE_VCS_URL`
- `HEXANA_K8S_NAMESPACE`, `HEXANA_IMAGE_PULL_SECRET`, `HEXANA_GCP_PROJECT`,
  `HEXANA_GCP_CLUSTER`, `HEXANA_GCP_REGION`
- `HEXANA_REGISTRY_HOST`, `HEXANA_REGISTRY_LOGIN_PARAM`,
  `HEXANA_REGISTRY_TOKEN_PARAM` (parameter names without `%`),
  `HEXANA_WEBAPP_IMAGE_REPOSITORY`
- `HEXANA_WEBAPP_MASTER_ENDPOINTS` for master and `HEXANA_WEBAPP_PRODUCTION_ENDPOINTS`
  for production: comma-separated canonical https origins with a trailing slash

```bash
TEAMCITY_SERVER_URL=https://teamcity.example.invalid \
HEXANA_WEBAPP_SOURCE_BRANCH=master \
HEXANA_SOURCE_REVISION=0123456789abcdef0123456789abcdef01234567 \
HEXANA_CLOUD_CONSOLE_TOKEN=masked \
node webapp/deploy-webapp-master.mjs --receipt /path/receipt.json --scan-summary /path/summary.json --output-dir webapp/build/master-deployment
```

## Live deployment smoke

Run the source-owned smoke from the repository root after deployment. Master uses
its full revision as both version and revision; production uses the release's
strict semantic version. The image digest is required deployment evidence but is
not inferred from HTTP headers.
The endpoints come from `HEXANA_WEBAPP_MASTER_ENDPOINTS` (one origin) or
`HEXANA_WEBAPP_PRODUCTION_ENDPOINTS` (two origins); the smoke fails without them.

```bash
HEXANA_WEBAPP_SMOKE_TARGET=production \
HEXANA_WEBAPP_PRODUCTION_ENDPOINTS=https://private.example.invalid/,https://public.example.invalid/ \
HEXANA_WEBAPP_EXPECTED_VERSION=0.1.0 \
HEXANA_WEBAPP_EXPECTED_REVISION=0123456789abcdef0123456789abcdef01234567 \
HEXANA_WEBAPP_EXPECTED_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
node webapp/live-webapp-smoke.mjs
```

The smoke writes `summary.json`, `junit.xml`, and per-host HTTP/browser evidence
under `webapp/build/live-smoke`. Production checks both approved hosts and requires
their release identities to match.

## Vulnerability scan

The source-owned OCI scan requires Bash, Node.js, a running Docker daemon,
registry credentials for the Hexana image, and the repository's
Gradle/JDK toolchain. The pinned Trivy image is anonymously readable. Prepare
these fixed inputs first:

- `webapp/build/oci-release-scan-input/evidence/receipt.json`
- `webapp/build/oci-release-scan-input/static-package/`, including `sbom.cdx.json` and `THIRD-PARTY-NOTICES.md`

From any working directory, invoke the checked-in script with the exact release
branch and lowercase full source revision:

```bash
HEXANA_WEBAPP_RELEASE_BRANCH=release/webapp/0.1 \
HEXANA_SOURCE_REVISION=0123456789abcdef0123456789abcdef01234567 \
/path/to/Hexana/webapp/scan-webapp-oci-release.sh
```

For a SHA-only master image, set the generic source branch instead. The release
and master branch variables are mutually exclusive:

```bash
HEXANA_WEBAPP_SOURCE_BRANCH=master \
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

The release deployment bridge queues the configured Cloud Console deploy-only build
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

- each origin in `HEXANA_WEBAPP_PRODUCTION_ENDPOINTS`

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
