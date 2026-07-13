plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

base {
    archivesName.set("cli-core")
}

dependencies {
    api(project(":cli:runtime"))
    api(project(":mcp:core"))
    implementation(project(":graalwasm:wasmprinter"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}
