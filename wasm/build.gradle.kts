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
                implementation(project(":encdec"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
