import * as fs from "fs";
import * as vscode from "vscode";
import {
    getDownloadedHexanaMcpServerVersion,
    getHexanaMcpServerDir,
    getHexanaMcpServerLauncherPath,
    prepareHexanaMcpServer,
} from "./mcpServerDownloader";

const mcpProviderId = "hexana.vscode.mcp";
const mcpServerLabel = "Hexana MCP";

export interface HexanaMcpServerProviderOptions {
    isTelemetryEnabled: () => boolean;
}

export function registerMcpServerDefinitionProvider(
    context: vscode.ExtensionContext,
    options: HexanaMcpServerProviderOptions,
): void {
    const didChange = new vscode.EventEmitter<void>();
    context.subscriptions.push(didChange);

    context.subscriptions.push(
        vscode.lm.registerMcpServerDefinitionProvider(mcpProviderId, {
            onDidChangeMcpServerDefinitions: didChange.event,

            provideMcpServerDefinitions: async () => {
                const config = vscode.workspace.getConfiguration("hexana.mcp");
                if (!config.get<boolean>("enabled", true)) {
                    return [];
                }

                const server = createMcpServerDefinition(
                    context,
                    config,
                    options,
                    await getDownloadedHexanaMcpServerVersion(context),
                );
                return [server];
            },

            resolveMcpServerDefinition: async (server) => {
                if (!(server instanceof vscode.McpStdioServerDefinition)) {
                    throw new Error("Hexana MCP only supports stdio server definitions.");
                }

                const config = vscode.workspace.getConfiguration("hexana.mcp");
                if (!config.get<boolean>("enabled", true)) {
                    return undefined;
                }

                const serverUri = await prepareHexanaMcpServer(context);
                if (!serverUri) {
                    return undefined;
                }

                const resolved = createMcpServerDefinition(
                    context,
                    config,
                    options,
                    await getDownloadedHexanaMcpServerVersion(context),
                );
                await validateMcpLauncherPath(resolved.command);
                resolved.cwd = serverUri;
                return resolved;
            },
        }),
    );

    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((event) => {
            if (
                event.affectsConfiguration("hexana.mcp.enabled") ||
                event.affectsConfiguration("hexana.mcp.javaHome") ||
                event.affectsConfiguration("hexana.mcp.logLevel") ||
                event.affectsConfiguration("hexana.enableStatistics")
            ) {
                didChange.fire();
            }
        }),
    );

    context.subscriptions.push(vscode.env.onDidChangeTelemetryEnabled(() => didChange.fire()));
}

function createMcpServerDefinition(
    context: vscode.ExtensionContext,
    config: vscode.WorkspaceConfiguration,
    options: HexanaMcpServerProviderOptions,
    version: string | undefined,
): vscode.McpStdioServerDefinition {
    const telemetryEnabled = mcpTelemetryEnabledValue(options);
    const server = new vscode.McpStdioServerDefinition(
        mcpServerLabel,
        getHexanaMcpServerLauncherPath(context),
        [
            "stdio",
            "--log-level",
            mcpLogLevel(config),
            "--telemetry-enabled",
            telemetryEnabled,
        ],
        mcpServerEnv(config, telemetryEnabled),
        version ?? String(context.extension.packageJSON.version ?? "dev"),
    );
    server.cwd = vscode.Uri.file(getHexanaMcpServerDir(context));
    return server;
}

function mcpServerEnv(config: vscode.WorkspaceConfiguration, telemetryEnabled: string): Record<string, string> {
    const env: Record<string, string> = {
        HEXANA_MCP_HOST: "vscode",
        HEXANA_MCP_TELEMETRY_ENABLED: telemetryEnabled,
    };
    const javaHome = mcpJavaHome(config);
    if (javaHome) {
        env.JAVA_HOME = javaHome;
    }
    return env;
}

function mcpLogLevel(config: vscode.WorkspaceConfiguration): string {
    const value = config.get<string>("logLevel", "quiet");
    return value === "info" || value === "debug" ? value : "quiet";
}

function mcpTelemetryEnabledValue(options: HexanaMcpServerProviderOptions): string {
    return options.isTelemetryEnabled() ? "true" : "false";
}

async function validateMcpLauncherPath(launcherPath: string): Promise<void> {
    const stats = await fs.promises.stat(launcherPath).catch(() => undefined);
    if (!stats?.isFile()) {
        throw new Error(`Hexana MCP server launcher not found: ${launcherPath}.`);
    }

    if (process.platform !== "win32") {
        await fs.promises.access(launcherPath, fs.constants.X_OK).catch(() => {
            throw new Error(`Hexana MCP server launcher is not executable: ${launcherPath}.`);
        });
    }
}

function mcpJavaHome(config: vscode.WorkspaceConfiguration): string | undefined {
    return config.get<string>("javaHome", "").trim() || undefined;
}
