plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"

base {
    archivesName.set("cli-product")
}

dependencies {
    api(project(":cli:runtime"))
    implementation(project(":analytics"))
    implementation(project(":cli:core"))
    implementation(project(":cli:scripting"))
    implementation(project(":mcp:core"))
    implementation(project(":mcp:scripting"))
    implementation(project(":mcp:server"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}
