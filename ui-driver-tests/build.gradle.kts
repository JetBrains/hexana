import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven(url = "https://cache-redirector.jetbrains.com/intellij-dependencies")
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    maven(url = "https://www.jetbrains.com/intellij-repository/snapshots")
    maven(url = "https://download.jetbrains.com/teamcity-repository")
    maven(url = "https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/grazi/grazie-platform-public")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    testImplementation(libs.bundles.intellij.driver)
    testImplementation(libs.intellij.starter.junit5) {
        exclude(group = "com.jetbrains.intellij.platform", module = "test-framework-junit5")
    }
    testImplementation(libs.mcp.kotlin.sdk.jvm)
    testImplementation(libs.ktor.client.cio.jvm)
    testImplementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Aggregator task driven by ci-configuration's `UI Driver Tests` build.
// The DSL probes for this task and, when present, runs it instead of the hardcoded
// `--tests` triplet kept in `.teamcity/legacy/ReleaseZeroNineFallback.kt`. Keep the
// surface broad on purpose: any new UI driver test class added to this module is
// picked up automatically without a DSL change.
tasks.register("teamcityUiDriverTests") {
    group = "verification"
    description = "Aggregate every UI driver test for the TeamCity UI Driver Tests build."
    dependsOn(tasks.test)
}
