import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val mainSourceSet = sourceSets.named("main").get()
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    java.srcDir("src/integrationTest/kotlin")
    compileClasspath += mainSourceSet.output
    runtimeClasspath += output + compileClasspath
}

configurations.named(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.named("testImplementation").get())
}

configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.named("testRuntimeOnly").get())
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val standardJvmLauncher = project(":cli:hexana-cli").layout.buildDirectory.file("install/hexana-cli/bin/hexana-cli")
val scriptJvmLauncher = project(":cli:hexana-cli-script").layout.buildDirectory.file("install/hexana-cli-script/bin/hexana-cli-script")
val standardNativeExecutable = project(":cli:hexana-cli").layout.buildDirectory.file("native/nativeCompile/hexana-cli")
val scriptNativeExecutable = project(":cli:hexana-cli-script").layout.buildDirectory.file("native/nativeCompile/hexana-cli-script")
val cliVersion = providers.gradleProperty("hexanaToolsVersion").get()

fun Test.configureCliContracts(target: String, standardLauncher: String, scriptLauncher: String) {
    group = "verification"
    dependsOn(integrationTestSourceSet.classesTaskName)
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("hexana.cli.integration.target", target)
    systemProperty("hexana.cli.integration.standardLauncher", standardLauncher)
    systemProperty("hexana.cli.integration.scriptLauncher", scriptLauncher)
    systemProperty("hexana.cli.integration.version", cliVersion)
    testLogging { events("failed", "passed", "skipped") }
}

tasks.register<Test>("jvmCliIntegrationTest") {
    description = "Runs the split CLI black-box contract against both installDist launchers."
    dependsOn(":cli:hexana-cli:installDist", ":cli:hexana-cli-script:installDist")
    configureCliContracts("jvm", standardJvmLauncher.get().asFile.absolutePath, scriptJvmLauncher.get().asFile.absolutePath)
}

tasks.register<Test>("nativeCliIntegrationTest") {
    description = "Runs the split CLI black-box contract against both GraalVM Native Image executables."
    dependsOn(":cli:hexana-cli:nativeCompile", ":cli:hexana-cli-script:nativeCompile")
    configureCliContracts("native", standardNativeExecutable.get().asFile.absolutePath, scriptNativeExecutable.get().asFile.absolutePath)
}
