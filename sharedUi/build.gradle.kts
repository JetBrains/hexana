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

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":wasmParser"))
                implementation(project(":binaryProvider"))
                implementation(project(":encdec"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
