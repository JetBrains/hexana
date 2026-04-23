plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "webview.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":wasmParser"))
                implementation(project(":binaryProvider"))
                implementation(project(":encdec"))
                implementation(project(":plugins-shared"))

                implementation(compose.html.core)
                implementation(compose.runtime)

                implementation(libs.kotlinx.browser)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        jsTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// Copy wasmprinter_wrapper.wasm into the webpack output directory so the webview
// can fetch it at runtime via the browser WebAssembly API.
val copyWasmPrinter by tasks.registering(Copy::class) {
    from(rootProject.file("graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
}

tasks.named("jsBrowserProductionWebpack") {
    finalizedBy(copyWasmPrinter)
}

// Also copy for development builds.
val copyWasmPrinterDev by tasks.registering(Copy::class) {
    from(rootProject.file("graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm"))
    into(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
}

tasks.named("jsBrowserDevelopmentWebpack") {
    finalizedBy(copyWasmPrinterDev)
}
