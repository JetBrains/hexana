export const deploymentTestEnvironment = Object.freeze({
    HEXANA_CLOUD_CONSOLE_SERVER_URL: "https://teamcity.example.invalid",
    HEXANA_CLOUD_CONSOLE_VCS_URL: "ssh://git@vcs.example.invalid/example/hexana.git",
    HEXANA_K8S_NAMESPACE: "example-namespace",
    HEXANA_IMAGE_PULL_SECRET: "example-pull-secret",
    HEXANA_GCP_PROJECT: "example-project",
    HEXANA_GCP_CLUSTER: "example-cluster",
    HEXANA_GCP_REGION: "example-region",
    HEXANA_REGISTRY_HOST: "registry.example.invalid",
    HEXANA_REGISTRY_LOGIN_PARAM: "example.registry.login",
    HEXANA_REGISTRY_TOKEN_PARAM: "example.registry.token",
    HEXANA_WEBAPP_IMAGE_REPOSITORY: "registry.example.invalid/example/webapp",
});

export const releaseTestEnvironment = Object.freeze({
    ...deploymentTestEnvironment,
    HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: "Example_CloudConsole_Webapp",
    HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: "Example_CloudConsole_WebappVcsRoot",
    HEXANA_WEBAPP_PRODUCTION_ENDPOINTS: "https://webapp.example.invalid/",
});

export const masterTestEnvironment = Object.freeze({
    ...deploymentTestEnvironment,
    HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: "Example_CloudConsole_WebappMaster",
    HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: "Example_CloudConsole_WebappMasterVcsRoot",
    HEXANA_WEBAPP_MASTER_ENDPOINTS: "https://webapp-master.example.invalid/",
});
