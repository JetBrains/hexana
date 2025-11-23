plugins {
    kotlin("jvm")
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.graalvm.polyglot:polyglot:25.0.0")
    implementation("org.graalvm.polyglot:wasm:25.0.0")
    implementation(project.parent!!.project("common"))
}