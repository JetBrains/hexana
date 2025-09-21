plugins {
    alias(libs.plugins.android.application) apply(false)
    alias(libs.plugins.android.library) apply(false)
    alias(libs.plugins.kotlin.multiplatform) apply(false)
    alias(libs.plugins.compose.compiler) apply(false)
    alias(libs.plugins.compose.multiplatform) apply(false)
}

allprojects {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            compilerOptions {
                optIn.add("kotlin.js.ExperimentalWasmJsInterop")

                // expect actual are surprisingly still in beta - https://youtrack.jetbrains.com/issue/KT-61573
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
}

