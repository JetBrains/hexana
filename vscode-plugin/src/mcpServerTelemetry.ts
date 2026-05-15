export function mcpServerArgs(logLevel: string, telemetryEnabled: string): string[] {
    return [
        "--transport",
        "stdio",
        "--log-level",
        logLevel,
        "--telemetry-enabled",
        telemetryEnabled,
    ];
}

export function mcpServerEnv(javaHome: string | undefined, telemetryEnabled: string): Record<string, string> {
    const env: Record<string, string> = {
        HEXANA_MCP_HOST: "vscode",
        HEXANA_MCP_TELEMETRY_ENABLED: telemetryEnabled,
    };
    if (javaHome) {
        env.JAVA_HOME = javaHome;
    }
    return env;
}

export function mcpTelemetryEnabledValue(isTelemetryEnabled: () => boolean): string {
    return isTelemetryEnabled() ? "true" : "false";
}
