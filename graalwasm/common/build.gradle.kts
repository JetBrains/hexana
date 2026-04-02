plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.bundles.graalvm)
}

kotlin {
    jvmToolchain(21)
}
