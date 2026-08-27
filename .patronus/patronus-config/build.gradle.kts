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

val patronusConfigFile = rootProject.layout.projectDirectory.file(".patronus/config.yaml")
val projectImpactGraphFile = rootProject.layout.buildDirectory.file("patronus/project-impact-graph.tsv")

val generatorMainClass = "org.jetbrains.hexana.patronus.HexanaPatronusConfigGenerator"

val updatePatronusConfig = tasks.register<JavaExec>("updatePatronusConfig") {
    group = "build setup"
    description = "Regenerate .patronus/config.yaml from the Patronus Kotlin DSL."
    dependsOn(tasks.classes, rootProject.tasks.named("exportPatronusProjectImpactGraph"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = generatorMainClass
    args(patronusConfigFile.asFile.absolutePath, projectImpactGraphFile.get().asFile.absolutePath)
    inputs.file(projectImpactGraphFile)
    outputs.file(patronusConfigFile)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("exportPatronusProjectImpactGraph"))
    mustRunAfter(updatePatronusConfig)
    inputs.files(patronusConfigFile, projectImpactGraphFile)
    systemProperty("hexana.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("hexana.projectImpactGraph", projectImpactGraphFile.get().asFile.absolutePath)
}

tasks.register<JavaExec>("verifyPatronusConfigUpToDate") {
    group = "verification"
    description = "Verify that .patronus/config.yaml matches the Patronus Kotlin DSL."
    dependsOn(tasks.classes, rootProject.tasks.named("exportPatronusProjectImpactGraph"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = generatorMainClass
    args("--verify", patronusConfigFile.asFile.absolutePath, projectImpactGraphFile.get().asFile.absolutePath)
    inputs.files(patronusConfigFile, projectImpactGraphFile)
    mustRunAfter(updatePatronusConfig)
}

tasks.register<JavaExec>("verifyPatronusProjectImpactGraph") {
    group = "verification"
    description = "Verify the exported Patronus project-impact graph snapshot."
    dependsOn(tasks.classes, rootProject.tasks.named("exportPatronusProjectImpactGraph"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.jetbrains.hexana.patronus.ProjectImpactSnapshotVerifier"
    args(rootProject.layout.buildDirectory.file("patronus/project-impact-graph.tsv").get().asFile.absolutePath)
}
