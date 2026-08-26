import java.util.Properties

val hexanaToolsVersion = providers.gradleProperty("hexanaToolsVersion")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: error("Missing `hexanaToolsVersion` in root gradle.properties")
val mcpServerPropertiesFile = file("mcp/server/gradle.properties")
val mcpServerVersion = Properties().apply {
    require(mcpServerPropertiesFile.isFile) {
        "Missing MCP server compatibility properties file: ${mcpServerPropertiesFile.absolutePath}"
    }
    mcpServerPropertiesFile.inputStream().use(::load)
}.getProperty("mcpServerVersion")?.takeIf { it.isNotBlank() }
    ?: error("Missing `mcpServerVersion` in ${mcpServerPropertiesFile.absolutePath}")
require(mcpServerVersion == hexanaToolsVersion) {
    "mcpServerVersion compatibility alias ($mcpServerVersion) must match " +
        "hexanaToolsVersion ($hexanaToolsVersion)."
}

val testIdeType = gradle.startParameter.projectProperties["testIdeType"]
val testIdeVersion = gradle.startParameter.projectProperties["testIdeVersion"]
val testSinceBuild = gradle.startParameter.projectProperties["testSinceBuild"]

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // Route Maven Central and the Gradle Plugin Portal through the
        // JetBrains cache-redirector. Direct access to repo.maven.apache.org
        // and plugins.gradle.org from TeamCity agents is rate-limited and
        // returns HTTP 429, breaking dependency resolution. See INTERNAL-TICKET /
        // INTERNAL-TICKET and #infra-channel.
        //
        // Order matters: maven-central must come first. The plugin-portal
        // mirror also proxies Maven Central artifacts (POMs), but its JARs
        // for non-plugin coordinates are not always cached. Gradle picks
        // the first repo where the POM resolves and does not fall through
        // when the JAR is missing — so putting plugin-portal first causes
        // failures for plugin transitive deps like com.google.j2objc:*
        // and com.google.errorprone:* (seen on koog the same way).
        maven("https://cache-redirector.jetbrains.com/maven-central")
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("ideLibs") {
            from(files("gradle/intellij-platform.versions.toml"))

            testIdeType?.let { version("defaultIdeType", it) }
            testIdeVersion?.let { version("defaultIdeVersion", it) }
            testSinceBuild?.let { version("defaultSinceBuild", it) }
        }
    }
}

rootProject.name = "hexana"

val includedProjectPaths = linkedSetOf<String>()

fun includeProject(path: String) {
    val normalizedPath = if (path.startsWith(":")) path else ":$path"
    require(includedProjectPaths.add(normalizedPath)) {
        "Duplicate Gradle include: $normalizedPath"
    }
    include(path)
}

includeProject("binaryProvider")
includeProject("disassembly")
includeProject("disassembly:core")
includeProject("disassembly:llvm-objdump")
includeProject("disassembly:capstone-wasm")
includeProject("parsing")
includeProject("parsing:common")
includeProject("parsing:elf")
includeProject("parsing:macho")
includeProject("parsing:pe")
includeProject("parsing:javaBytecode")
includeProject("parsing:dex")
includeProject("parsing:protobuf")
includeProject("parsing:zip")
includeProject("parsing:tar")
includeProject("jvm-jit")
includeProject("jvm-jit-jmh")
includeProject("debugInfo:dwarf")
includeProject("wasmParser")
includeProject("encdec")
includeProject("hexanaDocument")
includeProject("plugins-shared")
includeProject("cli:runtime")
includeProject("cli:metadata")
includeProject("cli:core")
includeProject("cli:scripting")
includeProject("cli:product")
includeProject("cli:hexana-cli")
includeProject("cli:hexana-cli-script")
includeProject("cli:integration-tests")
includeProject("mcp:core")
includeProject("mcp:scripting")
includeProject("mcp:server")
includeProject("mcp:tools-generator")
includeProject("mcp:vscode-server")
includeProject("idea-plugin")
includeProject("graalwasm:wasmprinter")
includeProject("graalwasm:wat2wasm")
includeProject("graalwasm:common")
includeProject("parsing:flatbuffers")
includeProject("parsing:thrift")
includeProject("parsing:codec")
includeProject("parsing:arrow")
includeProject("parsing:parquet")
includeProject("parsing:pcap")
includeProject("analytics")
includeProject("analytics:api")
includeProject("analytics:browser")
includeProject("web-ui")
includeProject("webapp")
includeProject("vscode-plugin:webview")
includeProject("vscode-plugin:extension-lib")
includeProject("ui-driver-tests")
includeProject("patronus-config")
project(":patronus-config").projectDir = file(".patronus/patronus-config")
