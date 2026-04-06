plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":encdec"))
    implementation(project(":wasmParser"))
    compileOnly(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(21)
}
