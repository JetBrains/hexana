plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    implementation(project(":binaryProvider"))
    implementation(project(":mcp:core"))
    implementation(project(":wasmParser"))
    implementation(libs.bundles.graalvm.js)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}
