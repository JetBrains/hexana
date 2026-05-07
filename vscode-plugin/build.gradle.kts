plugins {
    base
}

val vscodeMcpServerInstallDir = project(":mcp:vscode-server")
    .layout
    .buildDirectory
    .dir("install/hexana-vscode-mcp-server")

val syncMcpServer = tasks.register<Sync>("syncMcpServer") {
    group = "distribution"
    description = "Copies the VS Code MCP server distribution into vscode-plugin/server for VSIX packaging."

    dependsOn(":mcp:vscode-server:installDist")
    from(vscodeMcpServerInstallDir)
    into(layout.projectDirectory.dir("server"))
    filesMatching("bin/*") {
        if (!name.endsWith(".bat") && !name.endsWith(".cmd")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

tasks.register("assembleExtensionAssets") {
    group = "distribution"
    description = "Builds all generated assets required before packaging the VS Code extension."

    dependsOn(
        ":vscode-plugin:extension-lib:jsBrowserProductionWebpack",
        ":vscode-plugin:webview:jsBrowserProductionWebpack",
        syncMcpServer,
    )
}
