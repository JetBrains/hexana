# Cloud Console build environment

This image supplies the tooling required by the staging hook in
`webapp/stage-cloud-console-staging-package.mjs`: Node 22 and JDK 21, plus the
shell, Git, and `xargs` used by the Gradle wrapper and hook.

Both upstream base images are pinned by manifest digest. The image deliberately
ends as the existing non-root `node` user (UID 1000), so the TeamCity container
workspace remains writable without a root runtime.

Publication is an external CI operation. The publisher must emit an immutable
Linux `amd64` image digest, for example with `docker buildx build --platform
linux/amd64 --push`. It must not use a locally loaded single-architecture image
as the published result. Set the resulting digest in the Webapp's Cloud Console
**Build environment (container image)** setting. Do not change the generated
Cloud Console build configuration or add publication details to this directory.
