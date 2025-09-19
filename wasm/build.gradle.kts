plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    js {
        browser {
            testTask {
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

    jvmToolchain(17)
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
