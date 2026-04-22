import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

fun KotlinJsTest.passTestFlagsToEnvironment() {
    listOf(
        "hx.tests.web.enableChromeOrChromium",
        "hx.tests.web.enableFirefox",
        "hx.tests.web.enableSafari"
    ).filter {
        project.findProperty(it)?.toString() == "1"
    }.forEach { propertyName ->
        environment(propertyName, "1")
    }
}

val copyWat2WasmForWebTests by tasks.registering(Copy::class) {
    from(rootProject.file("wasm-modules/wat2wasm/target/wasm32-unknown-unknown/release/wat2wasm_full.wasm"))
    into(layout.buildDirectory.dir("processedResources/js/test"))
    doLast {
        copy {
            from(rootProject.file("wasm-modules/wat2wasm/target/wasm32-unknown-unknown/release/wat2wasm_full.wasm"))
            into(layout.buildDirectory.dir("processedResources/wasmJs/test"))
        }
    }
}

tasks.matching {
    it.name == "jsTest" ||
            it.name == "wasmJsTest" ||
            it.name == "jsBrowserTest" ||
            it.name == "wasmJsBrowserTest" ||
            it.name == "compileTestDevelopmentExecutableKotlinJs"
}.configureEach {
    dependsOn(copyWat2WasmForWebTests)
}

kotlin {
    jvmToolchain(21)

    jvm {
        testRuns.all {
            executionTask.configure {
                maxHeapSize = "8g"
            }
        }
    }

    js {
        browser {
            testTask {
                passTestFlagsToEnvironment()

                useKarma {
                    // We need to set up at least one browser here due to kotlin tooling limitations
                    // Actual browser configuration is set in karma.config.d/web/config.js
                    useChrome()
                    useConfigDirectory(
                        project.projectDir.resolve("karma.config.d/web")
                    )
                }
            }
        }
    }

    wasmJs {
        browser {
            testTask {
                passTestFlagsToEnvironment()

                useKarma {
                    // We need to set up at least one browser here due to kotlin tooling limitations
                    // Actual browser configuration is set in karma.config.d/web/config.js
                    useChrome()
                    useConfigDirectory(
                        project.projectDir.resolve("karma.config.d/web")
                    )
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":encdec"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(project(":graalwasm:wat2wasm"))
            }
        }

        webMain {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }
}
