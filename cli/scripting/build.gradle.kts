import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "org.jetbrains.hexana.cli"

base {
    archivesName.set("cli-scripting")
}

val cliScriptingPublishedApiClasspath = configurations.create("cliScriptingPublishedApiClasspath") {
    description = "Classpath used by the isolated cli:scripting public API consumer proof."
    isCanBeResolved = true
    isCanBeConsumed = false
    extendsFrom(configurations.named("api").get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val apiConsumerSourceSet = sourceSets.create("apiConsumer") {
    java.srcDir("src/apiConsumer/kotlin")
    compileClasspath += files(tasks.named("jar")) + cliScriptingPublishedApiClasspath
    runtimeClasspath += output + files(tasks.named("jar")) + configurations.named("runtimeClasspath").get()
}

dependencies {
    api(project(":cli:runtime"))
    api(project(":mcp:core"))
    api(project(":mcp:scripting"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(21)
}

val verifyCliScriptingBoundary = tasks.register("verifyCliScriptingBoundary") {
    description = "Fails if cli:scripting declares dependencies outside the neutral script CLI boundary."
    group = "verification"

    doLast {
        val forbiddenProjectPaths = setOf(
            ":analytics",
            ":cli:core",
            ":idea-plugin",
            ":mcp:server",
            ":mcp:tools-generator",
        )
        val forbiddenGroups = setOf(
            "com.github.ajalt.clikt",
            "com.jetbrains.intellij",
            "com.jetbrains.intellij.idea",
            "com.jetbrains.intellij.platform",
            "io.ktor",
            "io.modelcontextprotocol",
        )
        val checkedConfigurationNames = setOf(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
            "testImplementation",
            "testCompileOnly",
            "testRuntimeOnly",
        )

        val violations = checkedConfigurationNames.flatMap { configurationName ->
            configurations.findByName(configurationName)?.dependencies.orEmpty().mapNotNull { dependency ->
                when (dependency) {
                    is ProjectDependency -> {
                        val path = dependency.path
                        if (path in forbiddenProjectPaths) "$configurationName -> project($path)" else null
                    }

                    is ExternalModuleDependency -> {
                        val group = dependency.group
                        if (group in forbiddenGroups) "$configurationName -> $group:${dependency.name}" else null
                    }

                    else -> null
                }
            }
        }

        check(violations.isEmpty()) {
            "cli:scripting must stay script-only and transport-neutral. Forbidden dependencies:\n" +
                violations.joinToString(separator = "\n") { "  - $it" }
        }
    }
}

val runCliScriptingApiConsumer = tasks.register<JavaExec>("runCliScriptingApiConsumer") {
    description = "Compiles and runs an isolated consumer that depends on the cli:scripting public API."
    group = "verification"
    dependsOn(tasks.named(apiConsumerSourceSet.classesTaskName))
    mainClass.set("org.jetbrains.hexana.cli.scripting.consumer.CliScriptingApiConsumerKt")
    classpath = apiConsumerSourceSet.runtimeClasspath
}

tasks.named("check") {
    dependsOn(verifyCliScriptingBoundary)
    dependsOn(runCliScriptingApiConsumer)
}
