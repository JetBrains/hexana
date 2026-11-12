import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createDeploymentBridge, createDeploymentPolicy } from "./deploy-webapp-release.mjs";

export const createMasterDeploymentPolicy = env => createDeploymentPolicy({
    mode: "master",
    profile: "deploy_only_master",
    identityField: "source",
    branchEnv: "HEXANA_WEBAPP_SOURCE_BRANCH",
    revisionEnv: "HEXANA_SOURCE_REVISION",
    endpointsEnv: "HEXANA_WEBAPP_MASTER_ENDPOINTS",
    applicationName: "hexana-webapp-master",
    branchSpec: "+:<default>",
    defaultBranch: "refs/heads/master",
    queueBranchName: "<default>",
    sourceBuildName: "nightly",
    readinessLabel: "Master",
    endpointLabel: "master",
    readinessPhase: "master-readiness",
    readinessDeadlineField: "masterReadiness",
}, env);
export const createMasterDeploymentBridge = env => createDeploymentBridge(createMasterDeploymentPolicy(env));

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    Promise.resolve()
        .then(() => createMasterDeploymentBridge(process.env).runCli(process.argv.slice(2)))
        .catch(error => { console.error(error.message); process.exitCode = 2; });
}
