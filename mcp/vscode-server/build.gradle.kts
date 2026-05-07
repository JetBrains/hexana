import java.util.Properties

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"
version = Properties().apply {
    rootProject.layout.projectDirectory.file("mcp/server/gradle.properties").asFile.inputStream().use(::load)
}.getProperty("mcpServerVersion")

dependencies {
    implementation(project(":mcp:server"))

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mcp.kotlin.sdk.jvm)
    testImplementation(libs.kotlin.test)
}

application {
    applicationName = "hexana-vscode-mcp-server"
    mainClass = "org.jetbrains.hexana.mcp.vscode.server.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("installDist"))
    systemProperty(
        "hexana.vscodeServer.installDir",
        layout.buildDirectory.dir("install/hexana-vscode-mcp-server").get().asFile.absolutePath
    )
}
