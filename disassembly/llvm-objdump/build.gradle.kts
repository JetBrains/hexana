plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":disassembly:core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
}
