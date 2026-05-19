plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        nodejs()
        browser {
            commonWebpackConfig {
                outputFileName = "extension-lib.js"
            }
            webpackTask {
                mainOutputFileName.set("extension-lib.js")
                output.library = "hexanaExtensionLib"
                output.libraryTarget = "commonjs2"
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
                implementation(project(":analytics"))
            }
        }

        jsTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.named("jsBrowserTest") {
    // extension-lib is bundled through the browser webpack task, but it runs in the
    // VS Code extension host and depends on Node-only modules such as posthog-node.
    enabled = false
}

tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("jsNodeTest") {
    useMocha()
}
