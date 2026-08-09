plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "webapp.js"
            }
        }
        binaries.executable()
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
