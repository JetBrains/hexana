# CI environment parameters (internal targets)

No script or source file in this repository may hardcode an internal CI build ID, internal
host, cloud/Kubernetes target, storage bucket or secret parameter name. Each script reads
its targets from `HEXANA_*` environment variables, which the private CI configuration sets
on every build that runs the script. A missing variable makes the script exit before any
network or storage call, with a message naming the variable.

| Script | Variables | Behaviour when missing |
| --- | --- | --- |
| `ui-driver-tests` (`HexanaTeamCityArtifacts`) | `HEXANA_IDE_BUILD_TYPE`, `HEXANA_PLUGIN_BUILD_TYPE` | read only on the TeamCity download path; the test fails naming the variable |
| `mcp/packaging/publish-downloads-release-bundle.sh`, `cli/packaging/publish-cli-downloads-release-bundle.sh` | `HEXANA_DOWNLOADS_S3_BUCKET` | required when `PUBLISH_DOWNLOADS=true`; exits before generating or uploading |
| `mcp/packaging/release-codex-claude-plugins.sh` | `HEXANA_LEAK_GUARD_INTERNAL_NAMES` (regex alternation) | required for release runs; optional for the validation-only `RELEASE_ARTIFACT_SECURITY_CHECK_DIR` mode, where only the built-in public patterns apply |
| `webapp/deploy-webapp-release.mjs`, `webapp/deploy-webapp-master.mjs` | `HEXANA_CLOUD_CONSOLE_{SERVER_URL,BUILD_TYPE_ID,VCS_ROOT_ID,VCS_URL}`, `HEXANA_K8S_NAMESPACE`, `HEXANA_IMAGE_PULL_SECRET`, `HEXANA_GCP_{PROJECT,CLUSTER,REGION}`, `HEXANA_REGISTRY_{HOST,LOGIN_PARAM,TOKEN_PARAM}`, `HEXANA_WEBAPP_IMAGE_REPOSITORY`, `HEXANA_WEBAPP_{PRODUCTION,MASTER}_ENDPOINTS` | the deployment policy is built from them at start; exits before any request or output directory |
| `webapp/live-webapp-smoke.mjs` | `HEXANA_WEBAPP_MASTER_ENDPOINTS` (1 origin), `HEXANA_WEBAPP_PRODUCTION_ENDPOINTS` (2 origins) | only the selected target's variable is read |
| `webapp/scan-webapp-oci-release.sh` | `HEXANA_WEBAPP_IMAGE_REPOSITORY` | exits 2 before touching evidence |

Notes:

- `HEXANA_REGISTRY_LOGIN_PARAM` and `HEXANA_REGISTRY_TOKEN_PARAM` are the *names* of the CI
  parameters used by the Cloud Console generated `helm registry login`, without `%`, so the
  CI server does not resolve them. The approved generated-script template is built from them.
- Tests and runbooks use `example.invalid` values. Test helpers: `webapp/deployment-test-environment.mjs`.
- The Space image repository is still pinned in `webapp/skaffold.yaml`, `webapp/oci-evidence-contract.mjs` and
  `webapp/oci-localhost-browser-smoke.mjs`; only the deploy and scan scripts read it from env.
- Pre-existing, unrelated: `node --test webapp/release-contract.test.mjs` fails on master (Unreleased changelog section).
