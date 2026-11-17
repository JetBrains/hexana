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
    jvmToolchain(21)

    jvm()

    js {
        browser {
            testTask {
                passTestFlagsToEnvironment()

                useKarma {
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
                    useChrome()
                    useConfigDirectory(
                        project.projectDir.resolve("karma.config.d/web")
                    )
                }
            }
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.aircompressor)
            }
        }
    }
}
