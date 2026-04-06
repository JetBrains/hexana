import java.util.*

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"
version = Properties().apply {
    rootProject.file("idea-plugin/gradle.properties").inputStream().use(::load)
}.getProperty("pluginVersion")

dependencies {
    implementation(project(":mcp:core"))
    implementation(libs.clikt)
    implementation(libs.logback.classic)
    implementation(libs.mcp.kotlin.sdk.jvm)
    implementation(libs.ktor.server.cio.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(project(":graalwasm:wat2wasm"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "org.jetbrains.hexana.mcp.server.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version
        )
    }
}

kotlin {
    jvmToolchain(21)
}
