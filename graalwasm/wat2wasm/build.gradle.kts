plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.wasm)
    implementation(project.parent!!.project("common"))
}

kotlin {
    jvmToolchain(21)
}
