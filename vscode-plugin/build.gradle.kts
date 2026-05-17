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

// --- GraalVM runner resources ------------------------------------------------
// Copies the thin HexanaGraalWasmRunner.java source into
// `vscode-plugin/graalvm-runner/` so it is bundled in the VSIX.
// The TypeScript host compiles the runner on first use with `javac` from the
// user's GraalVM JDK (which has the Polyglot API on its module path) and runs
// it via `java -cp`.  No extra JARs are bundled.

val syncGraalvmRunner = tasks.register<Sync>("syncGraalvmRunner") {
    group = "distribution"
    description = "Copies runner source into graalvm-runner/ for VSIX packaging."

    from(project(":idea-plugin").file("src/main/resources/runner")) {
        into("src")
    }
    into(layout.projectDirectory.dir("graalvm-runner"))
}

tasks.register("assembleExtensionAssets") {
    group = "distribution"
    description = "Builds all generated assets required before packaging the VS Code extension."

    dependsOn(
        ":vscode-plugin:extension-lib:jsBrowserProductionWebpack",
        ":vscode-plugin:webview:jsBrowserProductionWebpack",
        syncMcpServer,
        syncGraalvmRunner,
    )
}
