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

- run the 'Publish Idea Plugin' build on TC

## Resources

Add links to external resources for this project, such as CI server, bug tracker, etc.
