plugins {
    application
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"
version = providers.gradleProperty("hexanaToolsVersion").get()

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

val generatedVersionSourceDir = layout.buildDirectory.dir("generated/sources/hexanaCliVersion/main/kotlin")
val generateHexanaCliScriptVersion by tasks.registering {
    val versionValue = version.toString()
    inputs.property("hexanaCliVersion", versionValue)
    outputs.dir(generatedVersionSourceDir)

    doLast {
        val escapedVersion = versionValue.replace("\\", "\\\\").replace("\"", "\\\"")
        val outputDir = generatedVersionSourceDir.get().asFile.resolve("org/jetbrains/hexana/cli/hexanacliscript")
        outputDir.mkdirs()
        outputDir.resolve("HexanaCliScriptVersion.kt").writeText(
            """
            package org.jetbrains.hexana.cli.hexanacliscript

            internal const val HEXANA_CLI_SCRIPT_VERSION: String = "$escapedVersion"
            """.trimIndent() + "\n"
        )
    }
}

dependencies {
    implementation(project(":cli:product"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)

    add(integrationTestSourceSet.implementationConfigurationName, libs.gson)
    add(integrationTestSourceSet.implementationConfigurationName, libs.junit4)
    add(integrationTestSourceSet.implementationConfigurationName, libs.kotlin.test)
}

application {
    applicationName = "hexana-cli-script"
    mainClass = "org.jetbrains.hexana.cli.hexanacliscript.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

graalvmNative {
    testSupport.set(false)
    toolchainDetection.set(true)

    binaries {
        named("main") {
            imageName.set("hexana-cli-script")
            mainClass.set(application.mainClass)
            fallback.set(false)
            buildArgs.addAll(
                "-Dhexana.version=$version",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin.DeprecationLevel",
            )
        }
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs black-box integration tests against the installed hexana-cli-script JVM application."
    group = "verification"
    dependsOn(tasks.named("installDist"))
    dependsOn(":cli:hexana-cli:installDist")
    shouldRunAfter(tasks.named("test"))
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    testLogging {
        showStandardStreams = true
        events("failed", "passed", "skipped")
    }
    systemProperty("hexana.cli.script.integration.installDir", layout.buildDirectory.dir("install/hexana-cli-script").get().asFile.absolutePath)
    systemProperty("hexana.cli.integration.installDir", project(":cli:hexana-cli").layout.buildDirectory.dir("install/hexana-cli").get().asFile.absolutePath)
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateHexanaCliScriptVersion)
    }
    jvmToolchain(21)
}
