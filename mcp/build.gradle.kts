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
    rewriteMcpJson: Boolean = true,
) = tasks.register<Sync>(taskName) {
    dependsOn(":mcp:server:installDist")
    from(layout.projectDirectory.dir("packaging/$packagingDirName")) {
        filesMatching(pluginManifestPath) {
            filter { line -> line.replace(GENERATED_VERSION_TOKEN, mcpServerVersion) }
        }
    }
    from(standaloneInstallDir) {
        into("server")
    }
    into(layout.buildDirectory.dir("packaging/$packagingDirName"))
    if (rewriteMcpJson) {
        doLast {
            val launcherPath = destinationDir.resolve("bin/hexana-mcp").absolutePath
            destinationDir.resolve(".mcp.json").writeText(renderMcpConfig(launcherPath))
        }
    }
}

val assembleClaudePluginPackage = registerPackagingSync(
    taskName = "assembleClaudePluginPackage",
    packagingDirName = "claude-plugin",
    pluginManifestPath = ".claude-plugin/plugin.json",
    rewriteMcpJson = false,
)

val assembleCodexPluginPackage = registerPackagingSync(
    taskName = "assembleCodexPluginPackage",
    packagingDirName = "codex-plugin",
    pluginManifestPath = ".codex-plugin/plugin.json",
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

tasks.register("assembleMcpPackaging") {
    group = "distribution"
    description = "Assemble self-contained Claude and Codex MCP wrapper layouts under mcp/build/packaging."
    dependsOn(assembleClaudePluginPackage, assembleCodexPluginPackage)
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
