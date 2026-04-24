plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
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
            }
        }
    }
}
