import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createDeploymentBridge, createDeploymentPolicy } from "./deploy-webapp-release.mjs";

const target = {
    serverUrl: "https://ci.example.invalid",
    buildTypeId: "HexanaCi_Deploy_HexanaWebappMaster",
    vcsRootId: "HexanaCi_Deploy_HexanaWebappMasterSshGitGitJetbrainsTeamHxHexanaGit",
};
const parameters = {
    "env.APPLICATION_NAME": "hexana-webapp-master",
    "env.K8S_NAMESPACE": "hexana-namespace",
    "env.IMAGE_PULL_SECRET": "registry-pull-secret",
    "env.JBCC_GCP_CLUSTER": "example-cluster",
    "env.JBCC_GCP_PROJECT": "example-gcp-project",
    "env.JBCC_GCP_REGION": "europe-west1",
};
export const masterDeploymentPolicy = createDeploymentPolicy({
    mode: "master",
    profile: "deploy_only_master",
    identityField: "source",
    branchEnv: "HEXANA_WEBAPP_SOURCE_BRANCH",
    revisionEnv: "HEXANA_SOURCE_REVISION",
    allowedTarget: target,
    endpoints: ["https://hexana-webapp-master.example.invalid/"],
    approvedParameters: parameters,
    branchSpec: "+:<default>",
    defaultBranch: "refs/heads/master",
    sourceBuildName: "nightly",
    readinessLabel: "Master",
    endpointLabel: "master",
    readinessPhase: "master-readiness",
    readinessDeadlineField: "masterReadiness",
});

const bridge = createDeploymentBridge(masterDeploymentPolicy);
export const allowedTarget = bridge.allowedTarget;
export const masterEndpoints = bridge.endpoints;
export const configFromEnv = bridge.configFromEnv;
export const validateEvidence = bridge.validateEvidence;
export const createVcsRootInstancesRequest = bridge.createVcsRootInstancesRequest;
export const createSafetyPreflightRequests = bridge.createSafetyPreflightRequests;
export const validateSafetyPreflight = bridge.validateSafetyPreflight;
export const resolveVcsRootInstance = bridge.resolveVcsRootInstance;
export const createQueueRequest = bridge.createQueueRequest;
export const deployMaster = bridge.deploy;

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    bridge.runCli(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 2; });
}
