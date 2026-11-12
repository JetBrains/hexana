plugins {
    base
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

// --- Scripting examples ------------------------------------------------------
// The example scripts offered by the Add Script Tab dialog live once at the repo
// root, in `examples/scripting/`, and are bundled by both plugins so that the two
// dialogs cannot offer different examples.

val syncScriptExamples = tasks.register<Sync>("syncScriptExamples") {
    group = "distribution"
    description = "Copies examples/scripting/*.js into hexana-scripts/ for VSIX packaging."

    from(rootProject.layout.projectDirectory.dir("examples/scripting")) {
        include("*.js")
    }
    into(layout.projectDirectory.dir("hexana-scripts"))
}

// --- Bundled WebAssembly feature catalog -------------------------------------
// The snapshot of WebAssembly's `features.json` lives once in `plugins-shared/features/`
// and is copied into the VSIX as the offline fallback for the runtime compatibility table.
// The IntelliJ plugin gets the same snapshot as a generated Kotlin constant; both come from
// that one file, so the two products cannot disagree about what "bundled" means.

val syncWasmFeatures = tasks.register<Sync>("syncWasmFeatures") {
    group = "distribution"
    description = "Copies the features.json snapshot into wasm-features/ for VSIX packaging."

    from(project(":plugins-shared").file("features")) {
        include("features.json")
    }
    into(layout.projectDirectory.dir("wasm-features"))
}

tasks.register("assembleExtensionAssets") {
    group = "distribution"
    description = "Builds all generated assets required before packaging the VS Code extension."

    dependsOn(
        ":vscode-plugin:extension-lib:jsBrowserProductionWebpack",
        ":vscode-plugin:webview:jsBrowserProductionWebpack",
        syncGraalvmRunner,
        syncScriptExamples,
        syncWasmFeatures,
        // The curated and raw .d.ts references the dialog links to. Generated from
        // :mcp:scripting rather than :idea-plugin so that packaging the extension
        // stays on a plain JVM toolchain and never builds the IntelliJ platform.
        ":mcp:scripting:generateHexanaScriptingDts",
    )
}

// --- Third-party notices -----------------------------------------------------
// THIRD-PARTY-NOTICES.md at the extension root is packaged into the VSIX by vsce. It covers the Kotlin/JS libraries
// and npm packages bundled into extension-lib.js and webview.js, the extension host's production npm dependencies
// (read from node_modules, so run `npm install` first), and the embedded Wasm printer's Rust crates.
// build-release-package.sh generates it and runs scripts/verify-vsix-notices.js on the finished VSIX.

apply(from = rootProject.file("gradle/third-party-notices.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val registerThirdPartyNotices = extra["registerThirdPartyNotices"] as (Map<String, Any?>) -> TaskProvider<Task>

val extensionHostNpmDependencies = (groovy.json.JsonSlurper().parse(file("package.json")) as Map<*, *>)
    .let { it["dependencies"] as Map<*, *> }
    .keys.map { it as String }

registerThirdPartyNotices(
    mapOf(
        "taskName" to "generateThirdPartyNotices",
        "output" to providers.provider { layout.projectDirectory.file("THIRD-PARTY-NOTICES.md") },
        "title" to "Hexana VS Code Extension: Third-Party Notices",
        "description" to "Generates THIRD-PARTY-NOTICES.md for the VSIX (requires npm install in vscode-plugin).",
        "mavenClasspaths" to listOf(":vscode-plugin:extension-lib", ":vscode-plugin:webview").map { projectPath ->
            providers.provider { project(projectPath).configurations.getByName("jsRuntimeClasspath") }
        },
        "npmNodeModules" to rootProject.layout.buildDirectory.dir("js/node_modules"),
        "npmPackageJsons" to listOf("hexana-vscode-plugin-extension-lib", "hexana-vscode-plugin-webview").map { packageName ->
            rootProject.layout.buildDirectory.file("js/packages/$packageName/package.json").map { it.asFile }
        },
        "npmPackages" to listOf(
            mapOf(
                "nodeModules" to layout.projectDirectory.dir("node_modules"),
                "roots" to extensionHostNpmDependencies,
            )
        ),
        "rustModules" to listOf("wasmprinter_wrapper"),
        "dependsOn" to listOf(
            ":kotlinNpmInstall",
            ":vscode-plugin:extension-lib:jsPackageJson",
            ":vscode-plugin:webview:jsPackageJson",
        ),
    )
)
