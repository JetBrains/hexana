plugins {
    kotlin("jvm")
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
    testImplementation("com.jetbrains.intellij.tools:ide-starter-squashed:LATEST-EAP-SNAPSHOT")
    testImplementation("com.jetbrains.intellij.tools:ide-starter-junit5:LATEST-EAP-SNAPSHOT") {
        exclude(group = "com.jetbrains.intellij.platform", module = "test-framework-junit5")
    }
    testImplementation("com.jetbrains.intellij.tools:ide-starter-driver:LATEST-EAP-SNAPSHOT")
    testImplementation("com.jetbrains.intellij.driver:driver-client:LATEST-EAP-SNAPSHOT")
    testImplementation("com.jetbrains.intellij.driver:driver-sdk:LATEST-EAP-SNAPSHOT")
    testImplementation("com.jetbrains.intellij.driver:driver-model:LATEST-EAP-SNAPSHOT")
    testImplementation(libs.kotlinx.serialization.json)

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
