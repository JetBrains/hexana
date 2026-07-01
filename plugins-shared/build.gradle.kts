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
                implementation(project(":wasmParser"))
                implementation(project(":binaryProvider"))
                implementation(project(":encdec"))
                implementation(project(":parsing:zip"))
                implementation(project(":debugInfo:dwarf"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
