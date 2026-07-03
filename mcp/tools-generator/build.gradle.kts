plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana"

dependencies {
    implementation(libs.kotlinpoet.jvm)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt"
}

val runToolsetGenerator by tasks.registering(JavaExec::class) {
    description = "Runs the idea-plugin toolset generator; pass the output root as a single argument."
    group = "generation"
    classpath = sourceSets.getByName("main").runtimeClasspath
    mainClass.set("org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt")
}

kotlin {
    jvmToolchain(21)
}
