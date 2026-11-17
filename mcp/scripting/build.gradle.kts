plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    implementation(project(":binaryProvider"))
    implementation(project(":mcp:core"))
    implementation(project(":plugins-shared"))
    implementation(project(":wasmParser"))
    implementation(libs.bundles.graalvm.js)
    // Needed by HostObjectProxy to expose `binary.raw` reflectively.
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}

// --- Bundled scripting references -------------------------------------------
// Both plugins bundle the same two .d.ts files, and both dialogs link to them, so
// they are generated once from here. This module deliberately owns the task: it
// needs nothing but a plain JVM toolchain, whereas generating from :idea-plugin
// would make packaging the VS Code extension build against the IntelliJ platform.
// The output is checked into git; regenerate with
// `./gradlew :mcp:scripting:generateHexanaScriptingDts` and commit.

val scriptingDtsOutputDirs = listOf(
    rootProject.layout.projectDirectory.dir("idea-plugin/src/main/resources"),
    rootProject.layout.projectDirectory.dir("vscode-plugin"),
)

tasks.register<JavaExec>("generateHexanaScriptingDts") {
    description = "Generates the curated and raw scripting .d.ts references into " +
        "idea-plugin resources and vscode-plugin src."
    group = "generation"

    mainClass.set("org.jetbrains.hexana.mcp.scripting.GenerateScriptingDtsKt")
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add(CommandLineArgumentProvider {
        scriptingDtsOutputDirs.map { it.asFile.absolutePath }
    })
    outputs.files(
        scriptingDtsOutputDirs.flatMap { dir ->
            listOf(dir.file("hexana-scripting.d.ts"), dir.file("hexana-scripting-raw.d.ts"))
        }
    )

    dependsOn(tasks.named("classes"))
}
