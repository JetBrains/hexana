plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js {
        nodejs()
        binaries.library()
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.posthog.server)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.gson)
            }
        }

        jsMain {
            dependencies {
                implementation(npm("posthog-node", "5.29.2"))
            }
        }
    }
}
