import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";

const MCP_PROVIDER_ID = "hexana.vscode.mcp";
const MCP_SERVER_LABEL = "Hexana MCP";

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
        vscode.lm.registerMcpServerDefinitionProvider(MCP_PROVIDER_ID, {
            onDidChangeMcpServerDefinitions: didChange.event,

            provideMcpServerDefinitions: async () => {
                const config = vscode.workspace.getConfiguration("hexana.mcp");
                if (!config.get<boolean>("enabled", true)) {
                    return [];
                }

                const telemetryEnabled = mcpTelemetryEnabledValue(options);
                const server = new vscode.McpStdioServerDefinition(
                    MCP_SERVER_LABEL,
                    mcpServerLauncherPath(context, config),
                    [
                        "--transport",
                        "stdio",
                        "--log-level",
                        mcpLogLevel(config),
                        "--telemetry-enabled",
                        telemetryEnabled,
                    ],
                    mcpServerEnv(config, telemetryEnabled),
                    String(context.extension.packageJSON.version ?? "dev"),
                );
                server.cwd = vscode.Uri.file(context.extensionPath);
                return [server];
            },

            resolveMcpServerDefinition: async (server) => {
                if (!(server instanceof vscode.McpStdioServerDefinition)) {
                    throw new Error("Hexana MCP only supports stdio server definitions.");
                }
                await validateMcpLauncherPath(context, server.command);
                return server;
            },
        }),
    );

    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration((event) => {
            if (
                event.affectsConfiguration("hexana.mcp.enabled") ||
                event.affectsConfiguration("hexana.mcp.serverPath") ||
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

function mcpServerLauncherPath(
    context: vscode.ExtensionContext,
    config: vscode.WorkspaceConfiguration,
): string {
    const configuredPath = config.get<string>("serverPath", "").trim();
    if (configuredPath.length > 0) {
        const launcherPath = path.isAbsolute(configuredPath)
            ? configuredPath
            : context.asAbsolutePath(configuredPath);
        if (!isPathInside(context.extensionPath, launcherPath)) {
            throw new Error(
                "hexana.mcp.serverPath must be empty or point to a launcher inside the Hexana extension directory.",
            );
        }
        return launcherPath;
    }

    const executableName = process.platform === "win32"
        ? "hexana-vscode-mcp-server.bat"
        : "hexana-vscode-mcp-server";
    return context.asAbsolutePath(path.join("server", "bin", executableName));
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
    const value = config.get<string>("logLevel", "info");
    return value === "quiet" || value === "debug" ? value : "info";
}

function mcpTelemetryEnabledValue(options: HexanaMcpServerProviderOptions): string {
    return options.isTelemetryEnabled() ? "true" : "false";
}

async function validateMcpLauncherPath(context: vscode.ExtensionContext, launcherPath: string): Promise<void> {
    const stats = await fs.promises.stat(launcherPath).catch(() => undefined);
    if (!stats?.isFile()) {
        throw new Error(
            `Hexana MCP server launcher not found: ${launcherPath}. ` +
                "Build the extension assets or configure hexana.mcp.serverPath.",
        );
    }

    const [extensionRoot, launcherRealPath] = await Promise.all([
        fs.promises.realpath(context.extensionPath),
        fs.promises.realpath(launcherPath),
    ]);
    if (!isPathInside(extensionRoot, launcherRealPath)) {
        throw new Error("Hexana MCP server launcher must resolve inside the Hexana extension directory.");
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

function isPathInside(rootPath: string, candidatePath: string): boolean {
    const relative = path.relative(path.resolve(rootPath), path.resolve(candidatePath));
    return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}
