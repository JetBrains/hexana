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
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        maven("https://cache-redirector.jetbrains.com/maven-central")
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

include("binaryProvider")
include("disassembly")
include("disassembly:core")
include("disassembly:llvm-objdump")
include("disassembly:capstone-wasm")
include("parsing")
include("parsing:common")
include("parsing:elf")
include("parsing:macho")
include("parsing:pe")
include("parsing:javaBytecode")
include("parsing:zip")
include("jvm-jit")
include("debugInfo:dwarf")
include("wasmParser")
include("encdec")
include("hexanaDocument")
include("plugins-shared")
include("mcp:core")
include("mcp:server")
include("mcp:tools-generator")
include("mcp:vscode-server")
include("idea-plugin")
include("graalwasm:wasmprinter")
include("graalwasm:wat2wasm")
include("graalwasm:common")
include("analytics")
include("vscode-plugin:webview")
include("vscode-plugin:extension-lib")
include("ui-driver-tests")
include("jvm-jit")
