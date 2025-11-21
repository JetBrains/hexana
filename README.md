# hx/Hexana



## Getting Started

Download links:

SSH clone URL: https://example.invalid/hexana/Hexana.git

HTTPS clone URL: https://example.invalid/hexana/Hexana.git



These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

## Prerequisites

What things you need to install the software and how to install them.

```
Examples
```

## Deployment

To publish an Idea plugin version A.B:

- change pluginVersion in `idea-plugin/gradle.properties` to A.B

- run `./gradlew :idea-plugin:patchChangeLog`

  This will generate a section for version A.B.0 in
  idea-plugin/CHANGELOG.md and create an empty section for the Unreleased
  version.

- (optional) run `./gradlew :idea-plugin:buildPlugin`

  You can install the built plugin from disk to check that change-notes look as expected.

- commit and push changes in `idea-plugin/gradle.properties` and `idea-plugin/CHANGELOG.md`

- run the Publish Idea Plugin [1] build on TC

To publish a nightly version of Idea plugin, run the Publish Idea Plugin Nightly [2] build.

Or run locally: `./gradlew -PhexanaBuild=nightly -PhexanaNightlyBuildNumber=<nigthlyBuildNumber> -PpublishToken=<yourToken> :idea-plugin:publishPlugin`.
This will pulblish version A.B.<nigthlyBuildNumber> to the nightly channel.

[1]: https://ci.example.invalid/buildConfiguration/InnoHub_HexFlow_PublishIdeaPlugin
[2]: https://ci.example.invalid/buildConfiguration/InnoHub_HexFlow_PublishIdeaPluginNightly

## Resources


Add links to external resources for this project, such as CI server, bug tracker, etc.
