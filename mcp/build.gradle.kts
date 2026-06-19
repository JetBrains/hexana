import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.net.InetAddress
import java.util.*
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

val serverPropertiesFile = layout.projectDirectory.file("server/gradle.properties").asFile
require(serverPropertiesFile.isFile) {
    "Missing MCP server properties file: ${serverPropertiesFile.absolutePath}"
}

val serverProperties = Properties().apply {
    serverPropertiesFile.inputStream().use(::load)
}
val mcpServerVersion = serverProperties.getProperty("mcpServerVersion")
    ?.takeIf { it.isNotBlank() }
    ?: error("Missing `mcpServerVersion` in ${serverPropertiesFile.absolutePath}")

plugins {
    application
    alias(libs.plugins.jib)
}

group = "org.jetbrains.hexana"
version = mcpServerVersion

dependencies {
    implementation(project(":mcp:server"))
}

application {
    mainClass = "org.jetbrains.hexana.mcp.server.MainKt"
    applicationDefaultJvmArgs = listOf("-Dhexana.version=$version")
}

val jibTargetImage = providers.gradleProperty("hexanaMcpImage")
    .orElse("localhost/hexana/mcp-server:$version")
val jibBaseImage = providers.gradleProperty("hexanaMcpBaseImage")
    .orElse("eclipse-temurin:21-jre")
val jibHttpPort = providers.gradleProperty("hexanaMcpHttpPort")
    .orElse("8080")
val jibHttpEndpoint = providers.gradleProperty("hexanaMcpHttpEndpoint")
    .orElse("/mcp")
val jibLogLevel = providers.gradleProperty("hexanaMcpLogLevel")
    .orElse("info")
val jibHost = providers.gradleProperty("hexanaMcpHost")
    .orElse("0.0.0.0")
val jibAllowPublicBind = providers.gradleProperty("hexanaMcpAllowPublicBind")
    .orElse("true")
    .map(::parseRequiredBoolean)
val standaloneInstallDir = project(":mcp:server").layout.buildDirectory.dir("install/server")
val plainPackagingDir = layout.buildDirectory.dir("packaging-plain")
val obfuscatedPackagingDir = layout.buildDirectory.dir("packaging")
val nativePackagingDir = layout.buildDirectory.dir("packaging-native")
val obfuscatedInstallDir = project(":mcp").layout.buildDirectory.dir("install/server-r8")
val nativeServerExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "hexana-mcp.exe"
} else {
    "hexana-mcp"
}
val nativeHostPlatformName = currentNativePlatformName()
val nativeReleasePlatforms = listOf("macos-arm64", "linux-x64", "windows-x64")
val nativeServerExecutable = project(":mcp:server").layout.buildDirectory.file("native/nativeCompile/$nativeServerExecutableName")
val nativeDistributionDir = layout.buildDirectory.dir("distributions-native")
val publicMarketplaceDir = layout.projectDirectory.dir("out/teamcity/public-marketplace")
val pluginLicenseFile = layout.projectDirectory.file("packaging/LICENSE.txt")
val thirdPartyNoticesDir = layout.buildDirectory.dir("generated/third-party-notices")
val thirdPartyNoticesFileName = "THIRD-PARTY-NOTICES.md"
val r8RulesFile = layout.projectDirectory.file("packaging/r8-rules.pro")
val r8OwnedJarNames = setOf(
    "analytics-jvm.jar",
    "binaryProvider-jvm.jar",
    "common-jvm.jar",
    "core.jar",
    "dwarf-jvm.jar",
    "elf-jvm.jar",
    "encdec-jvm.jar",
    "hexanaDocument-jvm.jar",
    "macho-jvm.jar",
    "pe-jvm.jar",
    "plugins-shared-jvm.jar",
    "server-$mcpServerVersion.jar",
    "wasmParser-jvm.jar",
)

val r8Tool by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

require(jibAllowPublicBind.get() || jibHost.get().isLoopbackHost()) {
    "Non-loopback `hexanaMcpHost` requires `-PhexanaMcpAllowPublicBind=true`."
}

data class ThirdPartyLicense(val name: String, val url: String?)
data class ThirdPartyComponentNotice(
    val coordinates: String,
    val name: String?,
    val projectUrl: String?,
    val licenses: List<ThirdPartyLicense>,
    val parentCoordinates: String? = null,
)

fun directChildElement(element: org.w3c.dom.Element, tagName: String): org.w3c.dom.Element? {
    val children = element.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is org.w3c.dom.Element && child.tagName == tagName) {
            return child
        }
    }
    return null
}

fun directChildText(element: org.w3c.dom.Element, tagName: String): String? {
    return directChildElement(element, tagName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

fun parseThirdPartyPom(coordinates: String, pomFile: File): ThirdPartyComponentNotice {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isExpandEntityReferences = false
        isNamespaceAware = false
        isValidating = false
    }
    val document = documentBuilderFactory.newDocumentBuilder().parse(pomFile)
    val project = document.documentElement
    val licenseNodes = document.getElementsByTagName("license")
    val licenses = buildList {
        for (index in 0 until licenseNodes.length) {
            val license = licenseNodes.item(index) as? org.w3c.dom.Element ?: continue
            val name = directChildText(license, "name") ?: continue
            add(ThirdPartyLicense(name, directChildText(license, "url")))
        }
    }.distinctBy { it.name to it.url }
    val parentCoordinates = directChildElement(project, "parent")?.let { parent ->
        val group = directChildText(parent, "groupId")
        val artifact = directChildText(parent, "artifactId")
        val version = directChildText(parent, "version")
        if (group != null && artifact != null && version != null) {
            "$group:$artifact:$version"
        } else {
            null
        }
    }

    return ThirdPartyComponentNotice(
        coordinates = coordinates,
        name = directChildText(project, "name"),
        projectUrl = directChildText(project, "url"),
        licenses = licenses,
        parentCoordinates = parentCoordinates,
    )
}

fun bundledArtifactLicenseFiles(artifactFile: File): List<String> {
    if (!artifactFile.isFile || artifactFile.extension != "jar") {
        return emptyList()
    }
    val licensePath = Regex("""(?i)(^|/)(META-INF/)?(LICENSE|NOTICE|COPYING)(\..*)?$""")
    return JarFile(artifactFile).use { jar ->
        jar.entries().asSequence()
            .map { it.name }
            .filter { licensePath.matches(it) }
            .distinct()
            .sorted()
            .toList()
    }
}

fun renderThirdPartyNotices(components: List<ThirdPartyComponentNotice>): String = buildString {
    appendLine("# Third-Party Notices")
    appendLine()
    appendLine("This Hexana distribution includes third-party software components.")
    appendLine("The list below is generated from the Gradle runtime artifacts used for the bundled MCP server.")
    appendLine("Each component remains licensed under its own license terms.")
    appendLine()
    appendLine("Generated by: `./gradlew :mcp:generateThirdPartyNotices`")
    appendLine()
    components.forEach { component ->
        appendLine("## `${component.coordinates}`")
        component.name?.let { appendLine("- Name: $it") }
        component.projectUrl?.let { appendLine("- Project: $it") }
        if (component.licenses.isEmpty()) {
            appendLine("- License: not declared in Maven POM")
        } else {
            component.licenses.forEach { license ->
                if (license.url.isNullOrBlank()) {
                    appendLine("- License: ${license.name}")
                } else {
                    appendLine("- License: ${license.name} (${license.url})")
                }
            }
        }
        appendLine()
    }
}.trimEnd() + "\n"

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
        args = buildList {
            add("http")
            add("--host")
            add(jibHost.get())
            add("--port")
            add(jibHttpPort.get())
            add("--endpoint")
            add(jibHttpEndpoint.get())
            if (jibAllowPublicBind.get()) {
                add("--allow-public-bind")
            }
            add("--log-level")
            add(jibLogLevel.get())
        }
        ports = listOf(jibHttpPort.get())
        labels = mapOf(
            "org.opencontainers.image.title" to "hexana-mcp-server",
            "org.opencontainers.image.version" to version.toString(),
            "org.opencontainers.image.description" to "Standalone Hexana MCP server for WebAssembly inspection."
        )
    }
}

fun registerPackagingSync(
    taskName: String,
    packagingDirName: String,
    pluginManifestPath: String,
    runtimeInstallDir: Provider<Directory>,
    runtimeTask: Any,
    outputRootDir: Provider<Directory>,
    rewriteMcpJson: Boolean = false,
    nativeExecutableFile: Provider<RegularFile>? = null,
    nativeTask: Any? = null,
) = tasks.register<Sync>(taskName) {
    dependsOn(runtimeTask, generateThirdPartyNotices)
    if (nativeExecutableFile != null) {
        dependsOn(nativeTask ?: ":mcp:server:nativeCompile")
    }
    from(layout.projectDirectory.dir("packaging/$packagingDirName")) {
        filesMatching(pluginManifestPath) {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(pluginLicenseFile)
    from(generateThirdPartyNotices)
    from(runtimeInstallDir) {
        into("server")
    }
    from(pluginLicenseFile) {
        into("server")
    }
    from(generateThirdPartyNotices) {
        into("server")
    }
    if (nativeExecutableFile != null) {
        from(nativeExecutableFile) {
            into("native/$nativeHostPlatformName")
            rename { nativeServerExecutableName }
            filesMatching(nativeServerExecutableName) {
                if (!nativeServerExecutableName.endsWith(".exe")) {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
    }
    into(outputRootDir.map { it.dir(packagingDirName) })
    if (rewriteMcpJson) {
        doLast {
            val launcherPath = destinationDir.resolve("bin/hexana-mcp").absolutePath
            destinationDir.resolve(".mcp.json").writeText(renderMcpConfig(launcherPath))
        }
    }
}

dependencies {
    r8Tool(libs.r8)
}

val generateThirdPartyNotices = tasks.register("generateThirdPartyNotices") {
    group = "distribution"
    description = "Generate third-party notices for the public MCP server/plugin distribution."

    val runtimeClasspath = configurations.named("runtimeClasspath")
    inputs.files(runtimeClasspath)
    outputs.file(thirdPartyNoticesDir.map { it.file(thirdPartyNoticesFileName) })

    doLast {
        val runtimeArtifacts = runtimeClasspath.get()
            .incoming
            .artifacts
            .artifacts
        val moduleIds = runtimeArtifacts
            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .distinctBy { listOf(it.group, it.module, it.version) }
            .sortedWith(compareBy<ModuleComponentIdentifier> { it.group }.thenBy { it.module }.thenBy { it.version })
        val artifactFileByModuleId = runtimeArtifacts
            .mapNotNull { artifact ->
                val moduleId = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                moduleId to artifact.file
            }
            .toMap()

        val pomResults = dependencies
            .createArtifactResolutionQuery()
            .forComponents(moduleIds)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute()
            .resolvedComponents
            .associate { component ->
                component.id to component.getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<ResolvedArtifactResult>()
                    .singleOrNull()
                    ?.file
            }

        fun resolvePomFile(coordinates: String): File? = runCatching {
            configurations.detachedConfiguration(dependencies.create("$coordinates@pom")).apply {
                isTransitive = false
            }.singleFile
        }.getOrNull()

        fun noticeWithFallbacks(notice: ThirdPartyComponentNotice, artifactFile: File?): ThirdPartyComponentNotice {
            if (notice.licenses.isNotEmpty()) {
                return notice
            }
            val parentCoordinates = notice.parentCoordinates
            if (parentCoordinates != null) {
                val parentPom = resolvePomFile(parentCoordinates)
                if (parentPom != null) {
                    val parentNotice = parseThirdPartyPom(parentCoordinates, parentPom)
                    if (parentNotice.licenses.isNotEmpty()) {
                        return notice.copy(licenses = parentNotice.licenses)
                    }
                }
            }

            val artifactLicenseFiles = artifactFile?.let(::bundledArtifactLicenseFiles).orEmpty()
            return if (artifactLicenseFiles.isEmpty()) {
                notice
            } else {
                notice.copy(
                    licenses = artifactLicenseFiles.map { path ->
                        ThirdPartyLicense("See bundled artifact file `$path`", null)
                    }
                )
            }
        }

        val notices = moduleIds.map { moduleId ->
            val coordinates = "${moduleId.group}:${moduleId.module}:${moduleId.version}"
            val pomFile = pomResults[moduleId]
            if (pomFile != null) {
                noticeWithFallbacks(parseThirdPartyPom(coordinates, pomFile), artifactFileByModuleId[moduleId])
            } else {
                ThirdPartyComponentNotice(coordinates, name = null, projectUrl = null, licenses = emptyList())
            }
        }

        val outputFile = thirdPartyNoticesDir.get().file(thirdPartyNoticesFileName).asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(renderThirdPartyNotices(notices))
    }
}

val copyMcpServerRuntimeForR8 = tasks.register<Sync>("copyMcpServerRuntimeForR8") {
    group = "distribution"
    description = "Copies the standalone MCP server runtime before R8 obfuscation."
    dependsOn(":mcp:server:installDist")
    from(standaloneInstallDir)
    into(obfuscatedInstallDir)
}

val r8ProgramJarFiles = r8OwnedJarNames.map { obfuscatedInstallDir.map { dir -> dir.file("lib/$it") } }
val r8LibraryJarFiles = obfuscatedInstallDir.map { dir ->
    dir.dir("lib").asFileTree.matching {
        exclude(r8OwnedJarNames.map { name -> name })
    }
}
val r8OutputDir = layout.buildDirectory.dir("r8/output")
val r8JarDir = layout.buildDirectory.dir("r8/jars")
val r8MappingFile = layout.buildDirectory.file("r8/mapping.txt")
val obfuscatedRuntimeJarName = "hexana-runtime-obfuscated.jar"

val obfuscateMcpServerRuntime = tasks.register<JavaExec>("obfuscateMcpServerRuntime") {
    group = "distribution"
    description = "Runs R8 over Hexana-owned MCP runtime JARs for public plugin ZIPs."
    dependsOn(copyMcpServerRuntimeForR8)

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    classpath = r8Tool
    mainClass.set("com.android.tools.r8.R8")

    inputs.file(r8RulesFile)
    inputs.files(r8ProgramJarFiles)
    inputs.files(r8LibraryJarFiles)
    outputs.dir(r8OutputDir)
    outputs.file(r8MappingFile)

    doFirst {
        r8OutputDir.get().asFile.deleteRecursively()
        r8OutputDir.get().asFile.mkdirs()
        r8MappingFile.get().asFile.parentFile.mkdirs()
        args(
            "--classfile",
            "--release",
            "--pg-conf",
            r8RulesFile.asFile.absolutePath,
            "--output",
            r8OutputDir.get().asFile.absolutePath,
            "--pg-map-output",
            r8MappingFile.get().asFile.absolutePath,
            "--lib",
            javaLauncher.get().metadata.installationPath.asFile.absolutePath,
        )
        r8LibraryJarFiles.get().files.sortedBy { it.name }.forEach { jar ->
            args("--lib", jar.absolutePath)
        }
        r8ProgramJarFiles.forEach { jar ->
            args(jar.get().asFile.absolutePath)
        }
    }

}

val jarObfuscatedMcpRuntime = tasks.register<Jar>("jarObfuscatedMcpRuntime") {
    group = "distribution"
    description = "Packs R8 output into a single obfuscated Hexana runtime JAR."
    dependsOn(obfuscateMcpServerRuntime)
    from(r8OutputDir)
    archiveFileName.set(obfuscatedRuntimeJarName)
    destinationDirectory.set(r8JarDir)
}

val installObfuscatedMcpServerRuntime = tasks.register("installObfuscatedMcpServerRuntime") {
    group = "distribution"
    description = "Installs the standalone MCP server runtime with obfuscated Hexana-owned classes."
    dependsOn(copyMcpServerRuntimeForR8, jarObfuscatedMcpRuntime)
    inputs.file(jarObfuscatedMcpRuntime.flatMap { it.archiveFile })
    inputs.files(obfuscatedInstallDir.map { dir -> r8OwnedJarNames.map { dir.file("lib/$it") } })
    outputs.dir(obfuscatedInstallDir)

    doLast {
        val installDir = obfuscatedInstallDir.get().asFile
        r8OwnedJarNames.forEach { jarName ->
            installDir.resolve("lib/$jarName").delete()
        }
        jarObfuscatedMcpRuntime.get().archiveFile.get().asFile.copyTo(
            installDir.resolve("lib/$obfuscatedRuntimeJarName"),
            overwrite = true,
        )

        installDir.resolve("bin/server").takeIf { it.isFile }?.let { script ->
            script.writeText(
                Regex("(?m)^CLASSPATH=.*$").replace(script.readText()) {
                    "CLASSPATH=\$APP_HOME/lib/*"
                }
            )
        }
        installDir.resolve("bin/server.bat").takeIf { it.isFile }?.let { script ->
            script.writeText(
                Regex("(?m)^set CLASSPATH=.*$").replace(script.readText()) {
                    "set CLASSPATH=%APP_HOME%\\\\lib\\\\*"
                }
            )
        }
    }
}

val zipObfuscatedMcpServer = tasks.register<Zip>("zipObfuscatedMcpServer") {
    group = "distribution"
    description = "Zips the R8-obfuscated standalone MCP server runtime into mcp/build/distributions."
    dependsOn(installObfuscatedMcpServerRuntime, generateThirdPartyNotices)
    from(obfuscatedInstallDir) {
        into("server")
        filesMatching("bin/server") {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(pluginLicenseFile) {
        into("server")
    }
    from(generateThirdPartyNotices) {
        into("server")
    }
    archiveBaseName.set("server")
    archiveVersion.set(mcpServerVersion)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

val assemblePlainClaudePluginPackage = registerPackagingSync(
    taskName = "assemblePlainClaudePluginPackage",
    packagingDirName = "claude-plugin",
    pluginManifestPath = ".claude-plugin/plugin.json",
    runtimeInstallDir = standaloneInstallDir,
    runtimeTask = ":mcp:server:installDist",
    outputRootDir = plainPackagingDir,
    rewriteMcpJson = false,
)

val assemblePlainCodexPluginPackage = registerPackagingSync(
    taskName = "assemblePlainCodexPluginPackage",
    packagingDirName = "codex-plugin",
    pluginManifestPath = ".codex-plugin/plugin.json",
    runtimeInstallDir = standaloneInstallDir,
    runtimeTask = ":mcp:server:installDist",
    outputRootDir = plainPackagingDir,
)

val assembleClaudePluginPackage = registerPackagingSync(
    taskName = "assembleClaudePluginPackage",
    packagingDirName = "claude-plugin",
    pluginManifestPath = ".claude-plugin/plugin.json",
    runtimeInstallDir = obfuscatedInstallDir,
    runtimeTask = "installObfuscatedMcpServerRuntime",
    outputRootDir = obfuscatedPackagingDir,
    rewriteMcpJson = false,
)

val assembleCodexPluginPackage = registerPackagingSync(
    taskName = "assembleCodexPluginPackage",
    packagingDirName = "codex-plugin",
    pluginManifestPath = ".codex-plugin/plugin.json",
    runtimeInstallDir = obfuscatedInstallDir,
    runtimeTask = "installObfuscatedMcpServerRuntime",
    outputRootDir = obfuscatedPackagingDir,
)

val assembleNativeClaudePluginPackage = registerPackagingSync(
    taskName = "assembleNativeClaudePluginPackage",
    packagingDirName = "claude-plugin",
    pluginManifestPath = ".claude-plugin/plugin.json",
    runtimeInstallDir = obfuscatedInstallDir,
    runtimeTask = "installObfuscatedMcpServerRuntime",
    outputRootDir = nativePackagingDir,
    rewriteMcpJson = false,
    nativeExecutableFile = nativeServerExecutable,
    nativeTask = ":mcp:server:nativeCompile",
)

val assembleNativeCodexPluginPackage = registerPackagingSync(
    taskName = "assembleNativeCodexPluginPackage",
    packagingDirName = "codex-plugin",
    pluginManifestPath = ".codex-plugin/plugin.json",
    runtimeInstallDir = obfuscatedInstallDir,
    runtimeTask = "installObfuscatedMcpServerRuntime",
    outputRootDir = nativePackagingDir,
    nativeExecutableFile = nativeServerExecutable,
    nativeTask = ":mcp:server:nativeCompile",
)

tasks.register<Sync>("installRepoLocalClaudePlugin") {
    group = "distribution"
    description = "Install the assembled Claude plugin into the repo-local plugins/hexana path for /plugin testing."
    dependsOn(":mcp:server:installDist", generateThirdPartyNotices)
    from(layout.projectDirectory.dir("packaging/claude-plugin")) {
        filesMatching(".claude-plugin/plugin.json") {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(pluginLicenseFile)
    from(generateThirdPartyNotices)
    from(standaloneInstallDir) {
        into("server")
    }
    from(pluginLicenseFile) {
        into("server")
    }
    from(generateThirdPartyNotices) {
        into("server")
    }
    into(rootProject.layout.projectDirectory.dir("plugins/hexana"))
}

tasks.register<Sync>("installRepoLocalCodexPlugin") {
    group = "distribution"
    description = "Install the assembled Codex plugin into the repo-local plugins/hexana path for /plugins testing."
    dependsOn(":mcp:server:installDist", generateThirdPartyNotices)
    from(layout.projectDirectory.dir("packaging/codex-plugin")) {
        filesMatching(".codex-plugin/plugin.json") {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(pluginLicenseFile)
    from(generateThirdPartyNotices)
    from(standaloneInstallDir) {
        into("server")
    }
    from(pluginLicenseFile) {
        into("server")
    }
    from(generateThirdPartyNotices) {
        into("server")
    }
    into(rootProject.layout.projectDirectory.dir("plugins/hexana"))
    doLast {
        val launcherPath = destinationDir.resolve("bin/hexana-mcp").absolutePath
        destinationDir.resolve(".mcp.json").writeText(renderMcpConfig(launcherPath))
    }
}

fun registerPluginZip(
    taskName: String,
    assembleTaskName: String,
    packagingRootDir: Provider<Directory>,
    packagingDirName: String,
    archiveBaseNameValue: String,
    distributionDirName: String = "distributions",
) = tasks.register<Zip>(taskName) {
    group = "distribution"
    description = "Zip the assembled $packagingDirName wrapper into mcp/build/distributions."
    dependsOn(assembleTaskName)
    from(packagingRootDir.map { it.dir(packagingDirName) }) {
        filesMatching(listOf("bin/hexana-mcp", "server/bin/*", "native/**/hexana-mcp")) {
            if (!name.endsWith(".bat") && !name.endsWith(".cmd")) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
    archiveBaseName.set(archiveBaseNameValue)
    archiveVersion.set(mcpServerVersion)
    destinationDirectory.set(layout.buildDirectory.dir(distributionDirName))
}

val zipPlainClaudePlugin = registerPluginZip(
    taskName = "zipPlainClaudePlugin",
    assembleTaskName = "assemblePlainClaudePluginPackage",
    packagingRootDir = plainPackagingDir,
    packagingDirName = "claude-plugin",
    archiveBaseNameValue = "hexana-claude-plugin",
    distributionDirName = "distributions-plain",
)

val zipPlainCodexPlugin = registerPluginZip(
    taskName = "zipPlainCodexPlugin",
    assembleTaskName = "assemblePlainCodexPluginPackage",
    packagingRootDir = plainPackagingDir,
    packagingDirName = "codex-plugin",
    archiveBaseNameValue = "hexana-codex-plugin",
    distributionDirName = "distributions-plain",
)

val zipClaudePlugin = registerPluginZip(
    taskName = "zipClaudePlugin",
    assembleTaskName = "assembleClaudePluginPackage",
    packagingRootDir = obfuscatedPackagingDir,
    packagingDirName = "claude-plugin",
    archiveBaseNameValue = "hexana-claude-plugin",
)

val zipCodexPlugin = registerPluginZip(
    taskName = "zipCodexPlugin",
    assembleTaskName = "assembleCodexPluginPackage",
    packagingRootDir = obfuscatedPackagingDir,
    packagingDirName = "codex-plugin",
    archiveBaseNameValue = "hexana-codex-plugin",
)

val zipNativeClaudePlugin = registerPluginZip(
    taskName = "zipNativeClaudePlugin",
    assembleTaskName = "assembleNativeClaudePluginPackage",
    packagingRootDir = nativePackagingDir,
    packagingDirName = "claude-plugin",
    archiveBaseNameValue = "hexana-claude-plugin-$nativeHostPlatformName",
    distributionDirName = "distributions-native",
)

val zipNativeCodexPlugin = registerPluginZip(
    taskName = "zipNativeCodexPlugin",
    assembleTaskName = "assembleNativeCodexPluginPackage",
    packagingRootDir = nativePackagingDir,
    packagingDirName = "codex-plugin",
    archiveBaseNameValue = "hexana-codex-plugin-$nativeHostPlatformName",
    distributionDirName = "distributions-native",
)

val zipNativeMcpExecutable = tasks.register<Zip>("zipNativeMcpExecutable") {
    group = "distribution"
    description = "Zip the current host's GraalVM native MCP executable into mcp/build/distributions-native."
    dependsOn(":mcp:server:nativeCompile", generateThirdPartyNotices)
    from(nativeServerExecutable) {
        into("bin")
        rename { nativeServerExecutableName }
        filesMatching(nativeServerExecutableName) {
            if (!nativeServerExecutableName.endsWith(".exe")) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
    from(pluginLicenseFile)
    from(generateThirdPartyNotices)
    archiveBaseName.set("hexana-mcp-native-$nativeHostPlatformName")
    archiveVersion.set(mcpServerVersion)
    destinationDirectory.set(nativeDistributionDir)
}

tasks.register("assembleMcpPackagingPlain") {
    group = "distribution"
    description = "Assemble pre-R8 standalone, Claude, and Codex MCP wrapper layouts for smoke testing."
    dependsOn(":mcp:server:distZip", assemblePlainClaudePluginPackage, assemblePlainCodexPluginPackage, zipPlainClaudePlugin, zipPlainCodexPlugin)
}

tasks.register("assembleMcpPackaging") {
    group = "distribution"
    description = "Assemble self-contained Claude and Codex MCP wrapper layouts under mcp/build/packaging and their distributable ZIPs."
    dependsOn(assembleClaudePluginPackage, assembleCodexPluginPackage, zipClaudePlugin, zipCodexPlugin, zipObfuscatedMcpServer)
}

val assembleNativeMcpPackaging = tasks.register("assembleNativeMcpPackaging") {
    group = "distribution"
    description = "Assemble native-enabled Claude and Codex MCP wrapper layouts for the current host platform while keeping JVM fallback."
    dependsOn(assembleNativeClaudePluginPackage, assembleNativeCodexPluginPackage, zipNativeClaudePlugin, zipNativeCodexPlugin, zipNativeMcpExecutable)
}

tasks.register("assembleNativeMcpReleaseArtifacts") {
    group = "distribution"
    description = "Build and smoke-test current-host native MCP release artifacts for the platform CI matrix."
    dependsOn(
        ":mcp:server:nativeMcpVersionSmoke",
        ":mcp:server:nativeMcpStdioSmoke",
        assembleNativeMcpPackaging,
    )
}

fun validatePublicMarketplaceBundleNativePolicy(outputDir: File) {
    val pluginDir = outputDir.resolve("plugins/hexana")
    val readmeFile = pluginDir.resolve("README.md")
    val releaseLockFile = pluginDir.resolve("release.lock.json")
    check(readmeFile.isFile) {
        "Public marketplace bundle is missing ${readmeFile.absolutePath}"
    }
    check(releaseLockFile.isFile) {
        "Public marketplace bundle is missing ${releaseLockFile.absolutePath}"
    }

    val readme = readmeFile.readText()
    val releaseLock = releaseLockFile.readText()
    val nativeDir = pluginDir.resolve("native")
    val nativeFilesPresent = nativeDir.exists() && nativeDir.walkTopDown().any { it.isFile }
    val readmeClaimsBundledNative = listOf(
        "`native/<platform>/hexana-mcp`: optional bundled native executable",
        "Native-enabled wrappers also include",
    ).any(readme::contains)
    val lockClaimsBundledNative = listOf(
        "\"distribution\": \"marketplace-bundle\"",
        "\"distribution\": \"bundled\"",
    ).any(releaseLock::contains)

    check(nativeFilesPresent || !readmeClaimsBundledNative) {
        "Public marketplace README claims bundled native files, but plugins/hexana/native is missing."
    }
    check(nativeFilesPresent || !lockClaimsBundledNative) {
        "Public marketplace release.lock.json claims bundled native files, but plugins/hexana/native is missing."
    }
    check(nativeFilesPresent || readme.contains("Native executables are not included in this public marketplace tree.")) {
        "Public marketplace README must document that native executables are GitHub Release artifacts when plugins/hexana/native is absent."
    }
    check(nativeFilesPresent || releaseLock.contains("\"distribution\": \"github-release-only\"")) {
        "Public marketplace release.lock.json must document the GitHub Release-only native artifact policy when plugins/hexana/native is absent."
    }
}

tasks.register<Sync>("assemblePublicMarketplaceBundle") {
    group = "distribution"
    description = "Assemble the public marketplace bundle consumed by TeamCity release automation."
    dependsOn(assembleClaudePluginPackage, assembleCodexPluginPackage, generateThirdPartyNotices)
    into(publicMarketplaceDir)

    from(obfuscatedPackagingDir.map { it.dir("claude-plugin/.claude-plugin") }) {
        into("plugins/hexana/.claude-plugin")
    }
    from(obfuscatedPackagingDir.map { it.dir("codex-plugin/.codex-plugin") }) {
        into("plugins/hexana/.codex-plugin")
    }
    from(obfuscatedPackagingDir.map { it.file("codex-plugin/.mcp.json") }) {
        into("plugins/hexana")
    }
    from(obfuscatedPackagingDir.map { it.dir("claude-plugin/bin") }) {
        into("plugins/hexana/bin")
        filesMatching("hexana-mcp") {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(obfuscatedPackagingDir.map { it.dir("codex-plugin/server") }) {
        into("plugins/hexana/server")
        filesMatching("bin/server") {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(obfuscatedPackagingDir.map { it.dir("codex-plugin/skills") }) {
        into("plugins/hexana/skills")
    }
    from(pluginLicenseFile) {
        into("plugins/hexana")
    }
    from(generateThirdPartyNotices) {
        into("plugins/hexana")
    }

    doFirst {
        delete(publicMarketplaceDir)
    }
    doLast {
        val outputDir = publicMarketplaceDir.asFile
        outputDir.resolve(".claude-plugin").mkdirs()
        outputDir.resolve(".agents/plugins").mkdirs()
        outputDir.resolve("plugins/hexana").mkdirs()

        fun writeClientMcpJson(client: String) {
            val source = obfuscatedPackagingDir.get().file("$client-plugin/.mcp.json").asFile
            val destination = outputDir.resolve("plugins/hexana/.$client-plugin/mcp.json")
            destination.parentFile.mkdirs()
            destination.writeText(source.readText())
        }

        fun rewriteManifestMcpPath(client: String) {
            val manifest = outputDir.resolve("plugins/hexana/.$client-plugin/plugin.json")
            val original = manifest.readText()
            val updated = original.replace(
                "\"mcpServers\": \"./.mcp.json\"",
                "\"mcpServers\": \"./.$client-plugin/mcp.json\""
            )
            check(updated != original) {
                "Could not rewrite mcpServers path in ${manifest.absolutePath}"
            }
            manifest.writeText(updated)
        }

        writeClientMcpJson("claude")
        writeClientMcpJson("codex")
        rewriteManifestMcpPath("claude")
        rewriteManifestMcpPath("codex")

        listOf(
            "plugins/hexana/bin/hexana-mcp.cmd",
            "plugins/hexana/server/bin/server.bat"
        ).forEach { relativePath ->
            val file = outputDir.resolve(relativePath)
            if (file.isFile) {
                file.writeText(file.readText().replace("\r\n", "\n"))
            }
        }

        outputDir.resolve(".claude-plugin/marketplace.json").writeText(
            """
            {
              "name": "hexana",
              "owner": {
                "name": "JetBrains"
              },
              "metadata": {
                "description": "Hexana marketplace distribution for Claude Code"
              },
              "plugins": [
                {
                  "name": "hexana",
                  "source": "./plugins/hexana",
                  "category": "Developer Tools"
                }
              ]
            }
            """.trimIndent() + "\n"
        )
        outputDir.resolve(".agents/plugins/marketplace.json").writeText(
            """
            {
              "name": "hexana",
              "interface": {
                "displayName": "Hexana"
              },
              "plugins": [
                {
                  "name": "hexana",
                  "source": {
                    "source": "local",
                    "path": "./plugins/hexana"
                  },
                  "policy": {
                    "installation": "AVAILABLE",
                    "authentication": "ON_INSTALL"
                  },
                  "category": "Developer Tools"
                }
              ]
            }
            """.trimIndent() + "\n"
        )
        outputDir.resolve("plugins/hexana/README.md").writeText(
            """
            # Hexana

            Hexana adds WebAssembly inspection tools to Claude Code and Codex through a bundled MCP server.

            ## Requirements

            - Java 21 or newer must be available on `PATH`.
            - The plugin is installed from a configured Claude Code or Codex marketplace.

            ## Included Files

            - `.claude-plugin/plugin.json`: Claude Code plugin manifest.
            - `.codex-plugin/plugin.json`: Codex plugin manifest.
            - `.claude-plugin/mcp.json` and `.codex-plugin/mcp.json`: client-specific MCP server configuration.
            - `bin/hexana-mcp`: launcher used by the client plugin.
            - `LICENSE.txt`: JetBrains plugin license agreement.
            - `THIRD-PARTY-NOTICES.md`: generated third-party dependency notices.
            - `server/`: bundled standalone Hexana MCP runtime.
            - `skills/wasm-inspector/`: Codex skill metadata and instructions.

            ## Native Artifacts

            Native executables are not included in this public marketplace tree. They are published as GitHub Release
            artifacts produced by the platform matrix:

            ${nativeReleasePlatforms.joinToString(separator = "\n            ") { "- `hexana-mcp-native-$it-$mcpServerVersion.zip`" }}
            ${nativeReleasePlatforms.joinToString(separator = "\n            ") { "- `hexana-claude-plugin-$it-$mcpServerVersion.zip`" }}
            ${nativeReleasePlatforms.joinToString(separator = "\n            ") { "- `hexana-codex-plugin-$it-$mcpServerVersion.zip`" }}

            The marketplace launcher uses the bundled JVM runtime from `server/`. Native-enabled release ZIPs use the
            same launcher and add `native/<platform>/hexana-mcp` beside the JVM fallback.

            ## What It Provides

            The bundled MCP server can summarize WebAssembly modules and list imports, exports, memories, globals,
            types, functions, locals, instructions, element segments, and data segments.
            """.trimIndent() + "\n"
        )
        outputDir.resolve("plugins/hexana/release.lock.json").writeText(
            """
            {
              "version": "$mcpServerVersion",
              "artifacts": [
                "hexana-claude-plugin-$mcpServerVersion.zip",
                "hexana-codex-plugin-$mcpServerVersion.zip",
                "server-$mcpServerVersion.zip"
              ],
              "nativeArtifacts": {
                "distribution": "github-release-only",
                "platforms": [
                  ${nativeReleasePlatforms.joinToString(separator = ",\n                  ") { "\"$it\"" }}
                ],
                "artifacts": [
                  ${nativeReleasePlatforms.flatMap { platform ->
                      listOf(
                          "hexana-mcp-native-$platform-$mcpServerVersion.zip",
                          "hexana-claude-plugin-$platform-$mcpServerVersion.zip",
                          "hexana-codex-plugin-$platform-$mcpServerVersion.zip",
                      )
                  }.joinToString(separator = ",\n                  ") { "\"$it\"" }}
                ]
              }
            }
            """.trimIndent() + "\n"
        )
        validatePublicMarketplaceBundleNativePolicy(outputDir)
    }
}

tasks.register("validatePublicMarketplaceBundle") {
    group = "verification"
    description = "Validate generated public marketplace metadata and native artifact policy."
    dependsOn("assemblePublicMarketplaceBundle")
    inputs.dir(publicMarketplaceDir)
    doLast {
        validatePublicMarketplaceBundleNativePolicy(publicMarketplaceDir.asFile)
    }
}

val GENERATED_VERSION_TOKEN = "__GENERATED_BY_ASSEMBLY__"

fun renderMcpConfig(commandPath: String): String {
    val escapedCommandPath = commandPath.replace("\\", "\\\\")
    return """
        {
          "mcpServers": {
            "hexana": {
              "command": "$escapedCommandPath",
              "args": [
                "stdio"
              ]
            }
          }
        }
    """.trimIndent()
}

fun parseRequiredBoolean(value: String): Boolean {
    return when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("Expected boolean Gradle property value `true` or `false`, got: $value")
    }
}

fun currentNativePlatformName(): String {
    val os = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
        else -> System.getProperty("os.name").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
    val arch = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
    return "$os-$arch"
}

fun String.isLoopbackHost(): Boolean {
    return runCatching { InetAddress.getByName(this).isLoopbackAddress }
        .getOrDefault(false)
}
