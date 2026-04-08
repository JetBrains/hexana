import java.util.*

plugins {
    application
    alias(libs.plugins.jib)
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"
version = Properties().apply {
    project.file("gradle.properties").inputStream().use(::load)
}.getProperty("mcpServerVersion")

val sourceSets = extensions.getByType<SourceSetContainer>()
val mainSourceSet = sourceSets.named("main").get()
val testSourceSet = sourceSets.named("test").get()
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    java.srcDir("src/integrationTest/kotlin")
    compileClasspath += mainSourceSet.output + testSourceSet.runtimeClasspath
    runtimeClasspath += output + compileClasspath
}

configurations.named(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.named("testImplementation").get())
}

configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.named("testRuntimeOnly").get())
}

dependencies {
    implementation(project(":mcp:core"))
    implementation(libs.clikt)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.logback.classic)
    implementation(libs.mcp.kotlin.sdk.jvm)
    implementation(libs.ktor.server.cio.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(project(":graalwasm:wat2wasm"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)

    add(integrationTestSourceSet.implementationConfigurationName, project(":graalwasm:wat2wasm"))
    add(integrationTestSourceSet.implementationConfigurationName, libs.junit4)
    add(integrationTestSourceSet.implementationConfigurationName, libs.kotlin.test)
    add(integrationTestSourceSet.implementationConfigurationName, libs.ktor.client.cio.jvm)
}

application {
    mainClass = "org.jetbrains.hexana.mcp.server.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

val jibTargetImage = providers.gradleProperty("hexanaMcpImage")
    .orElse("example.invalid/hexana/mcp-server:$version")
val jibBaseImage = providers.gradleProperty("hexanaMcpBaseImage")
    .orElse("eclipse-temurin:21-jre")
val jibHttpPort = providers.gradleProperty("hexanaMcpHttpPort")
    .orElse("8080")
val jibHttpEndpoint = providers.gradleProperty("hexanaMcpHttpEndpoint")
    .orElse("/mcp")
val jibLogLevel = providers.gradleProperty("hexanaMcpLogLevel")
    .orElse("info")

jib {
    from {
        image = jibBaseImage.get()
    }
    to {
        image = jibTargetImage.get()
    }
    container {
        mainClass = application.mainClass.get()
        appRoot = "/opt/hexana-mcp"
        format = com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
        jvmFlags = listOf("-Dhexana.version=$version")
        args = listOf(
            "http",
            "--host", "0.0.0.0",
            "--port", jibHttpPort.get(),
            "--endpoint", jibHttpEndpoint.get(),
            "--allow-public-bind",
            "--log-level", jibLogLevel.get()
        )
        ports = listOf(jibHttpPort.get())
        labels = mapOf(
            "org.opencontainers.image.title" to "hexana-mcp-server",
            "org.opencontainers.image.version" to version.toString(),
            "org.opencontainers.image.description" to "Standalone Hexana MCP server for WebAssembly inspection."
        )
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version
        )
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs black-box HTTP and stdio integration tests against the installed standalone MCP server."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    shouldRunAfter(tasks.named("test"))
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging {
        showStandardStreams = true
        events("failed", "passed", "skipped")
    }
    systemProperty(
        "hexana.integration.installDir",
        layout.buildDirectory.dir("install/${application.applicationName}").get().asFile.absolutePath
    )
}

kotlin {
    jvmToolchain(21)
}
