plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    js(IR) {
        browser()
    }

    sourceSets {
        jsMain {
            dependencies {
                api(project(":wasmParser"))
                api(project(":binaryProvider"))
                api(project(":encdec"))
                api(project(":parsing:zip"))
                api(project(":parsing:javaBytecode"))
                api(project(":parsing:dex"))
                api(project(":plugins-shared"))

                api(compose.html.core)
                api(compose.runtime)

                api(libs.kotlinx.browser)
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}