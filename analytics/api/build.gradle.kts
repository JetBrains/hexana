import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * Writes the build-time PostHog key into a `commonMain` source file. The key is a task input, so it
 * takes part in up-to-date and build-cache checks, and it is never logged.
 */
@CacheableTask
abstract class GenerateAnalyticsBuildConfig : DefaultTask() {
    @get:Input
    abstract val apiKey: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val key = apiKey.get()
        check(key.matches(Regex("[A-Za-z0-9_-]*"))) {
            "The PostHog API key may contain only letters, digits, '_' and '-'."
        }
        val file = outputDirectory.get().file("org/jetbrains/hexana/analytics/AnalyticsBuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package org.jetbrains.hexana.analytics

            internal object AnalyticsBuildConfig {
                const val API_KEY = "$key"
            }
            """.trimIndent() + "\n",
        )
    }
}

val posthogApiKey: Provider<String> = providers.gradleProperty("hexana.posthog.apiKey")
    .orElse(providers.environmentVariable("HEXANA_POSTHOG_BUILD_API_KEY"))
    .map { it.trim() }
    .orElse("")

val releaseBuild: Provider<Boolean> = providers.gradleProperty("hexana.release")
    .orElse(providers.environmentVariable("HEXANA_RELEASE_BUILD"))
    .map { it.trim().equals("true", ignoreCase = true) }
    .orElse(false)

val verifyAnalyticsReleaseKey = tasks.register("verifyAnalyticsReleaseKey") {
    group = "verification"
    description = "Fails a release build (hexana.release=true) that has no PostHog API key to bake in."

    val keyPresent = posthogApiKey.map { it.isNotEmpty() }
    val release = releaseBuild
    doLast {
        if (release.get() && !keyPresent.get()) {
            throw GradleException(
                "Release build without a PostHog API key: set the 'hexana.posthog.apiKey' Gradle property or " +
                    "the HEXANA_POSTHOG_BUILD_API_KEY environment variable. A release build without a key " +
                    "would ship with analytics permanently disabled.",
            )
        }
    }
}

val generateAnalyticsBuildConfig = tasks.register<GenerateAnalyticsBuildConfig>("generateAnalyticsBuildConfig") {
    description = "Generates AnalyticsBuildConfig with the build-time PostHog key for commonMain."
    group = "generation"
    apiKey.set(posthogApiKey)
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/analyticsBuildConfig/commonMain/kotlin"))
    dependsOn(verifyAnalyticsReleaseKey)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js(IR) {
        nodejs()
        browser()
        binaries.library()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAnalyticsBuildConfig.map { it.outputDirectory })
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
