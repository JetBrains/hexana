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