plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    implementation(libs.kotlinpoet.jvm)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt"
}

val runToolsetGenerator by tasks.registering(JavaExec::class) {
    description = "Runs the idea-plugin toolset generator; pass the output root as a single argument."
    group = "generation"
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt")
}

val generateHexanaToolCatalogReport by tasks.registering(JavaExec::class) {
    description = "Writes deterministic resolved tool catalog JSON and text reports under this module's build directory."
    group = "generation"
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.ToolCatalogReportMainKt")
    args(layout.buildDirectory.dir("reports/hexana-tool-catalog").get().asFile.absolutePath)
    outputs.dir(layout.buildDirectory.dir("reports/hexana-tool-catalog"))
}

val generateHexanaToolCompatibilityDiff by tasks.registering(JavaExec::class) {
    description = "Writes a deterministic compatibility diff for -PhexanaToolCompatibilityBeforeReport and -PhexanaToolCompatibilityAfterReport."
    group = "generation"
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.ToolCompatibilityDiffMainKt")
    val beforeReport = providers.gradleProperty("hexanaToolCompatibilityBeforeReport")
    val afterReport = providers.gradleProperty("hexanaToolCompatibilityAfterReport")
    inputs.files(beforeReport.map(::file), afterReport.map(::file))
    outputs.dir(layout.buildDirectory.dir("reports/hexana-tool-compatibility"))
    doFirst {
        args(
            beforeReport.orNull ?: error("Set -PhexanaToolCompatibilityBeforeReport=<saved resolved-tool-catalog.json>."),
            afterReport.orNull ?: error("Set -PhexanaToolCompatibilityAfterReport=<new resolved-tool-catalog.json>."),
            layout.buildDirectory.dir("reports/hexana-tool-compatibility").get().asFile.absolutePath,
        )
    }
}

kotlin {
    jvmToolchain(21)
}
