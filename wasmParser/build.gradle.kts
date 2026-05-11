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

val wat2WasmFullResource =
    project(":graalwasm:wat2wasm").layout.buildDirectory.file("resources/main/wat2wasm_full.wasm")

val copyWat2WasmForWebTests by tasks.registering {
    dependsOn(":graalwasm:wat2wasm:processResources")
    inputs.file(wat2WasmFullResource)
    outputs.dir(layout.buildDirectory.dir("processedResources/js/test"))
    outputs.dir(layout.buildDirectory.dir("processedResources/wasmJs/test"))

    doLast {
        copy {
            from(wat2WasmFullResource)
            into(layout.buildDirectory.dir("processedResources/js/test"))
        }
        copy {
            from(wat2WasmFullResource)
            into(layout.buildDirectory.dir("processedResources/wasmJs/test"))
        }
    }
}

tasks.matching {
    it.name == "jsTest" ||
            it.name == "wasmJsTest" ||
            it.name == "jsBrowserTest" ||
            it.name == "wasmJsBrowserTest" ||
            it.name == "compileTestDevelopmentExecutableKotlinJs" ||
            it.name == "compileTestDevelopmentExecutableKotlinWasmJs"
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
                api(project(":debugInfo:dwarf"))
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
