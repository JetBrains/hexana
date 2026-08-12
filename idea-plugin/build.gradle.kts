import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("java")
    idea
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.changelog)
    // Compose compiler plugin from version catalog
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Mark `src/main/gen` (generated WIT PSI + generated HexanaToolset.kt) as generated so IntelliJ
// dims those files, keeps them out of default Find-in-Path scope, and warns on hand edits.
idea {
    module {
        generatedSourceDirs.add(file("src/main/gen"))
    }
}

apply(from = project.file("gradle/mcp-token-counter.gradle.kts"))

group = "org.jetbrains.hexana"

val baseVersion = project.property("pluginVersion") as String
val hexanaBuild = project.property("hexanaBuild")
val hexanaPublishPluginArchiveFile =
    providers.gradleProperty("hexanaPublishPluginArchiveFile").orNull?.takeIf { it.isNotBlank() }
val hexanaPublishChannel = providers.gradleProperty("hexanaPublishChannel").orNull?.takeIf { it.isNotBlank() }
val defaultIdeaType = ideLibs.versions.defaultIdeType.map(IntelliJPlatformType::valueOf)
version = when (hexanaBuild) {
    "release" -> {
        baseVersion
    }
    "nightly" -> {
        val buildNumber = project.findProperty("hexanaNightlyBuildNumber") as String
        "$baseVersion.$buildNumber-nightly"
    }
    else -> {
        "$baseVersion-SNAPSHOT"
    }
}

if (hexanaBuild == "nightly" || hexanaBuild == "release") {
    // set TeamCity build number to the published plugin version
    println("##teamcity[buildNumber '${version}']")
}

repositories {
    intellijPlatform {
        defaultRepositories()
        marketplace()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create(defaultIdeaType, ideLibs.versions.defaultIdeVersion) {
            useInstaller = false
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.JavaScript)
        testBundledPlugins("JavaScript", "com.intellij.css", "com.intellij.modules.json")

        bundledPlugins("com.intellij.mcpServer")
        // Java / Maven / Gradle / JavaScript ship only in IntelliJ IDEA. Other IDEs (CLion,
        // RustRover via -PtestIdeType) don't bundle them, and `bundledPlugins` has no
        // "optional" form — requesting them there fails intellijPlatformRuntimeClasspath
        // resolution. The matching integration sources are excluded from compilation below,
        // and plugin.xml keeps these as optional <depends> so the plugin still loads without
        // them. com.intellij.java is only a transitive prerequisite of the Maven/Gradle plugins.
        if (defaultIdeaType.get() == IntelliJPlatformType.IntellijIdea) {
            bundledPlugins("JavaScript", "com.intellij.java", "org.jetbrains.idea.maven", "com.intellij.gradle")
        }

        // TODO: whenever composeUI() will be updated, we can safely remove direct dependencies to dekstop runtime
        // see https://chat.example.invalid/archives/CHANNEL/p1763485226889799
        composeUI()
        zipSigner()
    }

    implementation(project(":analytics:api"))
    implementation(project(":analytics"))
    implementation(project(":encdec"))
    implementation(project(":wasmParser"))
    implementation(project(":parsing:javaBytecode"))
    implementation(project(":parsing:dex"))
    implementation(project(":parsing:zip"))
    implementation(project(":parsing:tar"))
    implementation(project(":jvm-jit"))
    implementation(project(":jvm-jit-jmh"))
    // Capstone-WASM is the project's default disassembler; the JIT-view
    // tab renders the native bytes for each BCI inline through it.
    implementation(project(":disassembly:core"))
    implementation(project(":disassembly:capstone-wasm"))
    implementation(project(":disassembly:llvm-objdump"))
    implementation(project(":hexanaDocument"))
    implementation(project(":binaryProvider"))
    implementation(project(":plugins-shared"))
    implementation(project(":mcp:core"))
    implementation(project(":graalwasm:wasmprinter"))
    implementation(project(":graalwasm:common"))
    implementation(project(":graalwasm:wat2wasm"))
    implementation(libs.kotlin.reflect)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.bundles.graalvm)
    implementation(libs.bundles.graalvm.js)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.ktor.client.websockets.jvm)

    // see https://chat.example.invalid/archives/CHANNEL/p1763485226889799
    compileOnly(compose.desktop.currentOs)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.bundles.graalvm)
    implementation(libs.gson)
}

changelog {
    version = baseVersion
    headerParserRegex = """(\d+[\.\d+]*)""".trimMargin().toRegex()
}

intellijPlatform {
    projectName = "hexana"

    publishing {
        token = providers.gradleProperty("publishToken")
        if (hexanaPublishChannel != null) {
            channels = listOf(hexanaPublishChannel)
        } else if (hexanaBuild == "nightly") {
            channels = listOf("nightly")
        }
    }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = ideLibs.versions.defaultSinceBuild.get()
        }
    }

    pluginVerification {
        ides {
            create(defaultIdeaType, ideLibs.versions.defaultIdeVersion) {
                useInstaller = false
            }
            // Cross-IDE checks for the "installable in any JetBrains IDE" goal: these IDEs
            // lack the Java/Maven/Gradle/JS plugins, so the verifier confirms the core loads
            // and no extension references a missing (Java-only) EP. Explicit entries — not
            // recommended(), which can silently resolve zero IDEs. ~2 GB download each; intended
            // for a dedicated CI compatibility slice rather than every local build.
            ide(IntelliJPlatformType.CLion, ideLibs.versions.defaultIdeVersion.get())
            ide(IntelliJPlatformType.WebStorm, ideLibs.versions.defaultIdeVersion.get())
            ide(IntelliJPlatformType.PyCharmProfessional, ideLibs.versions.defaultIdeVersion.get())
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<Test> {
        jvmArgs("-Dpolyglotimpl.DisableMultiReleaseCheck=true")
        classpath = classpath.filter { it.extension != "pom" }
    }
    // Enable remote debugger attach for runIde and debug against intellij sources.
    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
        //jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5007")
        jvmArgs("-Xmx8g", "-XX:HeapDumpPath=/tmp")
    }

    // When targeting an IDE other than IntelliJ IDEA (e.g. -PtestIdeType=CLion / RustRover),
    // the Java / Maven / Gradle / JavaScript plugins are not on the classpath (see
    // bundledPlugins above), so every source that compiles against them must be excluded.
    // All such code is consolidated into these packages/files; they are reached from core only
    // through optional <depends> (maven/gradle/js) and the `symbolNavigator` extension point
    // (javaLang/JavaSymbolNavigator), so the plugin still loads and degrades gracefully.
    // The whole javaLang package is Java-gated; the platform-only types that core needs were
    // moved out to chicory/ (ChicoryAotRegistry) and wasmcache/ (WasmBinaryDataCacheService).
    // NOTE: any NEW source touching a Java/Maven/Gradle/JS plugin API must live under one of
    // these, or the CLion/RustRover build will break.
    if (defaultIdeaType.get() != IntelliJPlatformType.IntellijIdea) {
        named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
            exclude(
                "**/hexana/javaLang/**",
                "**/hexana/maven/**",
                "**/hexana/gradle/**",
                "**/hexana/javascript/WasmFrameworkIndexingHandler.kt",
                "**/hexana/jit/JitviewRunConfigurationExtension.kt",
            )
        }
    }

    named<SignPluginTask>("signPlugin") {
        hexanaPublishPluginArchiveFile?.let {
            archiveFile.set(project.file(it))
        }
    }

    named<PublishPluginTask>("publishPlugin") {
        hexanaPublishPluginArchiveFile?.let {
            val explicitPublishChannel = hexanaPublishChannel
                ?: throw GradleException(
                    "hexanaPublishChannel must be set when hexanaPublishPluginArchiveFile is set.",
                )
            val expectedBuild = when (explicitPublishChannel) {
                "default" -> "release"
                "nightly" -> "nightly"
                else -> throw GradleException(
                    "Unsupported hexanaPublishChannel '$explicitPublishChannel' when hexanaPublishPluginArchiveFile " +
                            "is set. Expected 'default' or 'nightly'.",
                )
            }
            val actualBuild = hexanaBuild.toString()
            if (actualBuild != expectedBuild) {
                throw GradleException(
                    "hexanaBuild must be '$expectedBuild' when publishing a prebuilt archive to " +
                            "'$explicitPublishChannel', but was '$actualBuild'.",
                )
            }
        }
    }

    patchPluginXml {
        changeNotes = provider {
            val item = when (hexanaBuild) {
                "release" -> changelog.get(version as String)
                else -> changelog.getUnreleased()
            }
            changelog.renderItem(
                item
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML
            )
        }
    }

    register<RunIdeTask>("buildEventsScheme") {
        group = "verification"
        description = "Generates Hexana FUS scheme artifact using the built-in IDE headless command"

        val rawOutputFile = layout.buildDirectory.file("fus/raw-scheme.txt")
        val outputFile = layout.buildDirectory.file("fus/scheme.txt")
        val errorsFile = layout.buildDirectory.file("fus/scheme.errors.txt")
        val pluginsFile = layout.buildDirectory.file("fus/plugins.txt")
        applySandboxFrom(project.tasks.named<PrepareSandboxTask>("prepareSandbox"))
        splitMode.set(false)
        splitModeTarget.set(SplitModeAware.SplitModeTarget.BACKEND)
        outputs.file(outputFile)
        environment("FUS_DESCRIPTION_REGISTRATION_ENABLED", "true")
        environment("FUS_COLLECTOR_FILENAME_ENABLED", "true")
        // oldest version we can encounter
        environment("IDEA_BUILD_NUMBER", "243")
        args(
            "buildEventsScheme",
            "--pluginId=org.jetbrains.hexana",
            "--outputFile=${rawOutputFile.get().asFile.absolutePath}",
            "--errorsFile=${errorsFile.get().asFile.absolutePath}",
            "--pluginsFile=${pluginsFile.get().asFile.absolutePath}",
        )
        doLast {
            @Suppress("UNCHECKED_CAST")
            val rawScheme = JsonSlurper().parse(rawOutputFile.get().asFile) as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val groups = rawScheme["scheme"] as List<Map<String, Any?>>
            rawScheme["scheme"] = groups.filter { group ->
                val pluginId = (group["plugin"] as? Map<*, *>)?.get("id") as? String
                val groupId = group["id"] as? String
                pluginId == "org.jetbrains.hexana" && groupId == "org.jetbrains.hexana"
            }
            outputFile.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(rawScheme)))
        }
    }
}

val generatedToolsetDir = layout.projectDirectory.dir("src/main/gen")
val verifiedToolsetDir = layout.buildDirectory.dir("generator-verify/toolset")
val feedbackFormContractFile = layout.buildDirectory.file("feedback/hexana-feedback-form-contract.json")

tasks.register<JavaExec>("generateHexanaToolset") {
    description = "Regenerates HexanaToolset.kt into src/main/gen; the output is checked into git."
    group = "generation"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(generatedToolsetDir.asFile.absolutePath)
    })

    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.file(generatedToolsetDir.file("org/jetbrains/hexana/mcp/HexanaToolset.kt"))

    dependsOn(":mcp:tools-generator:classes")
}

tasks.register<JavaExec>("generateHexanaFeedbackFormContract") {
    description = "Generates the Hexana feedback form contract JSON for the feedback backend."
    group = "generation"

    mainClass.set("org.jetbrains.hexana.feedback.HexanaFeedbackContractKt")
    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(feedbackFormContractFile.get().asFile.absolutePath)
    })
    outputs.file(feedbackFormContractFile)

    dependsOn(tasks.named("classes"))
}

val regenerateHexanaToolsetForVerification = tasks.register<JavaExec>("regenerateHexanaToolsetForVerification") {
    description = "Re-emits HexanaToolset.kt into build/ so verifyHexanaToolsetUpToDate can diff it."
    group = "verification"

    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(verifiedToolsetDir.get().asFile.absolutePath)
    })
    doFirst { verifiedToolsetDir.get().asFile.deleteRecursively() }

    dependsOn(":mcp:tools-generator:classes")
}

tasks.register("verifyHexanaToolsetUpToDate") {
    description = "Fails if src/main/gen/HexanaToolset.kt drifts from the tools-generator DSL."
    group = "verification"
    dependsOn(regenerateHexanaToolsetForVerification)
    mustRunAfter(tasks.named("generateHexanaToolset"))

    val committed = generatedToolsetDir.file("org/jetbrains/hexana/mcp/HexanaToolset.kt").asFile
    val fresh = verifiedToolsetDir.map { it.file("org/jetbrains/hexana/mcp/HexanaToolset.kt").asFile }

    inputs.file(committed)
    inputs.dir(verifiedToolsetDir)

    doLast {
        if (!committed.exists()) {
            error("Committed HexanaToolset.kt is missing at $committed. Run :idea-plugin:generateHexanaToolset.")
        }
        val freshFile = fresh.get()
        if (committed.readText().replace("\r\n", "\n") != freshFile.readText().replace("\r\n", "\n")) {
            error(
                "HexanaToolset.kt is out of date with the tools-generator DSL.\n" +
                    "  Committed:   ${committed.absolutePath}\n" +
                    "  Regenerated: ${freshFile.absolutePath}\n" +
                    "  Fix: ./gradlew :idea-plugin:generateHexanaToolset, then commit src/main/gen/."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyHexanaToolsetUpToDate"))
}

tasks.register<JavaExec>("generateHexanaScriptingDts") {
    description = "Generates hexana-scripting.d.ts into idea-plugin resources and vscode-plugin src."
    group = "generation"

    mainClass.set("org.jetbrains.hexana.ui.panel.GenerateScriptingDtsKt")
    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            layout.projectDirectory.dir("src/main/resources").asFile.absolutePath,
            rootProject.layout.projectDirectory.dir("vscode-plugin").asFile.absolutePath,
        )
    })
    outputs.files(
        layout.projectDirectory.file("src/main/resources/hexana-scripting.d.ts"),
        rootProject.layout.projectDirectory.file("vscode-plugin/hexana-scripting.d.ts"),
    )

    dependsOn(tasks.named("classes"))
}

tasks.register("verifyReleaseWhatsNew") {
    description = "Fails release builds when the bundled What's New page for pluginVersion is missing or incomplete."
    group = "verification"

    val pluginVersion = baseVersion
    val releaseVersion = Regex("""^\s*(\d+)\.(\d+)""")
        .find(pluginVersion)
        ?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
        ?: throw GradleException("pluginVersion must start with <major>.<minor>, got '$pluginVersion'.")
    val whatsNewDirectory = layout.projectDirectory.dir("src/main/resources/whatsnew/$releaseVersion")
    val indexFile = whatsNewDirectory.file("index.html")
    val styleFile = whatsNewDirectory.file("style.css")
    val manifestFile = whatsNewDirectory.file("manifest.txt")
    val sourceFile = whatsNewDirectory.file("source.json")

    inputs.property("releaseVersion", releaseVersion)
    inputs.files(project.fileTree(whatsNewDirectory.asFile))
        .withPropertyName("whatsNewFiles")

    doLast {
        fun fail(message: String): Nothing = throw GradleException(
            "Invalid What's New resources for pluginVersion=$pluginVersion (resource version $releaseVersion): $message"
        )

        val root = whatsNewDirectory.asFile
        if (!root.isDirectory) {
            fail("missing directory ${root.relativeTo(projectDir)}")
        }

        val requiredFiles = listOf(indexFile, styleFile, manifestFile, sourceFile).map { it.asFile }
        val missingRequiredFiles = requiredFiles.filterNot { it.isFile }
        if (missingRequiredFiles.isNotEmpty()) {
            fail("missing required files: ${missingRequiredFiles.joinToString { it.relativeTo(projectDir).path }}")
        }

        val manifestEntries = manifestFile.asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val manifestEntrySet = manifestEntries.toSet()
        if (manifestEntries.size != manifestEntrySet.size) {
            fail("manifest.txt contains duplicate entries.")
        }
        val requiredManifestEntries = setOf("index.html", "style.css", "source.json")
        val missingManifestEntries = requiredManifestEntries - manifestEntrySet
        if (missingManifestEntries.isNotEmpty()) {
            fail("manifest.txt is missing required entries: ${missingManifestEntries.joinToString()}")
        }

        val missingManifestFiles = manifestEntrySet
            .map { it to root.resolve(it) }
            .filterNot { (_, file) -> file.isFile }
            .map { (entry, _) -> entry }
        if (missingManifestFiles.isNotEmpty()) {
            fail("manifest.txt references missing files: ${missingManifestFiles.joinToString()}")
        }

        fun localReferences(text: String): Set<String> {
            val htmlReferences = Regex("""\b(?:href|src)="([^"]+)"""")
                .findAll(text)
                .map { it.groupValues[1] }
            val cssReferences = Regex("""url\((['"]?)([^)'"]+)\1\)""")
                .findAll(text)
                .map { it.groupValues[2] }
            return (htmlReferences + cssReferences)
                .filterNot { reference ->
                    reference.startsWith("http://") ||
                        reference.startsWith("https://") ||
                        reference.startsWith("mailto:") ||
                        reference.startsWith("#") ||
                        reference.startsWith("data:")
                }
                .toSet()
        }

        val referencedAssets = localReferences(indexFile.asFile.readText()) + localReferences(styleFile.asFile.readText())
        val missingReferencedAssets = referencedAssets - manifestEntrySet
        if (missingReferencedAssets.isNotEmpty()) {
            fail("local HTML/CSS references are missing from manifest.txt: ${missingReferencedAssets.joinToString()}")
        }

        val sourceJson = JsonSlurper().parse(sourceFile.asFile) as Map<*, *>
        val sourceVersion = sourceJson["version"] as? String
        if (sourceVersion != releaseVersion) {
            fail("source.json version is '$sourceVersion', expected '$releaseVersion'.")
        }
        val clusters = sourceJson["clusters"] as? List<*>
        if (clusters.isNullOrEmpty()) {
            fail("source.json must contain at least one generated feature cluster.")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Exclude standard kotlin coroutines from runtime / testRuntime since IDEA is using its own implementation
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
}

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir("src/main/gen")
        }
        kotlin {
            // Packaged for Driver @Remote UI tests; intentionally not registered as a plugin extension.
            srcDir("src/ui-tests-framework/kotlin")
        }
    }
}
