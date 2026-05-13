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
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
