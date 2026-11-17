import { expect } from "chai";
import { mcpServerArgs, mcpServerEnv, mcpTelemetryEnabledValue } from "../../../src/mcpServerTelemetry";

describe("VS Code MCP telemetry propagation", function () {
    it("passes enabled telemetry state through command arguments and env", function () {
        const telemetryEnabled = mcpTelemetryEnabledValue(() => true);

        expect(mcpServerArgs("debug", telemetryEnabled)).to.deep.equal([
            "--transport",
            "stdio",
            "--log-level",
            "debug",
            "--telemetry-enabled",
            "true",
        ]);
        expect(mcpServerEnv("/opt/jbr", telemetryEnabled)).to.deep.equal({
            HEXANA_MCP_HOST: "vscode",
            HEXANA_MCP_TELEMETRY_ENABLED: "true",
            JAVA_HOME: "/opt/jbr",
        });
    });

    it("passes disabled telemetry state through command arguments and env", function () {
        const telemetryEnabled = mcpTelemetryEnabledValue(() => false);

        expect(mcpServerArgs("quiet", telemetryEnabled)).to.deep.equal([
            "--transport",
            "stdio",
            "--log-level",
            "quiet",
            "--telemetry-enabled",
            "false",
        ]);
        expect(mcpServerEnv(undefined, telemetryEnabled)).to.deep.equal({
            HEXANA_MCP_HOST: "vscode",
            HEXANA_MCP_TELEMETRY_ENABLED: "false",
        });
    });
});
