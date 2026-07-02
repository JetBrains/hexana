import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import groovy.json.JsonSlurper
import java.net.InetAddress
import java.util.*
import java.util.jar.JarFile
import java.util.zip.ZipFile
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
val claudeDesktopMcpbDir = layout.buildDirectory.dir("packaging-native/claude-desktop-mcpb")
val obfuscatedInstallDir = project(":mcp").layout.buildDirectory.dir("install/server-r8")
val nativeServerExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "hexana-mcp.exe"
} else {
    "hexana-mcp"
}
val nativeHostPlatformName = currentNativePlatformName()
val mcpbHostPlatformName = currentMcpbPlatformName(nativeHostPlatformName)
val nativeReleasePlatforms = listOf("macos-arm64", "linux-x64")
val nativeServerExecutable = project(":mcp:server").layout.buildDirectory.file("native/nativeCompile/$nativeServerExecutableName")
val mcpbNativeExecutableOverridePath = providers.gradleProperty("hexanaMcpbNativeExecutable").map { it.trim() }
val mcpbNativeZipOverridePath = providers.gradleProperty("hexanaMcpbNativeZip").map { it.trim() }
val mcpbPreparedNativeExecutable = layout.buildDirectory.file("mcpb-native-runtime/$nativeHostPlatformName/$nativeServerExecutableName")
val nativeDistributionDir = layout.buildDirectory.dir("distributions-native")
val claudeDesktopMcpbArchiveName = "hexana-core-$nativeHostPlatformName.mcpb"
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
require(!(mcpbNativeExecutableOverridePath.isPresent && mcpbNativeZipOverridePath.isPresent)) {
    "Use only one MCPB native override: `hexanaMcpbNativeExecutable` or `hexanaMcpbNativeZip`."
}
mcpbNativeExecutableOverridePath.orNull?.let { path ->
    require(path.isNotEmpty()) {
        "`hexanaMcpbNativeExecutable` must not be blank."
    }
}
mcpbNativeZipOverridePath.orNull?.let { path ->
    require(path.isNotEmpty()) {
        "`hexanaMcpbNativeZip` must not be blank."
    }
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

fun rewriteMcpServerLauncherClasspaths(installDir: File) {
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

fun rewritePackagedMcpServerLauncherClasspaths(pluginDir: File) {
    rewriteMcpServerLauncherClasspaths(pluginDir.resolve("server"))
}

fun nativeExecutableArtifactName(platform: String): String =
    "hexana-mcp-native-$platform-$mcpServerVersion.zip"

fun nativeReleaseArtifactNames(platform: String): List<String> = listOf(
    nativeExecutableArtifactName(platform),
    "hexana-claude-plugin-$platform-$mcpServerVersion.zip",
    "hexana-codex-plugin-$platform-$mcpServerVersion.zip",
)

fun nativeExecutableZipFile(platform: String): File =
    nativeDistributionDir.get().file(nativeExecutableArtifactName(platform)).asFile

fun validateNativeExecutableZip(platform: String, zipFile: File) {
    check(zipFile.isFile) {
        "Missing native executable ZIP for $platform: ${zipFile.absolutePath}. " +
            "Expected ${nativeExecutableArtifactName(platform)} under ${nativeDistributionDir.get().asFile.absolutePath}."
    }
    ZipFile(zipFile).use { zip ->
        val entry = zip.getEntry("bin/hexana-mcp")
        check(entry != null && !entry.isDirectory) {
            "Malformed native executable ZIP for $platform: ${zipFile.absolutePath} is missing bin/hexana-mcp."
        }
        check(entry.size > 0L || entry.compressedSize > 0L) {
            "Malformed native executable ZIP for $platform: ${zipFile.absolutePath} contains empty bin/hexana-mcp."
        }
    }
}

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
    doLast {
        rewritePackagedMcpServerLauncherClasspaths(destinationDir)
        if (rewriteMcpJson) {
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

        rewriteMcpServerLauncherClasspaths(installDir)
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
    doLast {
        rewritePackagedMcpServerLauncherClasspaths(destinationDir)
    }
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
        rewritePackagedMcpServerLauncherClasspaths(destinationDir)
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

val prepareClaudeDesktopMcpbNativeExecutable = tasks.register("prepareClaudeDesktopMcpbNativeExecutable") {
    group = "distribution"
    description = "Prepare the native executable used by the local Claude Desktop MCPB package."
    outputs.file(mcpbPreparedNativeExecutable)

    when {
        mcpbNativeExecutableOverridePath.isPresent -> {
            inputs.file(providers.provider { file(mcpbNativeExecutableOverridePath.get()) })
        }
        mcpbNativeZipOverridePath.isPresent -> {
            inputs.file(providers.provider { file(mcpbNativeZipOverridePath.get()) })
        }
        else -> {
            dependsOn(":mcp:server:nativeCompile")
            inputs.file(nativeServerExecutable)
        }
    }

    doLast {
        val destinationFile = mcpbPreparedNativeExecutable.get().asFile
        when {
            mcpbNativeExecutableOverridePath.isPresent -> copyMcpbNativeExecutable(
                sourceFile = file(mcpbNativeExecutableOverridePath.get()),
                destinationFile = destinationFile,
                sourceDescription = "hexanaMcpbNativeExecutable",
            )
            mcpbNativeZipOverridePath.isPresent -> extractMcpbNativeExecutable(
                zipFile = file(mcpbNativeZipOverridePath.get()),
                destinationFile = destinationFile,
            )
            else -> copyMcpbNativeExecutable(
                sourceFile = nativeServerExecutable.get().asFile,
                destinationFile = destinationFile,
                sourceDescription = ":mcp:server:nativeCompile",
            )
        }
    }
}

val assembleClaudeDesktopMcpbPackage = tasks.register<Sync>("assembleClaudeDesktopMcpbPackage") {
    group = "distribution"
    description = "Stage the local Claude Desktop MCPB package for the current host platform."
    dependsOn(installObfuscatedMcpServerRuntime, prepareClaudeDesktopMcpbNativeExecutable, generateThirdPartyNotices)

    from(layout.projectDirectory.dir("packaging/claude-desktop-mcpb")) {
        filesMatching("manifest.json") {
            filter { line ->
                line
                    .replace(GENERATED_VERSION_TOKEN, mcpServerVersion)
                    .replace("__GENERATED_MCPB_PLATFORM__", mcpbHostPlatformName)
            }
        }
        filesMatching("bin/hexana-mcp-claude-desktop") {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(rootProject.layout.projectDirectory.file("vscode-plugin/media/icon.png")) {
        into("assets")
        rename { "icon.png" }
    }
    from(layout.projectDirectory.dir("packaging/claude-plugin/bin")) {
        into("bin")
        filesMatching("hexana-mcp") {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(mcpbPreparedNativeExecutable) {
        into("native/$nativeHostPlatformName")
        rename { nativeServerExecutableName }
        filesMatching(nativeServerExecutableName) {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(obfuscatedInstallDir) {
        into("server")
        filesMatching("bin/*") {
            if (!name.endsWith(".bat") && !name.endsWith(".cmd")) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
    from(pluginLicenseFile)
    from(generateThirdPartyNotices)
    from(pluginLicenseFile) {
        into("server")
    }
    from(generateThirdPartyNotices) {
        into("server")
    }
    into(claudeDesktopMcpbDir)

    doFirst {
        delete(claudeDesktopMcpbDir)
    }
    doLast {
        val outputDir = claudeDesktopMcpbDir.get().asFile
        val manifest = outputDir.resolve("manifest.json")
        check(manifest.readText().contains(GENERATED_VERSION_TOKEN).not()) {
            "Generated MCPB manifest still contains the version token."
        }
        check(manifest.readText().contains("__GENERATED_MCPB_PLATFORM__").not()) {
            "Generated MCPB manifest still contains the platform token."
        }
        check(outputDir.resolve("bin/hexana-mcp-claude-desktop").canExecute()) {
            "MCPB Claude Desktop launcher is not executable."
        }
        check(outputDir.resolve("native/$nativeHostPlatformName/$nativeServerExecutableName").canExecute()) {
            "MCPB package is missing executable native runtime for $nativeHostPlatformName."
        }
    }
}

tasks.register("validateClaudeDesktopMcpbManifest") {
    group = "verification"
    description = "Validate generated Claude Desktop MCPB manifest fields used by the local prototype."
    dependsOn(assembleClaudeDesktopMcpbPackage)

    val manifestFile = claudeDesktopMcpbDir.map { it.file("manifest.json") }
    inputs.file(manifestFile)

    doLast {
        validateClaudeDesktopMcpbManifest(manifestFile.get().asFile)
    }
}

tasks.register("validateClaudeDesktopMcpbNativeRuntime") {
    group = "verification"
    description = "Validate that the staged MCPB native runtime supports the required Claude Desktop CLI surface."
    dependsOn(assembleClaudeDesktopMcpbPackage)

    val packageDir = claudeDesktopMcpbDir
    val smokeAllowedRoot = layout.buildDirectory.dir("mcpb-smoke/native allowed root")
    inputs.dir(packageDir)
    outputs.upToDateWhen { false }

    doLast {
        val outputDir = packageDir.get().asFile
        val allowedRoot = smokeAllowedRoot.get().asFile.also { it.mkdirs() }
        val nativeRuntime = outputDir.resolve("native/$nativeHostPlatformName/$nativeServerExecutableName")
        validateMcpbNativeCliSurface(nativeRuntime, allowedRoot, outputDir)
    }
}

val packClaudeDesktopMcpb = tasks.register<Zip>("packClaudeDesktopMcpb") {
    group = "distribution"
    description = "Pack the local Claude Desktop MCPB bundle into mcp/build/distributions-native."
    dependsOn("validateClaudeDesktopMcpbManifest", "validateClaudeDesktopMcpbNativeRuntime")

    from(claudeDesktopMcpbDir) {
        filesMatching(listOf("bin/hexana-mcp", "bin/hexana-mcp-claude-desktop", "server/bin/*", "native/**/hexana-mcp")) {
            if (!name.endsWith(".bat") && !name.endsWith(".cmd")) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
    archiveFileName.set(claudeDesktopMcpbArchiveName)
    destinationDirectory.set(nativeDistributionDir)
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

tasks.register("claudeDesktopMcpbSmoke") {
    group = "verification"
    description = "Smoke-test the staged local Claude Desktop MCPB layout and packed archive."
    dependsOn(packClaudeDesktopMcpb)

    val packageDir = claudeDesktopMcpbDir
    val archiveFile = nativeDistributionDir.map { it.file(claudeDesktopMcpbArchiveName) }
    val smokeAllowedRoot = layout.buildDirectory.dir("mcpb-smoke/allowed root")
    val smokeSecondAllowedRoot = layout.buildDirectory.dir("mcpb-smoke/second allowed root")
    val fakeServerExecutable = layout.buildDirectory.file("mcpb-smoke/fake server/hexana-mcp")

    inputs.dir(packageDir)
    inputs.file(archiveFile)
    outputs.upToDateWhen { false }

    doLast {
        val outputDir = packageDir.get().asFile
        val archive = archiveFile.get().asFile
        val allowedRoot = smokeAllowedRoot.get().asFile.also { it.mkdirs() }
        val secondAllowedRoot = smokeSecondAllowedRoot.get().asFile.also { it.mkdirs() }
        val fakeServer = fakeServerExecutable.get().asFile.also { executable ->
            executable.parentFile.mkdirs()
            executable.writeText(
                """
                #!/bin/sh
                printf 'args:\n'
                printf '%s\n' "$@"
                printf 'env-begin\n%s\nenv-end\n' "${'$'}HEXANA_MCP_ALLOWED_ROOTS"
                """.trimIndent() + "\n"
            )
            executable.setExecutable(true, false)
        }
        val launcher = outputDir.resolve("bin/hexana-mcp")
        val desktopLauncher = outputDir.resolve("bin/hexana-mcp-claude-desktop")
        val fallbackServer = outputDir.resolve("server/bin/server")
        val nativeRuntime = outputDir.resolve("native/$nativeHostPlatformName/$nativeServerExecutableName")

        check(launcher.canExecute()) { "Expected executable launcher at ${launcher.absolutePath}" }
        check(desktopLauncher.canExecute()) { "Expected executable MCPB launcher at ${desktopLauncher.absolutePath}" }
        check(fallbackServer.canExecute()) { "Expected executable JVM fallback server at ${fallbackServer.absolutePath}" }
        check(nativeRuntime.canExecute()) { "Expected executable native runtime at ${nativeRuntime.absolutePath}" }

        validateMcpbNativeCliSurface(nativeRuntime, allowedRoot, outputDir)

        val doctor = runProcess(
            listOf(launcher.absolutePath, "doctor"),
            outputDir,
            environment = mapOf(
                "HEXANA_SERVER_BIN" to fallbackServer.absolutePath,
                "HEXANA_MCP_ALLOWED_ROOTS" to allowedRoot.absolutePath,
            ),
        )
        check(doctor.exitCode == 0 && doctor.output.contains("[OK] allowed roots: 1 configured")) {
            "Expected doctor to see the staged allowed root, got exit ${doctor.exitCode}:\n${doctor.output}"
        }

        val missingRoots = runProcess(listOf(desktopLauncher.absolutePath), outputDir)
        check(missingRoots.exitCode != 0 && missingRoots.output.contains("requires at least one allowed directory")) {
            "Expected MCPB launcher to reject missing allowed directories, got exit ${missingRoots.exitCode}:\n${missingRoots.output}"
        }

        val wrapperArgs = runProcess(
            listOf(desktopLauncher.absolutePath, allowedRoot.absolutePath, secondAllowedRoot.absolutePath),
            outputDir,
            environment = mapOf("HEXANA_SERVER_BIN" to fakeServer.absolutePath),
        )
        val observedArgs = wrapperArgs.output.lineSequence().filter { it.isNotBlank() }.toList()
        check(
            wrapperArgs.exitCode == 0 && observedArgs == listOf(
                "args:",
                "stdio",
                "env-begin",
                allowedRoot.absolutePath,
                secondAllowedRoot.absolutePath,
                "env-end",
            )
        ) {
            "Expected MCPB launcher to pass selected directories as allowed roots, got exit ${wrapperArgs.exitCode}:\n${wrapperArgs.output}"
        }

        ZipFile(archive).use { zip ->
            val requiredEntries = listOf(
                "manifest.json",
                "bin/hexana-mcp",
                "bin/hexana-mcp-claude-desktop",
                "native/$nativeHostPlatformName/$nativeServerExecutableName",
                "assets/icon.png",
                "LICENSE.txt",
                thirdPartyNoticesFileName,
            )
            val missing = requiredEntries.filter { zip.getEntry(it) == null }
            check(missing.isEmpty()) {
                "Packed MCPB archive is missing entries: ${missing.joinToString()}"
            }
        }
    }
}

tasks.register("mcpbValidateClaudeDesktopMcpb") {
    group = "verification"
    description = "Run the official mcpb CLI validate command against the staged Claude Desktop MCPB manifest."
    dependsOn(assembleClaudeDesktopMcpbPackage)

    doLast {
        runRequiredMcpbCommand(
            listOf("validate", claudeDesktopMcpbDir.get().asFile.absolutePath),
            "Install with `npm install -g @anthropic-ai/mcpb`, or pass `-PmcpbCli=/path/to/mcpb`."
        )
    }
}

tasks.register("mcpbPackClaudeDesktopMcpb") {
    group = "distribution"
    description = "Run the official mcpb CLI pack command for comparison with the Gradle-packed MCPB."
    dependsOn(assembleClaudeDesktopMcpbPackage)

    doLast {
        val outputFile = nativeDistributionDir.get().file("hexana-core-$nativeHostPlatformName.mcpb").asFile
        outputFile.parentFile.mkdirs()
        runRequiredMcpbCommand(
            listOf("pack", claudeDesktopMcpbDir.get().asFile.absolutePath, outputFile.absolutePath),
            "Install with `npm install -g @anthropic-ai/mcpb`, or pass `-PmcpbCli=/path/to/mcpb`."
        )
    }
}

tasks.register("mcpbInfoClaudeDesktopMcpb") {
    group = "verification"
    description = "Run the official mcpb CLI info command against the packed Claude Desktop MCPB."
    dependsOn(packClaudeDesktopMcpb)

    doLast {
        runRequiredMcpbCommand(
            listOf("info", nativeDistributionDir.get().file(claudeDesktopMcpbArchiveName).asFile.absolutePath),
            "Install with `npm install -g @anthropic-ai/mcpb`, or pass `-PmcpbCli=/path/to/mcpb`."
        )
    }
}

tasks.register("assembleClaudeDesktopMcpb") {
    group = "distribution"
    description = "Assemble, validate, pack, and smoke-test the local Claude Desktop MCPB bundle."
    dependsOn(packClaudeDesktopMcpb, "claudeDesktopMcpbSmoke")
}

tasks.register("assembleMcpPackagingPlain") {
    group = "distribution"
    description = "Assemble pre-R8 JVM fallback standalone, Claude, and Codex MCP wrapper layouts for diagnostic smoke testing."
    dependsOn(":mcp:server:distZip", assemblePlainClaudePluginPackage, assemblePlainCodexPluginPackage, zipPlainClaudePlugin, zipPlainCodexPlugin)
}

tasks.register("assembleMcpPackaging") {
    group = "distribution"
    description = "Assemble R8-obfuscated JVM fallback Claude and Codex MCP wrapper layouts; native matrix artifacts are required for release readiness."
    dependsOn(assembleClaudePluginPackage, assembleCodexPluginPackage, zipClaudePlugin, zipCodexPlugin, zipObfuscatedMcpServer)
}

val assembleNativeMcpPackaging = tasks.register("assembleNativeMcpPackaging") {
    group = "distribution"
    description = "Assemble native-enabled Claude and Codex MCP wrapper layouts for the current host platform while keeping JVM fallback."
    dependsOn(assembleNativeClaudePluginPackage, assembleNativeCodexPluginPackage, zipNativeClaudePlugin, zipNativeCodexPlugin, zipNativeMcpExecutable)
}

tasks.register("assembleNativeMcpReleaseArtifacts") {
    group = "distribution"
    description = "Build and smoke-test mandatory current-host GraalVM Native Image MCP release artifacts for the platform CI matrix."
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
    nativeReleasePlatforms.forEach { platform ->
        val nativeFile = pluginDir.resolve("native/$platform/hexana-mcp")
        check(nativeFile.isFile) {
            "Public marketplace bundle is missing bundled native executable for $platform: ${nativeFile.absolutePath}"
        }
        check(nativeFile.length() > 0L) {
            "Public marketplace bundled native executable for $platform is empty: ${nativeFile.absolutePath}"
        }
        check(nativeFile.canExecute()) {
            "Public marketplace bundled native executable for $platform is not executable: ${nativeFile.absolutePath}"
        }
        check(readme.contains("`native/$platform/hexana-mcp`")) {
            "Public marketplace README must document bundled native executable for $platform."
        }
    }
    check(!readme.contains("Native executables are not included in this public marketplace tree.")) {
        "Public marketplace README must not claim native executables are absent when they are bundled."
    }
    check(releaseLock.contains("\"distribution\": \"marketplace-bundle\"")) {
        "Public marketplace release.lock.json must document that native executables are bundled in the marketplace tree."
    }
    check(!releaseLock.contains("\"distribution\": \"github-release-only\"")) {
        "Public marketplace release.lock.json must not claim GitHub Release-only native distribution when native is bundled."
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
    nativeReleasePlatforms.forEach { platform ->
        from({ zipTree(nativeExecutableZipFile(platform)) }) {
            include("bin/hexana-mcp")
            eachFile {
                relativePath = org.gradle.api.file.RelativePath(
                    true,
                    "plugins",
                    "hexana",
                    "native",
                    platform,
                    "hexana-mcp"
                )
                permissions { unix("rwxr-xr-x") }
            }
            includeEmptyDirs = false
        }
    }

    doFirst {
        nativeReleasePlatforms.forEach { platform ->
            validateNativeExecutableZip(platform, nativeExecutableZipFile(platform))
        }
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

            - macOS arm64 and Linux x64 use the bundled native executable by default.
            - Other platforms require Java 21 or newer on `PATH` for the bundled JVM fallback.
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
            ${nativeReleasePlatforms.joinToString(separator = "\n            ") { "- `native/$it/hexana-mcp`: bundled native executable." }}

            ## Native Artifacts

            Native executables are bundled in this public marketplace tree for the supported native platforms:

            ${nativeReleasePlatforms.joinToString(separator = "\n            ") { "- `native/$it/hexana-mcp`" }}

            The marketplace launcher uses the bundled native executable when it matches the current platform, then falls
            back to the bundled JVM runtime from `server/`.

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
                "distribution": "marketplace-bundle",
                "platforms": [
                  ${nativeReleasePlatforms.joinToString(separator = ",\n                  ") { "\"$it\"" }}
                ],
                "paths": [
                  ${nativeReleasePlatforms.joinToString(separator = ",\n                  ") { "\"plugins/hexana/native/$it/hexana-mcp\"" }}
                ],
                "sourceArtifacts": [
                  ${nativeReleasePlatforms.joinToString(separator = ",\n                  ") { "\"${nativeExecutableArtifactName(it)}\"" }}
                ],
                "githubReleaseArtifacts": [
                  ${nativeReleasePlatforms.flatMap(::nativeReleaseArtifactNames).joinToString(separator = ",\n                  ") { "\"$it\"" }}
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

fun validateClaudeDesktopMcpbManifest(manifestFile: File) {
    val manifest = JsonSlurper().parse(manifestFile) as? Map<*, *>
        ?: error("MCPB manifest must be a JSON object: ${manifestFile.absolutePath}")
    val server = manifest.mapField("server")
    val mcpConfig = server.mapField("mcp_config")
    val userConfig = manifest.mapField("user_config")
    val allowedDirectories = userConfig.mapField("allowed_directories")
    val compatibility = manifest.mapField("compatibility")

    check(manifest["manifest_version"] == "0.3") {
        "Claude Desktop MCPB manifest must use manifest_version 0.3."
    }
    check(manifest["name"] == "hexana-core") {
        "Claude Desktop MCPB manifest must use the stable package name `hexana-core`."
    }
    check(manifest["version"] == mcpServerVersion) {
        "Claude Desktop MCPB manifest version must match mcpServerVersion $mcpServerVersion."
    }
    check(server["type"] == "binary") {
        "Claude Desktop MCPB must use server.type=binary."
    }
    check(server["entry_point"] == "bin/hexana-mcp-claude-desktop") {
        "Claude Desktop MCPB must enter through the allowed-directory wrapper."
    }
    check(mcpConfig["command"] == "\${__dirname}/bin/hexana-mcp-claude-desktop") {
        "Claude Desktop MCPB command must use the portable MCPB install directory variable."
    }
    check(mcpConfig["args"] == listOf("\${user_config.allowed_directories}")) {
        "Claude Desktop MCPB args must expand the selected allowed directories."
    }
    check(allowedDirectories["type"] == "directory") {
        "allowed_directories must use the MCPB directory picker type."
    }
    check(allowedDirectories["multiple"] == true) {
        "allowed_directories must allow multiple directory selections."
    }
    check(allowedDirectories["required"] == true) {
        "allowed_directories must be required."
    }
    check(compatibility["platforms"] == listOf(mcpbHostPlatformName)) {
        "Claude Desktop MCPB compatibility.platforms must match the current host platform."
    }
}

fun copyMcpbNativeExecutable(sourceFile: File, destinationFile: File, sourceDescription: String) {
    check(sourceFile.isFile) {
        "MCPB native executable from `$sourceDescription` does not exist: ${sourceFile.absolutePath}"
    }
    check(sourceFile.canExecute()) {
        "MCPB native executable from `$sourceDescription` is not executable: ${sourceFile.absolutePath}"
    }
    destinationFile.parentFile.mkdirs()
    sourceFile.copyTo(destinationFile, overwrite = true)
    if (!destinationFile.name.endsWith(".exe")) {
        destinationFile.setExecutable(true, false)
    }
    check(destinationFile.canExecute()) {
        "Prepared MCPB native executable is not executable: ${destinationFile.absolutePath}"
    }
}

fun extractMcpbNativeExecutable(zipFile: File, destinationFile: File) {
    check(zipFile.isFile) {
        "MCPB native ZIP does not exist: ${zipFile.absolutePath}"
    }
    val entryName = "bin/$nativeServerExecutableName"
    destinationFile.parentFile.mkdirs()
    ZipFile(zipFile).use { zip ->
        val entry = zip.getEntry(entryName)
            ?: error("MCPB native ZIP must contain `$entryName`: ${zipFile.absolutePath}")
        check(!entry.isDirectory) {
            "MCPB native ZIP entry `$entryName` must be a file: ${zipFile.absolutePath}"
        }
        zip.getInputStream(entry).use { input ->
            destinationFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
    if (!destinationFile.name.endsWith(".exe")) {
        destinationFile.setExecutable(true, false)
    }
    check(destinationFile.canExecute()) {
        "Prepared MCPB native executable from ZIP is not executable: ${destinationFile.absolutePath}"
    }
}

fun validateMcpbNativeCliSurface(nativeRuntime: File, allowedRoot: File, workingDir: File) {
    check(nativeRuntime.canExecute()) {
        "Expected executable native runtime at ${nativeRuntime.absolutePath}"
    }

    val version = runProcess(listOf(nativeRuntime.absolutePath, "version"), workingDir)
    check(version.exitCode == 0 && version.output.trim() == "hexana-mcp $mcpServerVersion") {
        """
        MCPB native executable lacks the required MCPB CLI surface.
        Expected direct native `version` to print `hexana-mcp $mcpServerVersion`, got exit ${version.exitCode}:
        ${version.output}
        Build the MCPB native override from the current branch/foundation, or omit the override so `:mcp:server:nativeCompile` is used.
        """.trimIndent()
    }

    val doctor = runProcess(
        listOf(nativeRuntime.absolutePath, "doctor", "--allowed-root", allowedRoot.absolutePath),
        workingDir,
    )
    check(doctor.exitCode == 0 && doctor.output.contains("[OK] allowed roots: 1 configured")) {
        """
        MCPB native executable lacks the required MCPB CLI surface.
        Expected direct native `doctor --allowed-root ${allowedRoot.absolutePath}` to accept allowed roots, got exit ${doctor.exitCode}:
        ${doctor.output}
        Build the MCPB native override from the current branch/foundation, or omit the override so `:mcp:server:nativeCompile` is used.
        """.trimIndent()
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

fun currentMcpbPlatformName(nativePlatformName: String): String {
    return when {
        nativePlatformName.startsWith("macos-") -> "darwin"
        nativePlatformName.startsWith("linux-") -> "linux"
        nativePlatformName.startsWith("windows-") -> "win32"
        else -> nativePlatformName.substringBefore('-')
    }
}

fun Map<*, *>.mapField(name: String): Map<*, *> {
    return this[name] as? Map<*, *>
        ?: error("Expected object field `$name` in MCPB manifest.")
}

data class ProcessResult(val exitCode: Int, val output: String)

fun runProcess(commandLine: List<String>, workingDir: File, environment: Map<String, String> = emptyMap()): ProcessResult {
    val processBuilder = ProcessBuilder(commandLine)
        .directory(workingDir)
        .redirectErrorStream(true)
    processBuilder.environment().putAll(environment)
    val process = processBuilder
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.waitFor(), output)
}

fun runRequiredMcpbCommand(args: List<String>, missingCliHint: String) {
    val cli = providers.gradleProperty("mcpbCli").orElse("mcpb").get()
    val result = try {
        runProcess(listOf(cli) + args, projectDir)
    } catch (error: java.io.IOException) {
        error("Could not run `$cli`: ${error.message}. $missingCliHint")
    }
    check(result.exitCode == 0) {
        "mcpb ${args.joinToString(" ")} failed with exit ${result.exitCode}:\n${result.output}"
    }
    if (result.output.isNotBlank()) {
        println(result.output.trimEnd())
    }
}

fun String.isLoopbackHost(): Boolean {
    return runCatching { InetAddress.getByName(this).isLoopbackAddress }
        .getOrDefault(false)
}
