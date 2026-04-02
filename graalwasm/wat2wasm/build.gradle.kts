plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlin.reflect)
    implementation(libs.bundles.graalvm)
    implementation(project.parent!!.project("common"))
}

kotlin {
    jvmToolchain(21)
}
