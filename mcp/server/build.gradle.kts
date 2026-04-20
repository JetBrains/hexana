import java.util.*

plugins {
    application
    idea
    alias(libs.plugins.kotlin.jvm)
}

// Mark `src/main/gen` (generated HexanaToolDispatcher.kt) as generated so IntelliJ dims the file,
// keeps it out of default Find-in-Path scope, and warns on hand edits.
idea {
    module {
        generatedSourceDirs.add(file("src/main/gen"))
    }
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

val generatedToolDispatcherDir = layout.projectDirectory.dir("src/main/gen")
val verifiedToolDispatcherDir = layout.buildDirectory.dir("generator-verify/dispatcher")

tasks.register<JavaExec>("generateHexanaToolDispatcher") {
    description = "Regenerates HexanaToolDispatcher.kt into src/main/gen; the output is checked into git."
    group = "generation"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(generatedToolDispatcherDir.asFile.absolutePath)
    })

    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.file(generatedToolDispatcherDir.file("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt"))

    dependsOn(":mcp:tools-generator:classes")
}

val regenerateHexanaToolDispatcherForVerification = tasks.register<JavaExec>("regenerateHexanaToolDispatcherForVerification") {
    description = "Re-emits HexanaToolDispatcher.kt into build/ so verifyHexanaToolDispatcherUpToDate can diff it."
    group = "verification"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(verifiedToolDispatcherDir.get().asFile.absolutePath)
    })
    doFirst { verifiedToolDispatcherDir.get().asFile.deleteRecursively() }

    dependsOn(":mcp:tools-generator:classes")
}

tasks.register("verifyHexanaToolDispatcherUpToDate") {
    description = "Fails if src/main/gen/HexanaToolDispatcher.kt drifts from the tools-generator DSL."
    group = "verification"
    dependsOn(regenerateHexanaToolDispatcherForVerification)
    mustRunAfter(tasks.named("generateHexanaToolDispatcher"))

    val committed = generatedToolDispatcherDir.file("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt").asFile
    val fresh = verifiedToolDispatcherDir.map {
        it.file("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt").asFile
    }

    inputs.file(committed)
    inputs.dir(verifiedToolDispatcherDir)

    doLast {
        if (!committed.exists()) {
            error("Committed HexanaToolDispatcher.kt is missing at $committed. Run :mcp:server:generateHexanaToolDispatcher.")
        }
        val freshFile = fresh.get()
        if (committed.readText().replace("\r\n", "\n") != freshFile.readText().replace("\r\n", "\n")) {
            error(
                "HexanaToolDispatcher.kt is out of date with the tools-generator DSL.\n" +
                    "  Committed:   ${committed.absolutePath}\n" +
                    "  Regenerated: ${freshFile.absolutePath}\n" +
                    "  Fix: ./gradlew :mcp:server:generateHexanaToolDispatcher, then commit src/main/gen/."
            )
        }
    }
}

dependencies {
    implementation(project(":analytics"))
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

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version
        )
    }
}

val overrideClaudePluginDir = providers.gradleProperty("hexanaClaudePluginDir").orNull

tasks.register<Test>("integrationTest") {
    description = "Runs black-box HTTP and stdio integration tests against the installed standalone MCP server."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    if (overrideClaudePluginDir == null) {
        dependsOn(":mcp:assembleClaudePluginPackage")
    }
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
    systemProperty(
        "hexana.integration.claudePluginDir",
        overrideClaudePluginDir
            ?: project(":mcp").layout.buildDirectory.dir("packaging/claude-plugin").get().asFile.absolutePath
    )
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir("src/main/gen")
    }
}
