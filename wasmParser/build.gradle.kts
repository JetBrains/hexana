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

kotlin {
    jvm()

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

        webMain {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }
}
