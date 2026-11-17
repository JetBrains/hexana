# Cloud Console build environment

The staging hook in `webapp/stage-cloud-console-staging-package.mjs` consumes a
published Node 22 and JDK 21 helper. Its Dockerfile, runtime contract, and
publication configuration are owned by the Hexana TeamCity configuration
repository under `.teamcity/images/webapp-build-environment/`; this directory
contains only the consumer contract.

The Skaffold helper reference is deliberately pinned by immutable digest.
Publishing a newer helper does not update that reference automatically: the
consumer digest changes only through a reviewed Hexana source change.

The staging profile deliberately does not configure this as Cloud Console's
**Build environment (container image)**. It can use a blank Build Environment
when the default SRE agent provides the host `docker` CLI: the source-owned
staging hook runs the published, digest-pinned helper with only the checkout
mounted. The hook selects the checkout owner and a container-local Gradle home,
so its staged artifacts remain writable without changing checkout permissions.
The helper performs package staging only; Helm, registry login, and deployment
remain outside it. Do not change the generated Cloud Console build configuration
or add publisher-owned image inputs to this directory.
