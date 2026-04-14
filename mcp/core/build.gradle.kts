plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":encdec"))
    implementation(project(":wasmParser"))
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(project(":graalwasm:wat2wasm"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(21)
}
