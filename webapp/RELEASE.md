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
