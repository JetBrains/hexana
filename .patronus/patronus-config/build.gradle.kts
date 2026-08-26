plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

dependencies {
    implementation(libs.patronus.config)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hexana.repositoryRoot", rootProject.projectDir.absolutePath)
}

val patronusConfigFile = rootProject.layout.projectDirectory.file(".patronus/config.yaml")
val generatorMainClass = "org.jetbrains.hexana.patronus.HexanaPatronusConfigGenerator"

val updatePatronusConfig = tasks.register<JavaExec>("updatePatronusConfig") {
    group = "build setup"
    description = "Regenerate .patronus/config.yaml from the Patronus Kotlin DSL."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = generatorMainClass
    args(patronusConfigFile.asFile.absolutePath)
    outputs.file(patronusConfigFile)
}

tasks.register<JavaExec>("verifyPatronusConfigUpToDate") {
    group = "verification"
    description = "Verify that .patronus/config.yaml matches the Patronus Kotlin DSL."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = generatorMainClass
    args("--verify", patronusConfigFile.asFile.absolutePath)
    inputs.file(patronusConfigFile)
    mustRunAfter(updatePatronusConfig)
}
