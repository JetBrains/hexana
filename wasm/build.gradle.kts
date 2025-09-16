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

        val commonTest by getting
        jvmMain {

        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jsMain by getting
        val jsTest by getting
        val wasmJsMain by getting
        val wasmJsTest by getting
    }
}
