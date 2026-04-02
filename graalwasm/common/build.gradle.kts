plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.wasm)
}

kotlin {
    jvmToolchain(21)
}
