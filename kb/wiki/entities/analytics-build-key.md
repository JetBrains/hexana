# Analytics build key

How the PostHog project key reaches shipped artifacts without being in the source.

- `analytics/api` generates `AnalyticsBuildConfig.API_KEY` (a `commonMain` source, task
  `generateAnalyticsBuildConfig`) from Gradle property `hexana.posthog.apiKey`, falling back to env
  `HEXANA_POSTHOG_BUILD_API_KEY`. The key is a cacheable task input and is never logged; values outside
  `[A-Za-z0-9_-]` fail the build.
- A blank key means analytics is off. `HexanaAnalytics.initialize`, `createBrowserAnalyticsClient` and the CLI telemetry
  thread create no client and send nothing. The runtime override `HEXANA_POSTHOG_KEY` (MCP) still wins.
- The release guard is opt-in: `hexana.release=true` / `HEXANA_RELEASE_BUILD=true` makes
  `:analytics:api:verifyAnalyticsReleaseKey` fail a build without a key. Every shipped artifact compiles `analytics:api`, so
  the guard sits on the generator rather than on each packaging task; `publishPlugin` depends on it explicitly because it
  runs with `-x buildPlugin`. Test lanes, PR checks and forks set neither variable.
- TeamCity wiring lives in the internal CI configuration (`common/AnalyticsBuildParams.kt`). `Build Idea Plugin` and
  `Build VSCode Plugin` default to a fake key because UI tests assert events on a fake PostHog server.
- Tests pass an explicit `AnalyticsConfig(apiKey = "test-key")`; the default key depends on the build.
- Never rotate the key: that revokes it in every shipped build. Removing the old literal from git history is a separate task.
