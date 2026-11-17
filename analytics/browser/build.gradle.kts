plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        browser()
        binaries.library()
    }

    sourceSets {
        jsMain {
            dependencies {
                implementation(project(":analytics:api"))
                implementation(npm("posthog-js", "1.226.0"))
            }
        }
    }
}
