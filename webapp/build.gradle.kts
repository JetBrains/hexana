plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    val composeRuntimeForCompiler = compose.runtime

    js(IR) {
        val parserWorker = compilations.create("parserWorker") {
            defaultSourceSet {
                kotlin.srcDir("src/parserWorkerMain/kotlin")
                dependencies {
                    implementation(project(":wasmParser"))
                    implementation(project(":encdec"))
                    compileOnly(composeRuntimeForCompiler)
                }
            }
        }

        browser {
            commonWebpackConfig {
                outputFileName = "webapp.js"
            }
            testTask {
                useKarma {
                    useChrome()
                    useConfigDirectory(project.projectDir.resolve("karma.config.d/web"))
                }
            }
        }
        binaries.executable()
        parserWorker.binaries.executable(parserWorker)
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":web-ui"))
            }
        }

        jsTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

val parserWorkerCompileSync = tasks.named("jsParserWorkerParserWorkerProductionExecutableCompileSync")
val parserWorkerOutputDir = layout.buildDirectory.dir("parser-worker/production")
val parserWorkerMeasurementsEnabled = providers.environmentVariable("HX_WEB_PARSER_WORKER_MEASUREMENTS")
    .map { it == "1" }
    .orElse(false)

val parserWorkerBrowserProductionWebpack by tasks.registering(Exec::class) {
    group = "verification"
    description = "Bundles the isolated parser worker for browser tests."
    dependsOn(parserWorkerCompileSync, rootProject.tasks.named("kotlinNpmInstall"))

    val rootJsDir = rootProject.layout.buildDirectory.dir("js")
    val workerEntry = rootJsDir.map {
        it.file("packages/hexana-webapp-parserWorker/kotlin/hexana-webapp-parserWorker.js")
    }
    inputs.dir(rootJsDir.map { it.dir("packages/hexana-webapp-parserWorker/kotlin") })
    outputs.file(parserWorkerOutputDir.map { it.file("parser-worker.js") })
    workingDir(rootJsDir)
    doFirst {
        commandLine(
            "node",
            rootJsDir.get().file("node_modules/webpack/bin/webpack.js").asFile,
            "--mode", "production",
            "--entry", workerEntry.get().asFile.absolutePath,
            "--output-path", parserWorkerOutputDir.get().asFile.absolutePath,
            "--output-filename", "parser-worker.js",
        )
    }
}

tasks.named<Copy>("jsTestProcessResources") {
    dependsOn(parserWorkerBrowserProductionWebpack)
    from(parserWorkerOutputDir) {
        include("parser-worker.js")
    }
    into("parser-worker-fixtures") {
        from(rootProject.file("wasmParser/src/commonTest/resources/snippets/addition.wasm"))
        if (parserWorkerMeasurementsEnabled.get()) {
            from(rootProject.file("wasmParser/src/commonTest/resources/faustide.wasm"))
            from(rootProject.file("wasmParser/src/commonTest/resources/mpp-demo.wasm"))
        }
    }
}

// Copy wasmprinter_wrapper.wasm into the production webpack output directory
// so the webapp can fetch it at runtime via the browser WebAssembly API.
val copyWasmPrinter by tasks.registering(Copy::class) {
    from(rootProject.file("graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
    dependsOn(tasks.named("jsBrowserProductionWebpack"))
}

tasks.named("jsBrowserProductionWebpack") {
    finalizedBy(copyWasmPrinter)
}

tasks.named("jsBrowserDistribution") {
    dependsOn(copyWasmPrinter)
}

// Copy editor.css into production output (provides VS Code-compatible base styles).
val copyEditorCss by tasks.registering(Copy::class) {
    from(rootProject.file("vscode-plugin/media/editor.css"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
    dependsOn(tasks.named("jsBrowserProductionWebpack"))
}

tasks.named("jsBrowserProductionWebpack") {
    finalizedBy(copyEditorCss)
}

tasks.named("jsBrowserDistribution") {
    dependsOn(copyEditorCss)
}

tasks.register("verifyPublicWebappCapabilityPolicy") {
    group = "verification"
    description = "Verifies the public webapp has no OSV-network capability."
    dependsOn(tasks.named("jsBrowserDistribution"))

    doLast {
        val distributionDir = layout.buildDirectory.dir("dist/js/productionExecutable").get().asFile
        check(distributionDir.isDirectory) {
            "Final public distribution is missing: ${distributionDir.absolutePath}"
        }
        val indexFile = distributionDir.resolve("index.html")
        check(indexFile.isFile) {
            "Final public distribution is missing index.html: ${indexFile.absolutePath}"
        }
        val index = indexFile.readText()
        check("api.osv.dev" !in index) { "Public CSP must not permit OSV networking" }

        val forbiddenLiterals = listOf("api.osv.dev", "/v1/querybatch", "/v1/vulns/")
        val productionBundles = distributionDir.walkTopDown()
            .filter { it.isFile && it.extension == "js" }
            .toList()
        check(productionBundles.isNotEmpty()) {
            "No JavaScript bundles were found in ${distributionDir.absolutePath}"
        }
        productionBundles.forEach { bundle ->
            val bundleText = bundle.readText()
            forbiddenLiterals.forEach { literal ->
                check(literal !in bundleText) {
                    "Public bundle ${bundle.name} contains forbidden capability literal: $literal"
                }
            }
        }
    }
}

// Build the distribution and serve it on http://0.0.0.0:8123 (LAN-reachable).
// Backs the "Hexana WebApp" run configuration; stop the run to stop the server.
tasks.register<Exec>("serve") {
    dependsOn(tasks.named("jsBrowserDistribution"))
    workingDir = layout.buildDirectory.dir("dist/js/productionExecutable").get().asFile
    commandLine("python3", "-m", "http.server", "8123")
}

// Build the webapp Component Model binary that embeds all static assets.
// Runs `cargo build --release` for the webapp_server crate and copies the
// resulting component to webapp/build/component/hexana-webapp.component.wasm.
val buildComponent by tasks.registering(Exec::class) {
    dependsOn(tasks.named("jsBrowserDistribution"))
    val distDir = layout.buildDirectory.dir("dist/js/productionExecutable").get().asFile
    val crateDir = rootProject.file("wasm-modules/webapp_server")
    val outDir = layout.buildDirectory.dir("component").get().asFile
    inputs.dir(distDir)
    inputs.dir(crateDir)
    outputs.dir(outDir)
    environment("HEXANA_WEBAPP_DIST", distDir.absolutePath)
    workingDir = crateDir
    commandLine("cargo", "build", "--release", "--target", "wasm32-wasip2")
    doLast {
        outDir.mkdirs()
        val src = crateDir.resolve("target/wasm32-wasip2/release/webapp_server.wasm")
        val dst = outDir.resolve("hexana-webapp.component.wasm")
        src.copyTo(dst, overwrite = true)
        logger.lifecycle("Component written to ${dst.absolutePath} (${dst.length()} bytes)")
    }
}

// Serve the Component Model binary on http://0.0.0.0:8124 via wasmtime.
tasks.register<Exec>("serveComponent") {
    dependsOn(buildComponent)
    val component = layout.buildDirectory.file("component/hexana-webapp.component.wasm").get().asFile
    commandLine(
        "wasmtime", "serve",
        "-S", "cli=y",
        "--addr", "0.0.0.0:8124",
        component.absolutePath
    )
}

// Dev build copies.
val copyWasmPrinterDev by tasks.registering(Copy::class) {
    from(rootProject.file("graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    dependsOn(tasks.named("jsBrowserDevelopmentWebpack"))
}

tasks.named("jsBrowserDevelopmentWebpack") {
    finalizedBy(copyWasmPrinterDev)
}

val copyEditorCssDev by tasks.registering(Copy::class) {
    from(rootProject.file("vscode-plugin/media/editor.css"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    dependsOn(tasks.named("jsBrowserDevelopmentWebpack"))
}

tasks.named("jsBrowserDevelopmentWebpack") {
    finalizedBy(copyEditorCssDev)
}
