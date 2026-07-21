import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import groovy.json.JsonSlurper
import java.net.InetAddress
import java.util.*
import java.util.jar.JarFile
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

val mcpServerVersion = providers.gradleProperty("hexanaToolsVersion").get()

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
val claudeDesktopMcpbManifestTemplate = layout.projectDirectory.file("packaging/claude-desktop-mcpb/manifest.json")
val claudeDesktopMcpbGeneratedManifest = layout.buildDirectory.file("generated/claude-desktop-mcpb/manifest.json")
val claudeDesktopMcpbVerifiedManifest = layout.buildDirectory.file("verification/claude-desktop-mcpb/manifest.json")
val obfuscatedInstallDir = project(":mcp").layout.buildDirectory.dir("install/server-r8")
val nativeServerExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "hexana-mcp.exe"
} else {
    "hexana-mcp"
}
val nativeHostPlatformName = currentNativePlatformName()
val mcpbHostPlatformName = providers.provider { currentMcpbPlatformName(nativeHostPlatformName) }
val nativeReleasePlatforms = listOf("macos-arm64", "linux-x64")
val nativeServerExecutable = project(":mcp:server").layout.buildDirectory.file("native/nativeCompile/$nativeServerExecutableName")
val mcpbNativeExecutableOverridePath = providers.gradleProperty("hexanaMcpbNativeExecutable").map { it.trim() }
val mcpbNativeZipOverridePath = providers.gradleProperty("hexanaMcpbNativeZip").map { it.trim() }
val mcpbPreparedNativeExecutable = layout.buildDirectory.file("mcpb-native-runtime/$nativeHostPlatformName/$nativeServerExecutableName")
val nativeDistributionDir = layout.buildDirectory.dir("distributions-native")
val claudeDesktopMcpbArchiveName = "hexana-core-$nativeHostPlatformName.mcpb"
val claudeDesktopMcpbArchive = nativeDistributionDir.map { it.file(claudeDesktopMcpbArchiveName) }
val publicMarketplaceDir = layout.projectDirectory.dir("out/teamcity/public-marketplace")
val pluginLicenseFile = layout.projectDirectory.file("packaging/LICENSE.txt")
val thirdPartyNoticesDir = layout.buildDirectory.dir("generated/third-party-notices")
val thirdPartyNoticesFileName = "THIRD-PARTY-NOTICES.md"
val r8RulesFile = layout.projectDirectory.file("packaging/r8-rules.pro")
val cliR8RulesFile = layout.projectDirectory.file("packaging/r8-cli-rules.pro")
val cliMetadataJarName = "cli-metadata.jar"
val cliProductJarName = "cli-product.jar"
val standardCliJarName = "hexana-cli-$mcpServerVersion.jar"
val scriptCliJarName = "hexana-cli-script-$mcpServerVersion.jar"
val r8OwnedJarNames = setOf(
    "analytics-jvm.jar",
    "arrow-jvm.jar",
    "binaryProvider-jvm.jar",
    "cli-core.jar",
    cliMetadataJarName,
    "cli-runtime.jar",
    "cli-scripting.jar",
    "codec-jvm.jar",
    "common.jar",
    "common-jvm.jar",
    "core.jar",
    "dex-jvm.jar",
    "dwarf-jvm.jar",
    "elf-jvm.jar",
    "encdec-jvm.jar",
    "flatbuffers-jvm.jar",
    "hexanaDocument-jvm.jar",
    "javaBytecode-jvm.jar",
    "macho-jvm.jar",
    "parquet-jvm.jar",
    "pe-jvm.jar",
    "plugins-shared-jvm.jar",
    "protobuf-jvm.jar",
    "scripting.jar",
    "server-$mcpServerVersion.jar",
    "thrift-jvm.jar",
    "wasmprinter.jar",
    "wasmParser-jvm.jar",
    "zip-jvm.jar",
)
val mcpServerDeniedOwnedJarNames = setOf(
    "cli-core.jar",
    cliMetadataJarName,
    "cli-runtime.jar",
    "cli-scripting.jar",
    "common.jar",
    "wasmprinter.jar",
)
val mcpServerR8OwnedJarNames = r8OwnedJarNames - mcpServerDeniedOwnedJarNames
val cliProductJarNames = setOf(
    cliMetadataJarName,
    cliProductJarName,
    standardCliJarName,
    scriptCliJarName,
)
val cliReleaseHexanaJarNames = r8OwnedJarNames + cliProductJarNames
val mcpSdkJarNamePrefixes = listOf("kotlin-sdk")
val ktorJarNamePrefixes = listOf("ktor-")
val cliServerOnlyJarNamePrefixes = listOf("clikt", "colormath", "jansi", "kotlin-logging", "logback", "mordant")
val graalJsJarNamePrefixes = listOf("icu4j", "js-", "js-language", "regex", "xz")
val graalWasmJarNamePrefixes = listOf("wasm-", "wasm-language")
val cliObfuscatedInstallRootDir = layout.buildDirectory.dir("install/cli-r8")
val cliR8OutputRootDir = layout.buildDirectory.dir("r8/cli/output")
val cliR8JarRootDir = layout.buildDirectory.dir("r8/cli/jars")
val cliR8MappingRootDir = layout.buildDirectory.dir("r8/cli/mapping")
val cliReleaseDistributionDir = layout.buildDirectory.dir("distributions")
val cliArchiveVerificationRootDir = layout.buildDirectory.dir("verification/cli-r8-archives")
val nativeCliArchiveVerificationRootDir = layout.buildDirectory.dir("verification/native-cli-archives")
val javaToolchains = extensions.getByType<JavaToolchainService>()
val jvmCliReferenceJavaHome = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { it.metadata.installationPath.asFile.absolutePath }

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

fun rewriteCliLauncherClasspaths(installDir: File, executableName: String) {
    installDir.resolve("bin/$executableName").takeIf { it.isFile }?.let { script ->
        script.writeText(
            Regex("(?m)^CLASSPATH=.*$").replace(script.readText()) {
                "CLASSPATH=\$APP_HOME/lib/*"
            }
        )
    }
    installDir.resolve("bin/$executableName.bat").takeIf { it.isFile }?.let { script ->
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

data class CliR8Product(
    val id: String,
    val executableName: String,
    val projectPath: String,
    val installDir: Provider<Directory>,
    val programJarNames: Set<String>,
    val deniedJarNamePrefixes: List<String>,
    val expectedCommands: Set<String>,
    val rejectedCommands: Set<String>,
    val deniedMappedClassPrefixes: List<String>,
)

fun cliReleaseProductRootName(product: CliR8Product): String = product.executableName

fun cliReleaseArchiveName(product: CliR8Product): String =
    "${product.executableName}-$mcpServerVersion.zip"

fun cliObfuscatedInstallDir(product: CliR8Product): Provider<Directory> =
    cliObfuscatedInstallRootDir.map { it.dir(product.executableName) }

fun cliR8OutputDir(product: CliR8Product): Provider<Directory> =
    cliR8OutputRootDir.map { it.dir(product.id) }

fun cliR8JarDir(product: CliR8Product): Provider<Directory> =
    cliR8JarRootDir.map { it.dir(product.id) }

fun cliR8MappingFile(product: CliR8Product): Provider<RegularFile> =
    cliR8MappingRootDir.map { it.file("${product.id}-mapping.txt") }

fun cliObfuscatedRuntimeJarFile(product: CliR8Product): Provider<RegularFile> =
    cliR8JarDir(product).map { it.file(obfuscatedRuntimeJarName) }

fun cliReleaseArchiveFile(product: CliR8Product): Provider<RegularFile> =
    cliReleaseDistributionDir.map { it.file(cliReleaseArchiveName(product)) }

fun cliExtractedArchiveDir(product: CliR8Product): Provider<Directory> =
    cliArchiveVerificationRootDir.map { it.dir(product.id) }

fun jarNameHasAnyPrefix(jarName: String, prefixes: Iterable<String>): Boolean =
    prefixes.any { prefix -> jarName.startsWith(prefix) }

fun jarContainsClassPrefix(jarFile: File, classPrefix: String): Boolean =
    ZipFile(jarFile).use { zip ->
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name.startsWith(classPrefix) && entry.name.endsWith(".class")
        }
    }

fun assertSameFileBytes(actual: File, expected: File, label: String) {
    check(actual.isFile) { "$label is missing: ${actual.absolutePath}" }
    check(expected.isFile) { "$label source is missing: ${expected.absolutePath}" }
    check(actual.readBytes().contentEquals(expected.readBytes())) {
        "$label must match ${expected.absolutePath}: ${actual.absolutePath}"
    }
}

fun assertCliReleaseRuntimeLayout(product: CliR8Product, installDir: File, label: String) {
    val executable = installDir.resolve("bin/${product.executableName}")
    check(executable.canExecute()) {
        "$label must contain executable launcher ${executable.absolutePath}."
    }
    val unexpectedLaunchers = installDir.resolve("bin")
        .listFiles()
        .orEmpty()
        .map { it.name }
        .filterNot { it == product.executableName || it == "${product.executableName}.bat" }
        .sorted()
    check(unexpectedLaunchers.isEmpty()) {
        "$label must not contain unrelated launchers. Found: $unexpectedLaunchers"
    }

    assertSameFileBytes(installDir.resolve("LICENSE.txt"), pluginLicenseFile.asFile, "$label LICENSE.txt")
    assertSameFileBytes(
        installDir.resolve(thirdPartyNoticesFileName),
        thirdPartyNoticesDir.get().file(thirdPartyNoticesFileName).asFile,
        "$label $thirdPartyNoticesFileName",
    )

    val libDir = installDir.resolve("lib")
    val jarNames = libDir
        .listFiles { file -> file.isFile && file.name.endsWith(".jar") }
        .orEmpty()
        .map { it.name }
        .sorted()
    check(jarNames.count { it == obfuscatedRuntimeJarName } == 1) {
        "$label must contain exactly one $obfuscatedRuntimeJarName. Jars: $jarNames"
    }
    val preObfuscationHexanaJars = jarNames.filter { it in cliReleaseHexanaJarNames }
    check(preObfuscationHexanaJars.isEmpty()) {
        "$label must not contain pre-obfuscation Hexana runtime jars. Found: $preObfuscationHexanaJars"
    }
    val deniedLibFiles = libDir
        .listFiles { file -> file.isFile }
        .orEmpty()
        .map { it.name }
        .filter {
            jarNameHasAnyPrefix(it, mcpSdkJarNamePrefixes + ktorJarNamePrefixes + product.deniedJarNamePrefixes)
        }
        .sorted()
    check(deniedLibFiles.isEmpty()) {
        "$label contains denied runtime library files: $deniedLibFiles"
    }
    val deniedJars = jarNames.filter {
        jarNameHasAnyPrefix(it, mcpSdkJarNamePrefixes + ktorJarNamePrefixes)
    }
    check(deniedJars.isEmpty()) {
        "$label contains denied runtime jars: $deniedJars"
    }
    val plainHexanaClassJars = libDir
        .listFiles { file -> file.isFile && file.name.endsWith(".jar") && file.name != obfuscatedRuntimeJarName }
        .orEmpty()
        .filter { jarContainsClassPrefix(it, "org/jetbrains/hexana/") }
        .map { it.name }
        .sorted()
    check(plainHexanaClassJars.isEmpty()) {
        "$label must not contain pre-obfuscation Hexana classes outside $obfuscatedRuntimeJarName. Found: $plainHexanaClassJars"
    }
}

fun assertCliR8MappingPolicy(product: CliR8Product, mappingFile: File) {
    check(mappingFile.isFile) {
        "Missing R8 mapping for ${product.executableName}: ${mappingFile.absolutePath}"
    }
    val deniedMappings = mappingFile.readLines()
        .filter { line -> line.isNotBlank() && !line.startsWith(" ") }
        .map { it.substringBefore(" ->") }
        .filter { className -> product.deniedMappedClassPrefixes.any(className::startsWith) }
        .take(50)
    check(deniedMappings.isEmpty()) {
        "${product.executableName} R8 output contains denied implementation classes: $deniedMappings"
    }
}

fun classFileMajorVersion(zip: ZipFile, entryName: String): Int? {
    val entry = zip.getEntry(entryName) ?: return null
    return zip.getInputStream(entry).use { input ->
        val header = ByteArray(8)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read == -1) break
            offset += read
        }
        if (offset == header.size) {
            ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
        } else {
            null
        }
    }
}

fun isNewerThanJava21MultiReleaseClass(entryName: String): Boolean {
    val versionPath = entryName.removePrefix("META-INF/versions/")
    if (versionPath == entryName) return false
    val version = versionPath.substringBefore('/').toIntOrNull() ?: return false
    return version > 21
}

fun assertJava21RuntimeClasses(libDir: File, label: String) {
    val tooNewClasses = mutableListOf<String>()
    libDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }
        .orEmpty()
        .sortedBy { it.name }
        .forEach { jarFile ->
            ZipFile(jarFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .filterNot { isNewerThanJava21MultiReleaseClass(it.name) }
                    .forEach { entry ->
                        val majorVersion = classFileMajorVersion(zip, entry.name)
                        if (majorVersion != null && majorVersion > 65) {
                            tooNewClasses += "${jarFile.name}!/${entry.name} has class file major version $majorVersion"
                        }
                    }
            }
        }
    check(tooNewClasses.isEmpty()) {
        buildString {
            appendLine("$label must run on Java 21, but found newer class files:")
            tooNewClasses.take(50).forEach { appendLine("  $it") }
            if (tooNewClasses.size > 50) {
                appendLine("  ... and ${tooNewClasses.size - 50} more")
            }
        }
    }
}

fun assertCliReleaseArchive(product: CliR8Product, archiveFile: File) {
    val rootName = cliReleaseProductRootName(product)
    check(archiveFile.name == cliReleaseArchiveName(product)) {
        "Unexpected ${product.executableName} archive name: ${archiveFile.name}"
    }
    ZipFile(archiveFile).use { zip ->
        val entries = zip.entries().asSequence().map { it.name }.toList()
        val rootPrefixes = entries
            .filter { it.isNotBlank() }
            .map { it.substringBefore('/') }
            .distinct()
            .sorted()
        check(rootPrefixes == listOf(rootName)) {
            "${archiveFile.name} must contain exactly one root `$rootName`. Found: $rootPrefixes"
        }
        val requiredEntries = listOf(
            "$rootName/bin/${product.executableName}",
            "$rootName/lib/$obfuscatedRuntimeJarName",
            "$rootName/LICENSE.txt",
            "$rootName/$thirdPartyNoticesFileName",
        )
        val missing = requiredEntries.filter { zip.getEntry(it) == null }
        check(missing.isEmpty()) {
            "${archiveFile.name} is missing required entries: $missing"
        }
        val unexpectedLaunchers = entries
            .filter { it.startsWith("$rootName/bin/") && !it.endsWith("/") }
            .map { it.removePrefix("$rootName/bin/") }
            .filterNot { it == product.executableName || it == "${product.executableName}.bat" }
            .sorted()
        check(unexpectedLaunchers.isEmpty()) {
            "${archiveFile.name} contains unrelated launchers: $unexpectedLaunchers"
        }
        val preObfuscationHexanaJars = entries
            .filter { it.startsWith("$rootName/lib/") && it.substringAfterLast('/') in cliReleaseHexanaJarNames }
            .sorted()
        check(preObfuscationHexanaJars.isEmpty()) {
            "${archiveFile.name} contains pre-obfuscation Hexana runtime jars: $preObfuscationHexanaJars"
        }
        val deniedLibFiles = entries
            .filter { it.startsWith("$rootName/lib/") && !it.endsWith("/") }
            .map { it.substringAfterLast('/') }
            .filter { jarNameHasAnyPrefix(it, mcpSdkJarNamePrefixes + ktorJarNamePrefixes + product.deniedJarNamePrefixes) }
            .sorted()
        check(deniedLibFiles.isEmpty()) {
            "${archiveFile.name} contains denied runtime library files: $deniedLibFiles"
        }
    }
}

fun assertCliExtractedReleaseRoot(product: CliR8Product, extractionDir: File): File {
    val rootName = cliReleaseProductRootName(product)
    val unexpectedFiles = extractionDir
        .listFiles { file -> file.isFile }
        .orEmpty()
        .map { it.name }
        .sorted()
    check(unexpectedFiles.isEmpty()) {
        "${product.executableName} extracted archive must not contain files beside root `$rootName`. Found: $unexpectedFiles"
    }
    val rootDirs = extractionDir
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .map { it.name }
        .sorted()
    check(rootDirs == listOf(rootName)) {
        "${product.executableName} extracted archive must contain exactly one root `$rootName`. Found: $rootDirs"
    }
    return extractionDir.resolve(rootName)
}

fun assertCliReleaseProcessSmoke(product: CliR8Product, installDir: File) {
    val executable = installDir.resolve("bin/${product.executableName}")
    val version = runProcess(
        listOf(executable.absolutePath, "version"),
        installDir,
        environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
    )
    check(version.exitCode == 0 && version.output.trim() == "${product.executableName} $mcpServerVersion") {
        "Expected `${product.executableName} version` to report $mcpServerVersion, got exit ${version.exitCode}:\n${version.output}"
    }

    val tools = runProcess(
        listOf(executable.absolutePath, "tools"),
        installDir,
        environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
    )
    check(tools.exitCode == 0) {
        "Expected `${product.executableName} tools` to succeed, got exit ${tools.exitCode}:\n${tools.output}"
    }
    product.expectedCommands.forEach { command ->
        check(tools.output.contains("\n$command\n")) {
            "${product.executableName} tools output must include `$command`:\n${tools.output}"
        }
    }
    product.rejectedCommands.forEach { command ->
        check(!tools.output.contains("\n$command\n")) {
            "${product.executableName} tools output must not include `$command`:\n${tools.output}"
        }
        val rejected = runProcess(
            listOf(executable.absolutePath, command),
            installDir,
            environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        check(rejected.exitCode == 2 && rejected.output.contains("no such subcommand")) {
            "Expected `${product.executableName} $command` to be rejected, got exit ${rejected.exitCode}:\n${rejected.output}"
        }
    }

    val wasmFixture = installDir.resolve("cli-r8-smoke.wasm")
    wasmFixture.writeBytes(minimalWasmModuleBytes())
    if (product.executableName == "hexana-cli") {
        val summary = runProcess(
            listOf(executable.absolutePath, "summarize-binary", wasmFixture.absolutePath, "--format", "json"),
            installDir,
            environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        check(summary.exitCode == 0 && summary.output.contains("\"format\":\"wasm\"")) {
            "Expected obfuscated hexana-cli summarize-binary to succeed, got exit ${summary.exitCode}:\n${summary.output}"
        }
        val rawWat = runProcess(
            listOf(executable.absolutePath, "wasm-to-wat", wasmFixture.absolutePath, "--format", "raw"),
            installDir,
            environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        check(rawWat.exitCode == 0 && rawWat.output.contains("(module")) {
            "Expected obfuscated hexana-cli wasm-to-wat raw output, got exit ${rawWat.exitCode}:\n${rawWat.output}"
        }
    } else {
        val script = runProcess(
            listOf(
                executable.absolutePath,
                "run-script",
                wasmFixture.absolutePath,
                "--script-text",
                "print(binary.bytes.size);",
                "--format",
                "raw",
            ),
            installDir,
            environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        val scriptPayload = script.output.lineSequence().filter { it.isNotBlank() }.lastOrNull()
        check(script.exitCode == 0 && scriptPayload == wasmFixture.length().toString()) {
            "Expected obfuscated hexana-cli-script run-script raw output, got exit ${script.exitCode}:\n${script.output}"
        }
    }
    wasmFixture.delete()
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
    doLast {
        val leakedArchives = mcpServerDeniedOwnedJarNames.filter { obfuscatedInstallDir.get().file("lib/$it").asFile.exists() }
        check(leakedArchives.isEmpty()) {
            "hexana-mcp R8 input must not contain split CLI or converter archives: $leakedArchives"
        }
    }
}

val r8ProgramJarFiles = mcpServerR8OwnedJarNames.map { obfuscatedInstallDir.map { dir -> dir.file("lib/$it") } }
val r8LibraryJarFiles = obfuscatedInstallDir.map { dir ->
    dir.dir("lib").asFileTree.matching {
        include("*.jar")
        exclude(mcpServerR8OwnedJarNames.map { name -> name })
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
    inputs.files(obfuscatedInstallDir.map { dir -> mcpServerR8OwnedJarNames.map { dir.file("lib/$it") } })
    outputs.dir(obfuscatedInstallDir)

    doLast {
        val installDir = obfuscatedInstallDir.get().asFile
        mcpServerR8OwnedJarNames.forEach { jarName ->
            installDir.resolve("lib/$jarName").delete()
        }
        jarObfuscatedMcpRuntime.get().archiveFile.get().asFile.copyTo(
            installDir.resolve("lib/$obfuscatedRuntimeJarName"),
            overwrite = true,
        )

        rewriteMcpServerLauncherClasspaths(installDir)
    }
}

val cliServerImplementationClassPrefixes = listOf(
    "org.jetbrains.hexana.mcp.server.HexanaCliKt",
    "org.jetbrains.hexana.mcp.server.HexanaDoctor",
    "org.jetbrains.hexana.mcp.server.HexanaHttp",
    "org.jetbrains.hexana.mcp.server.HexanaLog",
    "org.jetbrains.hexana.mcp.server.HexanaMcp",
    "org.jetbrains.hexana.mcp.server.HexanaStdio",
    "org.jetbrains.hexana.mcp.server.HexanaToolDispatcher",
    "org.jetbrains.hexana.mcp.server.HexanaToolHandlers",
    "org.jetbrains.hexana.mcp.server.MainKt",
)

val standardCliR8Product = CliR8Product(
    id = "standard",
    executableName = "hexana-cli",
    projectPath = ":cli:hexana-cli",
    installDir = project(":cli:hexana-cli").layout.buildDirectory.dir("install/hexana-cli"),
    programJarNames = (r8OwnedJarNames - "cli-scripting.jar" - "scripting.jar" - "server-$mcpServerVersion.jar") + setOf(
        cliProductJarName,
        standardCliJarName,
    ),
    deniedJarNamePrefixes = cliServerOnlyJarNamePrefixes + graalJsJarNamePrefixes,
    expectedCommands = setOf("summarize-binary", "summarize-module", "wasm-to-wat"),
    rejectedCommands = setOf("run-script", "stdio", "http", "doctor"),
    deniedMappedClassPrefixes = cliServerImplementationClassPrefixes + listOf(
        "org.jetbrains.hexana.cli.scripting.",
        "org.jetbrains.hexana.mcp.scripting.",
    ),
)

val scriptCliR8Product = CliR8Product(
    id = "script",
    executableName = "hexana-cli-script",
    projectPath = ":cli:hexana-cli-script",
    installDir = project(":cli:hexana-cli-script").layout.buildDirectory.dir("install/hexana-cli-script"),
    programJarNames = (r8OwnedJarNames - "cli-core.jar" - "common.jar" - "wasmprinter.jar" - "server-$mcpServerVersion.jar") + setOf(
        cliProductJarName,
        "cli-scripting.jar",
        scriptCliJarName,
    ),
    deniedJarNamePrefixes = cliServerOnlyJarNamePrefixes + graalWasmJarNamePrefixes,
    expectedCommands = setOf("run-script"),
    rejectedCommands = setOf("summarize-binary", "summarize-module", "wasm-to-wat", "stdio", "http", "doctor"),
    deniedMappedClassPrefixes = cliServerImplementationClassPrefixes + listOf(
        "org.jetbrains.hexana.cli.core.",
        "org.jetbrains.hexana.graalwasm.wasmprinter.",
    ),
)

val cliR8Products = listOf(standardCliR8Product, scriptCliR8Product)

data class CliR8Tasks(
    val copyRuntime: TaskProvider<Sync>,
    val obfuscateRuntime: TaskProvider<JavaExec>,
    val jarRuntime: TaskProvider<Jar>,
    val installRuntime: TaskProvider<Task>,
    val zipRuntime: TaskProvider<Zip>,
    val verifyRuntime: TaskProvider<Task>,
)

fun registerCliR8ProductTasks(product: CliR8Product): CliR8Tasks {
    val installDir = cliObfuscatedInstallDir(product)
    val outputDir = cliR8OutputDir(product)
    val jarDir = cliR8JarDir(product)
    val mappingFile = cliR8MappingFile(product)
    val runtimeJarFile = cliObfuscatedRuntimeJarFile(product)
    val archiveFile = cliReleaseArchiveFile(product)
    val extractedArchiveDir = cliExtractedArchiveDir(product)
    val capitalizedProductId = product.id.replaceFirstChar { it.uppercaseChar() }

    val copyRuntime = tasks.register<Sync>("copy${capitalizedProductId}CliRuntimeForR8") {
        group = "distribution"
        description = "Copies the ${product.executableName} runtime before R8 obfuscation."
        dependsOn("${product.projectPath}:installDist")
        from(product.installDir)
        into(installDir)
    }

    val programJarFiles = product.programJarNames.map { jarName -> installDir.map { dir -> dir.file("lib/$jarName") } }
    val libraryJarFiles = installDir.map { dir ->
        dir.dir("lib").asFileTree.matching {
            include("*.jar")
            exclude(product.programJarNames.map { name -> name })
        }
    }

    val obfuscateRuntime = tasks.register<JavaExec>("obfuscate${capitalizedProductId}CliRuntime") {
        group = "distribution"
        description = "Runs R8 over Hexana-owned ${product.executableName} runtime JARs."
        dependsOn(copyRuntime)

        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
        classpath = r8Tool
        mainClass.set("com.android.tools.r8.R8")

        inputs.file(cliR8RulesFile)
        inputs.files(programJarFiles)
        inputs.files(libraryJarFiles)
        outputs.dir(outputDir)
        outputs.file(mappingFile)

        doFirst {
            outputDir.get().asFile.deleteRecursively()
            outputDir.get().asFile.mkdirs()
            mappingFile.get().asFile.parentFile.mkdirs()
            args(
                "--classfile",
                "--release",
                "--pg-conf",
                cliR8RulesFile.asFile.absolutePath,
                "--output",
                outputDir.get().asFile.absolutePath,
                "--pg-map-output",
                mappingFile.get().asFile.absolutePath,
                "--lib",
                javaLauncher.get().metadata.installationPath.asFile.absolutePath,
            )
            libraryJarFiles.get().files.sortedBy { it.name }.forEach { jar ->
                args("--lib", jar.absolutePath)
            }
            programJarFiles.forEach { jar ->
                val jarFile = jar.get().asFile
                check(jarFile.isFile) {
                    "Missing ${product.executableName} R8 program jar: ${jarFile.absolutePath}"
                }
                args(jarFile.absolutePath)
            }
        }
    }

    val jarRuntime = tasks.register<Jar>("jarObfuscated${capitalizedProductId}CliRuntime") {
        group = "distribution"
        description = "Packs ${product.executableName} R8 output into a single obfuscated Hexana runtime JAR."
        dependsOn(obfuscateRuntime)
        from(outputDir)
        archiveFileName.set(obfuscatedRuntimeJarName)
        destinationDirectory.set(jarDir)
    }

    val installRuntime = tasks.register("installObfuscated${capitalizedProductId}CliRuntime") {
        group = "distribution"
        description = "Installs ${product.executableName} with obfuscated Hexana-owned classes."
        dependsOn(copyRuntime, jarRuntime, generateThirdPartyNotices)
        inputs.file(runtimeJarFile)
        inputs.files(installDir.map { dir -> cliReleaseHexanaJarNames.map { dir.file("lib/$it") } })
        outputs.dir(installDir)

        doLast {
            val productInstallDir = installDir.get().asFile
            val libDir = productInstallDir.resolve("lib")
            cliReleaseHexanaJarNames.forEach { jarName ->
                libDir.resolve(jarName).delete()
            }
            libDir
                .listFiles { file -> file.isFile }
                .orEmpty()
                .filter { jarNameHasAnyPrefix(it.name, mcpSdkJarNamePrefixes + ktorJarNamePrefixes + product.deniedJarNamePrefixes) }
                .forEach { it.delete() }
            runtimeJarFile.get().asFile.copyTo(
                libDir.resolve(obfuscatedRuntimeJarName),
                overwrite = true,
            )
            pluginLicenseFile.asFile.copyTo(productInstallDir.resolve("LICENSE.txt"), overwrite = true)
            thirdPartyNoticesDir.get().file(thirdPartyNoticesFileName).asFile.copyTo(
                productInstallDir.resolve(thirdPartyNoticesFileName),
                overwrite = true,
            )
            rewriteCliLauncherClasspaths(productInstallDir, product.executableName)
        }
    }

    val zipRuntime = tasks.register<Zip>("zipObfuscated${capitalizedProductId}Cli") {
        group = "distribution"
        description = "Zips the R8-obfuscated ${product.executableName} release archive."
        dependsOn(installRuntime)
        from(installDir) {
            into(cliReleaseProductRootName(product))
            filesMatching("bin/*") {
                if (!name.endsWith(".bat") && !name.endsWith(".cmd")) {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
        archiveFileName.set(cliReleaseArchiveName(product))
        destinationDirectory.set(cliReleaseDistributionDir)
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

    val verifyRuntime = tasks.register("verifyObfuscated${capitalizedProductId}CliRelease") {
        group = "verification"
        description = "Verifies the staged and archived R8-obfuscated ${product.executableName} release."
        dependsOn(zipRuntime)
        inputs.dir(installDir)
        inputs.file(mappingFile)
        inputs.file(archiveFile)
        outputs.dir(extractedArchiveDir)
        outputs.upToDateWhen { false }

        doLast {
            val productInstallDir = installDir.get().asFile
            val productArchiveFile = archiveFile.get().asFile
            val archiveExtractionDir = extractedArchiveDir.get().asFile
            assertCliReleaseRuntimeLayout(product, productInstallDir, product.executableName)
            assertJava21RuntimeClasses(productInstallDir.resolve("lib"), product.executableName)
            assertCliR8MappingPolicy(product, mappingFile.get().asFile)
            assertCliReleaseArchive(product, productArchiveFile)

            archiveExtractionDir.deleteRecursively()
            archiveExtractionDir.mkdirs()
            copy {
                from(zipTree(productArchiveFile))
                into(archiveExtractionDir)
            }
            val extractedProductRoot = assertCliExtractedReleaseRoot(product, archiveExtractionDir)
            assertCliReleaseRuntimeLayout(product, extractedProductRoot, "${product.executableName} extracted archive")
            assertJava21RuntimeClasses(
                extractedProductRoot.resolve("lib"),
                "${product.executableName} extracted archive",
            )
            assertCliReleaseProcessSmoke(product, extractedProductRoot)
        }
    }

    return CliR8Tasks(copyRuntime, obfuscateRuntime, jarRuntime, installRuntime, zipRuntime, verifyRuntime)
}

val cliR8Tasks = cliR8Products.map(::registerCliR8ProductTasks)

data class NativeCliProduct(
    val id: String,
    val executableName: String,
    val projectPath: String,
    val nativeExecutable: Provider<RegularFile>,
    val jvmInstallDir: Provider<Directory>,
    val expectedCommands: Set<String>,
    val rejectedCommands: Set<String>,
)

data class NativeCliTasks(
    val zipRuntime: TaskProvider<Zip>,
    val verifyRuntime: TaskProvider<Task>,
)

fun nativeCliArchiveName(product: NativeCliProduct, platform: String): String =
    "${product.executableName}-native-$platform-$mcpServerVersion.zip"

fun nativeCliArchiveFile(product: NativeCliProduct): Provider<RegularFile> =
    nativeDistributionDir.map { it.file(nativeCliArchiveName(product, nativeHostPlatformName)) }

fun nativeCliExtractedArchiveDir(product: NativeCliProduct): Provider<Directory> =
    nativeCliArchiveVerificationRootDir.map { it.dir(product.id) }

val nativeCliHostSupported = nativeHostPlatformName in nativeReleasePlatforms

fun requireSupportedNativeCliHost() {
    check(nativeCliHostSupported) {
        "Native CLI release archives are supported only for ${nativeReleasePlatforms.joinToString()} hosts; " +
            "current host is $nativeHostPlatformName."
    }
}

fun assertNativeCliArchive(product: NativeCliProduct, archiveFile: File) {
    check(archiveFile.name == nativeCliArchiveName(product, nativeHostPlatformName)) {
        "Unexpected ${product.executableName} native archive name: ${archiveFile.name}"
    }
    ZipFile(archiveFile).use { zip ->
        val fileEntries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .map { it.name }
            .sorted()
            .toList()
        val expectedEntries = listOf(
            "LICENSE.txt",
            thirdPartyNoticesFileName,
            "bin/${product.executableName}",
        ).sorted()
        check(fileEntries == expectedEntries) {
            "${archiveFile.name} must contain only ${expectedEntries.joinToString()}. Found: $fileEntries"
        }
        val executableEntry = zip.getEntry("bin/${product.executableName}")
        check(executableEntry != null && executableEntry.size > 0L) {
            "${archiveFile.name} contains an empty native executable entry for ${product.executableName}."
        }
    }
}

fun assertNativeCliExtractedLayout(product: NativeCliProduct, extractionDir: File): File {
    val executable = extractionDir.resolve("bin/${product.executableName}")
    val binEntries = extractionDir.resolve("bin")
        .listFiles()
        .orEmpty()
        .map { it.name }
        .sorted()
    check(binEntries == listOf(product.executableName)) {
        "${product.executableName} native archive must contain only bin/${product.executableName}. Found: $binEntries"
    }
    check(executable.isFile && executable.length() > 0L) {
        "${product.executableName} native archive executable is missing or empty: ${executable.absolutePath}"
    }
    check(executable.canExecute()) {
        "${product.executableName} native archive executable bit is not preserved: ${executable.absolutePath}"
    }
    assertSameFileBytes(extractionDir.resolve("LICENSE.txt"), pluginLicenseFile.asFile, "${product.executableName} native LICENSE.txt")
    assertSameFileBytes(
        extractionDir.resolve(thirdPartyNoticesFileName),
        thirdPartyNoticesDir.get().file(thirdPartyNoticesFileName).asFile,
        "${product.executableName} native $thirdPartyNoticesFileName",
    )
    val topLevelFiles = extractionDir
        .listFiles { file -> file.isFile }
        .orEmpty()
        .map { it.name }
        .sorted()
    check(topLevelFiles == listOf("LICENSE.txt", thirdPartyNoticesFileName).sorted()) {
        "${product.executableName} native archive has unexpected top-level files: $topLevelFiles"
    }
    val topLevelDirs = extractionDir
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .map { it.name }
        .sorted()
    check(topLevelDirs == listOf("bin")) {
        "${product.executableName} native archive has unexpected top-level directories: $topLevelDirs"
    }
    return executable
}

fun runCliWithTelemetryOptOut(commandLine: List<String>, workingDir: File): ProcessResult =
    runProcess(commandLine, workingDir, environment = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"))

fun runJvmCliReferenceWithTelemetryOptOut(commandLine: List<String>, workingDir: File): ProcessResult =
    runProcess(
        commandLine,
        workingDir,
        environment = mapOf(
            "HEXANA_CLI_TELEMETRY_ENABLED" to "false",
            "JAVA_HOME" to jvmCliReferenceJavaHome.get(),
        ),
    )

fun assertNativeAndJvmCliAgreement(
    product: NativeCliProduct,
    nativeExecutable: File,
    jvmExecutable: File,
    args: List<String>,
    workingDir: File,
) {
    val nativeResult = runCliWithTelemetryOptOut(listOf(nativeExecutable.absolutePath) + args, workingDir)
    val jvmResult = runJvmCliReferenceWithTelemetryOptOut(listOf(jvmExecutable.absolutePath) + args, workingDir)
    check(nativeResult.exitCode == jvmResult.exitCode && nativeResult.output == jvmResult.output) {
        """
        ${product.executableName} native/JVM disagreement for `${args.joinToString(" ")}`.
        Native exit ${nativeResult.exitCode}:
        ${nativeResult.output}
        JVM exit ${jvmResult.exitCode}:
        ${jvmResult.output}
        """.trimIndent()
    }
}

fun parseCliToolCommandRows(output: String): Set<String> =
    output
        .lineSequence()
        .drop(1)
        .map { it.trimEnd() }
        .filter { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("\t") }
        .toSet()

fun assertNativeCliProductSmoke(product: NativeCliProduct, nativeExecutable: File, jvmInstallDir: File, workingDir: File) {
    val jvmExecutable = jvmInstallDir.resolve("bin/${product.executableName}")
    check(jvmExecutable.canExecute()) {
        "Expected JVM ${product.executableName} executable at ${jvmExecutable.absolutePath}."
    }

    assertNativeAndJvmCliAgreement(product, nativeExecutable, jvmExecutable, listOf("version"), workingDir)
    val version = runCliWithTelemetryOptOut(listOf(nativeExecutable.absolutePath, "version"), workingDir)
    check(version.exitCode == 0 && version.output.trim() == "${product.executableName} $mcpServerVersion") {
        "Expected `${product.executableName} version` to report $mcpServerVersion, got exit ${version.exitCode}:\n${version.output}"
    }

    assertNativeAndJvmCliAgreement(product, nativeExecutable, jvmExecutable, listOf("tools"), workingDir)
    val tools = runCliWithTelemetryOptOut(listOf(nativeExecutable.absolutePath, "tools"), workingDir)
    check(tools.exitCode == 0) {
        "Expected `${product.executableName} tools` to succeed, got exit ${tools.exitCode}:\n${tools.output}"
    }
    val inventory = parseCliToolCommandRows(tools.output)
    check(inventory == product.expectedCommands) {
        "${product.executableName} tools output must expose exactly ${product.expectedCommands}. Found: $inventory"
    }
    product.rejectedCommands.forEach { command ->
        val rejected = runCliWithTelemetryOptOut(listOf(nativeExecutable.absolutePath, command), workingDir)
        check(rejected.exitCode == 2 && rejected.output.contains("no such subcommand")) {
            "Expected `${product.executableName} $command` to be rejected, got exit ${rejected.exitCode}:\n${rejected.output}"
        }
    }

    val wasmFixture = workingDir.resolve("${product.id}-native-cli-smoke.wasm")
    wasmFixture.writeBytes(minimalWasmModuleBytes())
    if (product.executableName == "hexana-cli") {
        assertNativeAndJvmCliAgreement(
            product,
            nativeExecutable,
            jvmExecutable,
            listOf("summarize-binary", wasmFixture.absolutePath, "--format", "json"),
            workingDir,
        )
        val missing = workingDir.resolve("missing-${System.nanoTime()}.wasm")
        assertNativeAndJvmCliAgreement(
            product,
            nativeExecutable,
            jvmExecutable,
            listOf("wasm-to-wat", missing.absolutePath, "--format", "raw"),
            workingDir,
        )
    } else {
        assertNativeAndJvmCliAgreement(
            product,
            nativeExecutable,
            jvmExecutable,
            listOf(
                "run-script",
                wasmFixture.absolutePath,
                "--script-text",
                "print(binary.bytes.size);",
                "--format",
                "raw",
            ),
            workingDir,
        )
        assertNativeAndJvmCliAgreement(
            product,
            nativeExecutable,
            jvmExecutable,
            listOf(
                "run-script",
                wasmFixture.absolutePath,
                "--script-text",
                "throw new Error('native-smoke');",
                "--format",
                "raw",
            ),
            workingDir,
        )
    }
    wasmFixture.delete()
}

fun registerNativeCliProductTasks(product: NativeCliProduct): NativeCliTasks {
    val archiveFile = nativeCliArchiveFile(product)
    val extractedArchiveDir = nativeCliExtractedArchiveDir(product)
    val capitalizedProductId = product.id.replaceFirstChar { it.uppercaseChar() }

    val zipRuntime = tasks.register<Zip>("zipNative${capitalizedProductId}Cli") {
        group = "distribution"
        description = "Zip the current host's GraalVM native ${product.executableName} executable into mcp/build/distributions-native."
        if (nativeCliHostSupported) {
            dependsOn("${product.projectPath}:nativeCompile", generateThirdPartyNotices)
        }
        doFirst {
            requireSupportedNativeCliHost()
        }
        from(product.nativeExecutable) {
            into("bin")
            rename { product.executableName }
            filesMatching(product.executableName) {
                permissions { unix("rwxr-xr-x") }
            }
        }
        from(pluginLicenseFile)
        from(generateThirdPartyNotices)
        archiveFileName.set(nativeCliArchiveName(product, nativeHostPlatformName))
        destinationDirectory.set(nativeDistributionDir)
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

    val verifyRuntime = tasks.register("verifyNative${capitalizedProductId}CliRelease") {
        group = "verification"
        description = "Verifies the current-host native ${product.executableName} release archive."
        if (nativeCliHostSupported) {
            dependsOn(zipRuntime, "${product.projectPath}:installDist")
        }
        inputs.file(archiveFile)
        inputs.dir(product.jvmInstallDir)
        outputs.dir(extractedArchiveDir)
        outputs.upToDateWhen { false }

        doLast {
            requireSupportedNativeCliHost()
            val productArchiveFile = archiveFile.get().asFile
            val archiveExtractionDir = extractedArchiveDir.get().asFile
            assertNativeCliArchive(product, productArchiveFile)

            archiveExtractionDir.deleteRecursively()
            archiveExtractionDir.mkdirs()
            copy {
                from(zipTree(productArchiveFile))
                into(archiveExtractionDir)
            }

            val nativeExecutable = assertNativeCliExtractedLayout(product, archiveExtractionDir)
            assertNativeCliProductSmoke(
                product,
                nativeExecutable,
                product.jvmInstallDir.get().asFile,
                archiveExtractionDir,
            )
        }
    }

    return NativeCliTasks(zipRuntime, verifyRuntime)
}

val nativeStandardCliProduct = NativeCliProduct(
    id = "standard",
    executableName = "hexana-cli",
    projectPath = ":cli:hexana-cli",
    nativeExecutable = project(":cli:hexana-cli").layout.buildDirectory.file("native/nativeCompile/hexana-cli"),
    jvmInstallDir = project(":cli:hexana-cli").layout.buildDirectory.dir("install/hexana-cli"),
    expectedCommands = standardCliR8Product.expectedCommands,
    rejectedCommands = standardCliR8Product.rejectedCommands,
)

val nativeScriptCliProduct = NativeCliProduct(
    id = "script",
    executableName = "hexana-cli-script",
    projectPath = ":cli:hexana-cli-script",
    nativeExecutable = project(":cli:hexana-cli-script").layout.buildDirectory.file("native/nativeCompile/hexana-cli-script"),
    jvmInstallDir = project(":cli:hexana-cli-script").layout.buildDirectory.dir("install/hexana-cli-script"),
    expectedCommands = scriptCliR8Product.expectedCommands,
    rejectedCommands = scriptCliR8Product.rejectedCommands,
)

val nativeCliProducts = listOf(nativeStandardCliProduct, nativeScriptCliProduct)
val nativeCliTasks = nativeCliProducts.map(::registerNativeCliProductTasks)

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

val generateClaudeDesktopMcpbManifest = tasks.register<JavaExec>("generateClaudeDesktopMcpbManifest") {
    group = "generation"
    description = "Generate the Claude Desktop MCPB manifest from the MCP tool catalog."
    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.McpbManifestMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            claudeDesktopMcpbManifestTemplate.asFile.absolutePath,
            claudeDesktopMcpbGeneratedManifest.get().asFile.absolutePath,
            mcpServerVersion,
            mcpbHostPlatformName.get(),
        )
    })
    inputs.file(claudeDesktopMcpbManifestTemplate)
    inputs.property("mcpServerVersion", mcpServerVersion)
    inputs.property("mcpbHostPlatformName", mcpbHostPlatformName)
    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.file(claudeDesktopMcpbGeneratedManifest)
    dependsOn(":mcp:tools-generator:classes")
}

tasks.register<JavaExec>("generateClaudeDesktopMcpbManifestTemplate") {
    group = "generation"
    description = "Regenerate the checked-in Claude Desktop MCPB manifest template from the MCP tool catalog."
    classpath = project(":mcp:tools-generator").extensions
        .getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.McpbManifestMainKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            claudeDesktopMcpbManifestTemplate.asFile.absolutePath,
            claudeDesktopMcpbManifestTemplate.asFile.absolutePath,
            GENERATED_VERSION_TOKEN,
            "__GENERATED_MCPB_PLATFORM__",
        )
    })
    inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
    outputs.file(claudeDesktopMcpbManifestTemplate)
    dependsOn(":mcp:tools-generator:classes")
}

val regenerateClaudeDesktopMcpbManifestTemplateForVerification =
    tasks.register<JavaExec>("regenerateClaudeDesktopMcpbManifestTemplateForVerification") {
        group = "verification"
        description = "Re-emits the Claude Desktop MCPB manifest template into build/ for drift verification."
        classpath = project(":mcp:tools-generator").extensions
            .getByType<SourceSetContainer>()
            .named("main").get().runtimeClasspath
        mainClass.set("org.jetbrains.hexana.mcp.tools.generator.McpbManifestMainKt")
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                claudeDesktopMcpbManifestTemplate.asFile.absolutePath,
                claudeDesktopMcpbVerifiedManifest.get().asFile.absolutePath,
                GENERATED_VERSION_TOKEN,
                "__GENERATED_MCPB_PLATFORM__",
            )
        })
        inputs.file(claudeDesktopMcpbManifestTemplate)
        inputs.files(project(":mcp:tools-generator").tasks.named("classes"))
        outputs.file(claudeDesktopMcpbVerifiedManifest)
        dependsOn(":mcp:tools-generator:classes")
    }

tasks.register("verifyClaudeDesktopMcpbManifestTemplateUpToDate") {
    group = "verification"
    description = "Fails if the checked-in Claude Desktop MCPB manifest template drifts from the MCP tool catalog."
    dependsOn(regenerateClaudeDesktopMcpbManifestTemplateForVerification)
    mustRunAfter(tasks.named("generateClaudeDesktopMcpbManifestTemplate"))

    inputs.file(claudeDesktopMcpbManifestTemplate)
    inputs.file(claudeDesktopMcpbVerifiedManifest)

    doLast {
        val committed = claudeDesktopMcpbManifestTemplate.asFile
        val regenerated = claudeDesktopMcpbVerifiedManifest.get().asFile
        if (!committed.exists()) {
            error("Committed Claude Desktop MCPB manifest template is missing at ${committed.absolutePath}.")
        }
        if (committed.readText().replace("\r\n", "\n") != regenerated.readText().replace("\r\n", "\n")) {
            error(
                "Claude Desktop MCPB manifest template is out of date with the MCP tool catalog.\n" +
                    "  Committed:   ${committed.absolutePath}\n" +
                    "  Regenerated: ${regenerated.absolutePath}\n" +
                    "  Fix: ./gradlew :mcp:generateClaudeDesktopMcpbManifestTemplate, then commit " +
                    "mcp/packaging/claude-desktop-mcpb/manifest.json."
            )
        }
    }
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
    dependsOn(
        installObfuscatedMcpServerRuntime,
        generateClaudeDesktopMcpbManifest,
        prepareClaudeDesktopMcpbNativeExecutable,
        generateThirdPartyNotices,
    )

    from(layout.projectDirectory.dir("packaging/claude-desktop-mcpb")) {
        exclude("manifest.json")
        filesMatching("bin/hexana-mcp-claude-desktop") {
            permissions { unix("rwxr-xr-x") }
        }
    }
    from(claudeDesktopMcpbGeneratedManifest)
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

val assertMcpbCliAvailable = tasks.register("assertMcpbCliAvailable") {
    group = "verification"
    description = "Assert that the official mcpb CLI is installed on PATH."

    doLast {
        runRequiredMcpbCommand(listOf("--help"), printOutput = false)
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

val packClaudeDesktopMcpb = tasks.register("packClaudeDesktopMcpb") {
    group = "distribution"
    description = "Pack the local Claude Desktop MCPB bundle with the official mcpb CLI."
    dependsOn(assertMcpbCliAvailable, "mcpbValidateClaudeDesktopMcpb", "validateClaudeDesktopMcpbManifest", "validateClaudeDesktopMcpbNativeRuntime")

    inputs.dir(claudeDesktopMcpbDir)
    outputs.file(claudeDesktopMcpbArchive)

    doLast {
        val outputFile = claudeDesktopMcpbArchive.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.delete()
        runRequiredMcpbCommand(
            listOf("pack", claudeDesktopMcpbDir.get().asFile.absolutePath, outputFile.absolutePath)
        )
    }
}

tasks.register("claudeDesktopMcpbSmoke") {
    group = "verification"
    description = "Smoke-test the staged local Claude Desktop MCPB layout and packed archive."
    dependsOn(packClaudeDesktopMcpb)

    val packageDir = claudeDesktopMcpbDir
    val archiveFile = claudeDesktopMcpbArchive
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
                "--allowed-root",
                allowedRoot.absolutePath,
                "--allowed-root",
                secondAllowedRoot.absolutePath,
                "env-begin",
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
    dependsOn(assertMcpbCliAvailable, assembleClaudeDesktopMcpbPackage)

    doLast {
        runRequiredMcpbCommand(listOf("validate", claudeDesktopMcpbDir.get().asFile.absolutePath))
    }
}

tasks.register("mcpbInfoClaudeDesktopMcpb") {
    group = "verification"
    description = "Run the official mcpb CLI info command against the packed Claude Desktop MCPB."
    dependsOn(assertMcpbCliAvailable, packClaudeDesktopMcpb)

    doLast {
        runRequiredMcpbCommand(
            listOf("info", claudeDesktopMcpbArchive.get().asFile.absolutePath)
        )
    }
}

tasks.register("assembleClaudeDesktopMcpb") {
    group = "distribution"
    description = "Assemble, validate, pack with mcpb, inspect, and smoke-test the local Claude Desktop MCPB bundle."
    dependsOn(packClaudeDesktopMcpb, "mcpbInfoClaudeDesktopMcpb", "claudeDesktopMcpbSmoke")
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

tasks.register("assembleCliR8ReleaseArchives") {
    group = "distribution"
    description = "Assemble R8-obfuscated JVM release archives for hexana-cli and hexana-cli-script."
    dependsOn(cliR8Tasks.map { it.zipRuntime })
}

tasks.register("verifyCliR8ReleaseArchives") {
    group = "verification"
    description = "Verify R8-obfuscated JVM release archives for hexana-cli and hexana-cli-script."
    dependsOn(cliR8Tasks.map { it.verifyRuntime })
}

tasks.register("assembleNativeCliReleaseArchives") {
    group = "distribution"
    description = "Assemble current-host native release archives for hexana-cli and hexana-cli-script."
    if (nativeCliHostSupported) {
        dependsOn(nativeCliTasks.map { it.zipRuntime })
    }
    doFirst {
        requireSupportedNativeCliHost()
    }
}

tasks.register("verifyNativeCliReleaseArchives") {
    group = "verification"
    description = "Verify current-host native release archives for hexana-cli and hexana-cli-script."
    if (nativeCliHostSupported) {
        dependsOn(nativeCliTasks.map { it.verifyRuntime })
    }
    outputs.upToDateWhen { false }

    doLast {
        requireSupportedNativeCliHost()
        val versionOutputs = nativeCliProducts.associate { product ->
            val executable = nativeCliExtractedArchiveDir(product).get().asFile.resolve("bin/${product.executableName}")
            val result = runCliWithTelemetryOptOut(listOf(executable.absolutePath, "version"), executable.parentFile)
            check(result.exitCode == 0) {
                "Expected `${product.executableName} version` to succeed, got exit ${result.exitCode}:\n${result.output}"
            }
            product.executableName to result.output.trim()
        }
        val versions = versionOutputs.mapValues { (_, output) -> output.substringAfter(' ') }
        check(versions.values.toSet() == setOf(mcpServerVersion)) {
            "Native CLI products must share version $mcpServerVersion. Observed: $versionOutputs"
        }
    }
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
        ":cli:integration-tests:nativeCliIntegrationTest",
        ":mcp:server:nativeMcpVersionSmoke",
        ":mcp:server:nativeMcpStdioSmoke",
        ":mcp:server:nativeMcpHttpSmoke",
        "verifyNativeCliReleaseArchives",
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
    check(compatibility["platforms"] == listOf(mcpbHostPlatformName.get())) {
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

    listOf("summarize-binary", "summarize-module", "wasm-to-wat", "run-script", "tools").forEach { command ->
        val rejected = runProcess(listOf(nativeRuntime.absolutePath, command), workingDir)
        check(rejected.exitCode != 0 && rejected.output.contains("no such subcommand")) {
            """
            MCPB native executable exposes removed direct command `$command`.
            Expected a no-such-subcommand failure, got exit ${rejected.exitCode}:
            ${rejected.output}
            Build the MCPB native override from the current branch/foundation, or omit the override so `:mcp:server:nativeCompile` is used.
            """.trimIndent()
        }
    }
}

fun minimalWasmModuleBytes(): ByteArray = byteArrayOf(
    0x00, 0x61, 0x73, 0x6d,
    0x01, 0x00, 0x00, 0x00,
    0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
    0x03, 0x02, 0x01, 0x00,
    0x05, 0x03, 0x01, 0x00, 0x01,
    0x07, 0x08, 0x01, 0x04, 0x6d, 0x61, 0x69, 0x6e, 0x00, 0x00,
    0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b,
)

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
        nativePlatformName.startsWith("windows-") -> error(
            "Claude Desktop MCPB packaging is not supported on Windows until a Windows entrypoint is added."
        )
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
    processBuilder.environment().keys.removeAll { it == "JAVA_TOOL_OPTIONS" || it == "JDK_JAVA_OPTIONS" }
    processBuilder.environment().putAll(environment)
    val process = processBuilder
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.waitFor(), output)
}

fun runRequiredMcpbCommand(args: List<String>, printOutput: Boolean = true) {
    val cli = "mcpb"
    val result = try {
        runProcess(listOf(cli) + args, projectDir)
    } catch (error: java.io.IOException) {
        error("Could not run `$cli`: ${error.message}. Install the official MCPB CLI before running Claude Desktop MCPB tasks.")
    }
    check(result.exitCode == 0) {
        "mcpb ${args.joinToString(" ")} failed with exit ${result.exitCode}:\n${result.output}"
    }
    if (printOutput && result.output.isNotBlank()) {
        println(result.output.trimEnd())
    }
}

fun String.isLoopbackHost(): Boolean {
    return runCatching { InetAddress.getByName(this).isLoopbackAddress }
        .getOrDefault(false)
}
