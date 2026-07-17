plugins {
    `java-library`
    idea
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"

base {
    archivesName.set("cli-metadata")
}

val generatedCliMetadataDir = layout.projectDirectory.dir("src/main/gen")
val verifiedCliMetadataDir = layout.buildDirectory.dir("generator-verify/cli-metadata")
val discardedServerDir = layout.buildDirectory.dir("generator-discard/server")
val verifiedDiscardedServerDir = layout.buildDirectory.dir("generator-verify-discard/server")
val generatedCliToolsetFile = generatedCliMetadataDir.file("org/jetbrains/hexana/cli/metadata/HexanaCliToolset.kt")
val generatedCliProductsFile = generatedCliMetadataDir.file("org/jetbrains/hexana/cli/metadata/HexanaCliProducts.kt")
val verifiedCliToolsetFile = verifiedCliMetadataDir.map {
    it.file("org/jetbrains/hexana/cli/metadata/HexanaCliToolset.kt").asFile
}
val verifiedCliProductsFile = verifiedCliMetadataDir.map {
    it.file("org/jetbrains/hexana/cli/metadata/HexanaCliProducts.kt").asFile
}

idea {
    module {
        generatedSourceDirs.add(file("src/main/gen"))
    }
}

dependencies {
    api(project(":cli:runtime"))
}

tasks.register<JavaExec>("generateHexanaCliMetadata") {
    description = "Regenerates neutral generated CLI metadata into src/main/gen; the output is checked into git."
    group = "generation"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(discardedServerDir.get().asFile.absolutePath, generatedCliMetadataDir.asFile.absolutePath)
    })

    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.files(generatedCliToolsetFile, generatedCliProductsFile)

    dependsOn(":mcp:tools-generator:classes")
}

val regenerateHexanaCliMetadataForVerification = tasks.register<JavaExec>("regenerateHexanaCliMetadataForVerification") {
    description = "Re-emits neutral generated CLI metadata into build/ so verifyHexanaCliMetadataUpToDate can diff it."
    group = "verification"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(verifiedDiscardedServerDir.get().asFile.absolutePath, verifiedCliMetadataDir.get().asFile.absolutePath)
    })
    doFirst {
        verifiedDiscardedServerDir.get().asFile.deleteRecursively()
        verifiedCliMetadataDir.get().asFile.deleteRecursively()
    }

    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.files(verifiedCliToolsetFile, verifiedCliProductsFile)

    dependsOn(":mcp:tools-generator:classes")
}

tasks.register("verifyHexanaCliMetadataUpToDate") {
    description = "Fails if committed neutral CLI metadata drifts from the tools-generator DSL."
    group = "verification"
    dependsOn(regenerateHexanaCliMetadataForVerification)
    mustRunAfter(tasks.named("generateHexanaCliMetadata"))

    val generatedFiles = listOf(
        "HexanaCliToolset.kt" to (generatedCliToolsetFile.asFile to verifiedCliToolsetFile),
        "HexanaCliProducts.kt" to (generatedCliProductsFile.asFile to verifiedCliProductsFile),
    )

    inputs.files(generatedFiles.map { it.second.first })
    inputs.files(generatedFiles.map { it.second.second })

    doLast {
        generatedFiles.forEach { (fileName, files) ->
            val committed = files.first
            if (!committed.exists()) {
                error("Committed $fileName is missing at $committed. Run :cli:metadata:generateHexanaCliMetadata.")
            }
            val freshFile = files.second.get()
            if (committed.readText().replace("\r\n", "\n") != freshFile.readText().replace("\r\n", "\n")) {
                error(
                    "$fileName is out of date with the tools-generator DSL.\n" +
                        "  Committed:   ${committed.absolutePath}\n" +
                        "  Regenerated: ${freshFile.absolutePath}\n" +
                        "  Fix: ./gradlew :cli:metadata:generateHexanaCliMetadata, then commit src/main/gen/."
                )
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir("src/main/gen")
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyHexanaCliMetadataUpToDate"))
}
