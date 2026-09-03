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
                implementation(project(":hexanaDocument"))
                implementation(project(":parsing:dex"))
                implementation(project(":parsing:arsc"))
                implementation(project(":debugInfo:dwarf"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":parsing:parquet"))
                implementation(project(":parsing:arrow"))
            }
        }
    }
}
