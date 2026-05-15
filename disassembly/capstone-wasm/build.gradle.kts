import com.dylibso.chicory.build.time.compiler.Config as AotConfig
import com.dylibso.chicory.build.time.compiler.Generator as AotGenerator
import com.dylibso.chicory.compiler.InterpreterFallback
import java.util.Base64

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Used by the aotCompileDisassemblerWasm task below.
        classpath("com.dylibso.chicory:build-time-compiler:1.4.0")
    }
}

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

/**
 * Build-time AOT compilation of disassembler.wasm via Chicory's build-time-compiler.
 *
 * Output layout (under `build/generated/chicory-aot/`):
 *  - `classes/`   — raw JVM bytecode for the AOT-compiled WASM functions
 *                   (`DisassemblerWasmMachine` + its inner emitter classes).
 *  - `resources/` — the "meta" .wasm containing only the interpreter-fallback
 *                   functions (much smaller than the original blob).
 *
 * The AOT classes' package is hardcoded to
 * `org.jetbrains.hexana.disassembly.capstone.aot`; the Kotlin actual in
 * `CapstoneWasmModule.jvm.kt` references `DisassemblerWasmMachine` directly,
 * so we skip Chicory's optional Java wrapper.
 */
val aotClassesDir = layout.buildDirectory.dir("generated/chicory-aot/classes")
val aotResourcesDir = layout.buildDirectory.dir("generated/chicory-aot/resources")

val aotCompileDisassemblerWasm by tasks.registering {
    description = "Pre-compiles disassembler.wasm into JVM bytecode + a meta .wasm fallback."
    val wasm = layout.projectDirectory.file("src/commonMain/resources/disassembler.wasm")
    inputs.file(wasm)
    outputs.dir(aotClassesDir)
    outputs.dir(aotResourcesDir)
    doLast {
        listOf(aotClassesDir, aotResourcesDir).forEach { d ->
            val f = d.get().asFile
            f.deleteRecursively()
            f.mkdirs()
        }
        // Chicory's Generator demands a non-null source folder even when we
        // never read from it (we drive DisassemblerWasmMachine directly from
        // Kotlin and skip generateSources()). Give it a throwaway dir.
        val unusedSourcesDir = layout.buildDirectory.dir("generated/chicory-aot/.unused-sources").get().asFile
        unusedSourcesDir.deleteRecursively(); unusedSourcesDir.mkdirs()

        val config = AotConfig.builder()
            .withWasmFile(wasm.asFile.toPath())
            .withName("org.jetbrains.hexana.disassembly.capstone.aot.DisassemblerWasm")
            .withTargetClassFolder(aotClassesDir.get().asFile.toPath())
            .withTargetSourceFolder(unusedSourcesDir.toPath())
            .withTargetWasmFolder(aotResourcesDir.get().asFile.toPath())
            // A handful of yaxpeax's decode functions exceed the 64 KB JVM method
            // limit when lowered to bytecode; let them fall back to the interpreter
            // at runtime instead of failing the build.
            .withInterpreterFallback(InterpreterFallback.WARN)
            .build()
        val generator = AotGenerator(config)
        val interpreted = generator.generateResources()
        generator.generateMetaWasm(interpreted)
    }
}

/**
 * Packages the AOT-generated `.class` files into a jar so IntelliJ-platform
 * plugin classloaders pick them up. The plugin classloader scans `lib/` for
 * jars but ignores loose class trees, so shipping the AOT bundle as a plain
 * `files(aotClassesDir)` dependency leaves the classes in
 * `plugins/<id>/lib/org/...` where `ClassLoader.findClass` can't see them.
 */
val aotClassesJar by tasks.registering(Jar::class) {
    description = "Wraps the AOT-compiled disassembler classes in a jar so plugin classloaders find them."
    dependsOn(aotCompileDisassemblerWasm)
    archiveBaseName.set("capstone-wasm-aot")
    destinationDirectory.set(layout.buildDirectory.dir("generated/chicory-aot/jar"))
    from(aotClassesDir)
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
            // Meta .wasm flows into the jvm target's resources.
            resources.srcDir(aotResourcesDir)
            dependencies {
                implementation(libs.chicory.runtime)
                implementation(libs.chicory.compiler)
            }
        }
    }
}

// Precompiled .class files: ship them as a real jar (see [aotClassesJar])
// rather than a loose-class directory, so IntelliJ plugin classloaders —
// which scan `lib/*.jar` only — can resolve `DisassemblerWasmMachine` at
// runtime. Direct JVM consumers (tests, other Gradle modules) pick the jar
// up off the runtime classpath the same way they would a loose tree.
dependencies {
    "jvmMainImplementation"(files(aotClassesJar))
}

// Provider<Directory> from layout.buildDirectory.dir(...) doesn't auto-track
// the task that produces it when wired through resources.srcDir, so attach
// an explicit dependency for each consumer.
listOf("jvmProcessResources").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(aotCompileDisassemblerWasm)
    }
}
