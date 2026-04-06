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
        gradlePluginPortal()
        mavenCentral()
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
include("wasmParser")
include("encdec")
include("mcp:core")
include("idea-plugin")
include("graalwasm:wasmprinter")
include("graalwasm:wat2wasm")
include("graalwasm:common")
include("ui-driver-tests")
