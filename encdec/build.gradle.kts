plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    js {
        browser()
    }

    wasmJs {
        browser()
    }

    jvmToolchain(17)
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.io.core)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
