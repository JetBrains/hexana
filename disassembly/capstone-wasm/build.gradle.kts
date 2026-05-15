import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val generateDisassemblerWasmBytes by tasks.registering {
    description = "Embeds disassembler.wasm into a Kotlin source consumed by the common tests " +
        "so js / wasmJs test runners can load the same bytes the JVM reads from the classpath."
    val wasm = layout.projectDirectory.file("src/commonMain/resources/disassembler.wasm")
    val outDir = layout.buildDirectory.dir("generated/sources/disassemblerWasmBytes/commonTest")
    inputs.file(wasm)
    outputs.dir(outDir)
    doLast {
        val base64 = Base64.getEncoder().encodeToString(wasm.asFile.readBytes())
        // JVM class files cap UTF-8 string constants at 65535 bytes, so chunk well
        // below that and concatenate at init. `val`, not `const val`, because the
        // concatenation runs at class load.
        val chunks = base64.chunked(32 * 1024)
        val out = outDir.get().asFile
            .resolve("org/jetbrains/hexana/disassembly/capstone/DisassemblerWasmBytes.kt")
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine("package org.jetbrains.hexana.disassembly.capstone")
            appendLine()
            appendLine("import kotlin.io.encoding.Base64 as Kb64")
            appendLine("import kotlin.io.encoding.ExperimentalEncodingApi")
            appendLine()
            appendLine("@OptIn(ExperimentalEncodingApi::class)")
            appendLine("internal val DISASSEMBLER_WASM_BYTES: ByteArray by lazy(LazyThreadSafetyMode.NONE) {")
            appendLine("    Kb64.decode(DISASSEMBLER_WASM_BASE64)")
            appendLine("}")
            appendLine()
            appendLine("private val DISASSEMBLER_WASM_BASE64: String = buildString(${base64.length}) {")
            chunks.forEach { chunk ->
                appendLine("    append(\"$chunk\")")
            }
            appendLine("}")
        })
    }
}

kotlin {
    jvmToolchain(21)

    jvm()

    js {
        nodejs()
    }

    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":disassembly:core"))
                api(project(":encdec"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            kotlin.srcDir(generateDisassemblerWasmBytes)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.chicory.runtime)
                implementation(libs.chicory.compiler)
            }
        }
    }
}
