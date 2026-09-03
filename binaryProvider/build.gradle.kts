plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
                implementation(libs.kotlinx.serialization.json)
                implementation(project(":wasmParser"))
                implementation(project(":encdec"))
                api(project(":hexanaDocument"))
                api(project(":parsing:common"))
                api(project(":parsing:elf"))
                api(project(":parsing:macho"))
                api(project(":parsing:pe"))
                api(project(":parsing:zip"))
                api(project(":parsing:tar"))
                api(project(":parsing:javaBytecode"))
                api(project(":parsing:dex"))
                api(project(":parsing:arsc"))
                api(project(":parsing:pcap"))
                api(project(":parsing:protobuf"))
                api(project(":parsing:parquet"))
                api(project(":parsing:arrow"))
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

        jsMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }

}
