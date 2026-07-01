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
                implementation(project(":encdec"))
                api(project(":hexanaDocument"))
                api(project(":parsing:common"))
                api(project(":parsing:elf"))
                api(project(":parsing:macho"))
                api(project(":parsing:pe"))
                api(project(":parsing:zip"))
                api(project(":parsing:javaBytecode"))
                api(project(":parsing:dex"))
                implementation(project(":debugInfo:dwarf"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        webMain {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }

}
