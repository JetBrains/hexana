plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}
