plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js {
        browser()
    }

    wasmJs {
        browser()
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
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.chicory.runtime)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit4)
            }
        }
    }
}
