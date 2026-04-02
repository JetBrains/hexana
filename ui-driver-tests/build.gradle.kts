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
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.intellij.starter.squashed)
    testImplementation(libs.intellij.starter.junit5) {
        exclude(group = "com.jetbrains.intellij.platform", module = "test-framework-junit5")
    }
    testImplementation(libs.intellij.starter.driver)
    testImplementation(libs.intellij.driver.client)
    testImplementation(libs.intellij.driver.sdk)
    testImplementation(libs.intellij.driver.model)
    testImplementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
