import java.net.InetAddress
import java.util.*

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
val obfuscatedInstallDir = project(":mcp").layout.buildDirectory.dir("install/server-r8")
val publicMarketplaceDir = layout.projectDirectory.dir("out/teamcity/public-marketplace")
val r8RulesFile = layout.projectDirectory.file("packaging/r8-rules.pro")
val r8OwnedJarNames = setOf(
    "analytics-jvm.jar",
    "binaryProvider-jvm.jar",
    "core.jar",
    "dwarf-jvm.jar",
    "encdec-jvm.jar",
    "hexanaDocument-jvm.jar",
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
) = tasks.register<Sync>(taskName) {
    dependsOn(runtimeTask)
    from(layout.projectDirectory.dir("packaging/$packagingDirName")) {
        filesMatching(pluginManifestPath) {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(runtimeInstallDir) {
        into("server")
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
    dependsOn(installObfuscatedMcpServerRuntime)
    from(obfuscatedInstallDir) {
        into("server")
        filesMatching("bin/server") {
            permissions { unix("rwxr-xr-x") }
        }
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

tasks.register<Sync>("installRepoLocalClaudePlugin") {
    group = "distribution"
    description = "Install the assembled Claude plugin into the repo-local plugins/hexana path for /plugin testing."
    dependsOn(":mcp:server:installDist")
    from(layout.projectDirectory.dir("packaging/claude-plugin")) {
        filesMatching(".claude-plugin/plugin.json") {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(standaloneInstallDir) {
        into("server")
    }
    into(rootProject.layout.projectDirectory.dir("plugins/hexana"))
}

tasks.register<Sync>("installRepoLocalCodexPlugin") {
    group = "distribution"
    description = "Install the assembled Codex plugin into the repo-local plugins/hexana path for /plugins testing."
    dependsOn(":mcp:server:installDist")
    from(layout.projectDirectory.dir("packaging/codex-plugin")) {
        filesMatching(".codex-plugin/plugin.json") {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(standaloneInstallDir) {
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
        filesMatching(listOf("bin/hexana-mcp", "server/bin/*")) {
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

tasks.register<Sync>("assemblePublicMarketplaceBundle") {
    group = "distribution"
    description = "Assemble the public marketplace bundle consumed by TeamCity release automation."
    dependsOn(assembleClaudePluginPackage, assembleCodexPluginPackage)
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
            - `server/`: bundled standalone Hexana MCP runtime.
            - `skills/wasm-inspector/`: Codex skill metadata and instructions.

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
              ]
            }
            """.trimIndent() + "\n"
        )
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

fun String.isLoopbackHost(): Boolean {
    return runCatching { InetAddress.getByName(this).isLoopbackAddress }
        .getOrDefault(false)
}
