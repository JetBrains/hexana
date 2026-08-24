plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    implementation(project(":binaryProvider"))
    implementation(project(":mcp:core"))
    implementation(project(":plugins-shared"))
    implementation(project(":wasmParser"))
    implementation(libs.bundles.graalvm.js)
    // Needed by HostObjectProxy to expose `binary.raw` reflectively.
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}
