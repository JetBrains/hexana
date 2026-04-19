plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    implementation(libs.kotlinpoet.jvm)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "org.jetbrains.hexana.mcp.tools.generator.MainKt"
}

kotlin {
    jvmToolchain(21)
}
