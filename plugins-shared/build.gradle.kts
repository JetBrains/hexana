plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js {
        browser()
    }

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("src/commonMain/gen")

            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(project(":wasmParser"))
                implementation(project(":binaryProvider"))
                implementation(project(":encdec"))
                implementation(project(":hexanaDocument"))
                implementation(project(":parsing:dex"))
                implementation(project(":parsing:arsc"))
                implementation(project(":debugInfo:dwarf"))
                // `api`: BinaryDiffHost exposes the host-supplied `Disassembler`.
                api(project(":disassembly:core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":parsing:parquet"))
                implementation(project(":parsing:arrow"))
                implementation(project(":disassembly:capstone-wasm"))
            }
        }
    }
}

// --- Bundled WebAssembly feature catalog -------------------------------------------------
//
// `features/features.json` (a snapshot of WebAssembly/website features.json) is compiled
// into a checked-in Kotlin constant rather than shipped as a resource: `wasmJs` has no
// convenient resource loader, and a constant is readable identically on every target.
// See features/README.md.

val bundledFeaturesJson = layout.projectDirectory.file("features/features.json")
val bundledFeaturesKt = layout.projectDirectory
    .file("src/commonMain/gen/org/jetbrains/hexana/shared/features/BundledWasmFeatures.kt")

fun renderBundledWasmFeatures(json: String): String {
    // Emitted as a Kotlin raw string. Two things need handling: the raw-string terminator
    // (features.json has none, but guard rather than assume) and `$`, which would start a
    // string template - features.json contains one, in the `"$schema"` key. Escaping `$`
    // makes the literal non-constant, hence `val` rather than `const val`.
    require(!json.contains("\"\"\"")) { "features.json contains a raw-string terminator" }
    val escaped = json.replace("$", "\${'\$'}")
    return buildString {
        appendLine("package org.jetbrains.hexana.shared.features")
        appendLine()
        appendLine("// GENERATED FILE - DO NOT EDIT.")
        appendLine("// Source: plugins-shared/features/features.json")
        appendLine("// Regenerate: ./gradlew :plugins-shared:updateBundledWasmFeatures")
        appendLine()
        appendLine("/**")
        appendLine(" * Committed snapshot of WebAssembly's `features.json`, used as the offline fallback for")
        appendLine(" * the runtime compatibility table when no host cache is available.")
        appendLine(" */")
        appendLine("internal val BUNDLED_WASM_FEATURES_JSON: String = \"\"\"")
        append(escaped.trimEnd())
        appendLine("\"\"\"")
    }
}

val updateBundledWasmFeatures by tasks.registering {
    description = "Regenerates BundledWasmFeatures.kt from features/features.json."
    group = "build setup"
    inputs.file(bundledFeaturesJson)
    outputs.file(bundledFeaturesKt)
    doLast {
        val generated = renderBundledWasmFeatures(bundledFeaturesJson.asFile.readText())
        bundledFeaturesKt.asFile.parentFile.mkdirs()
        bundledFeaturesKt.asFile.writeText(generated)
    }
}

val verifyBundledWasmFeaturesUpToDate by tasks.registering {
    description = "Fails when BundledWasmFeatures.kt does not match features/features.json."
    group = "verification"
    inputs.file(bundledFeaturesJson)
    inputs.file(bundledFeaturesKt)
    doLast {
        val expected = renderBundledWasmFeatures(bundledFeaturesJson.asFile.readText())
        val actual = bundledFeaturesKt.asFile.readText()
        check(expected == actual) {
            "BundledWasmFeatures.kt is stale. Run ./gradlew :plugins-shared:updateBundledWasmFeatures"
        }
    }
}

tasks.named("check") { dependsOn(verifyBundledWasmFeaturesUpToDate) }
