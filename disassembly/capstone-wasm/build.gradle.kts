plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js {
        // Browser test run is disabled until the disassembler.wasm test fixture
        // is wired for Kotlin/JS — see jsTest/TestFixture.js.kt for the TODO.
        browser {
            testTask { enabled = false }
        }
    }

    wasmJs {
        // Same situation as js: compilation works, execution waits on a
        // browser-side bytes provisioning path. See wasmJsTest/TestFixture.wasmJs.kt.
        browser {
            testTask { enabled = false }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":disassembly:core"))
                api(project(":encdec"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.chicory.runtime)
            }
        }
    }
}
