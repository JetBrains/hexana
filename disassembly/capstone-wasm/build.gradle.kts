import com.dylibso.chicory.build.time.compiler.Config as AotConfig
import com.dylibso.chicory.build.time.compiler.Generator as AotGenerator
import com.dylibso.chicory.compiler.InterpreterFallback
import io.roastedroot.redline.build.Config as RedlineConfig
import io.roastedroot.redline.build.Generator as RedlineGenerator
import java.util.Base64

buildscript {
    repositories {
        maven("https://cache-redirector.jetbrains.com/maven-central")
    }
    dependencies {
        // Used by the aotCompileDisassemblerWasm task below.
        classpath("com.dylibso.chicory:build-time-compiler:1.4.0")
        // Used by the redlineCompileDisassemblerWasm task below — experimental
        // Cranelift-based native AOT alongside the vanilla bytecode AOT. See
        // `user/capstone-chicory-redline` branch + chicory-redline-expert
        // agent for rationale.
        classpath("io.roastedroot:redline-build-time-compiler:0.0.4")
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

// ---------------------------------------------------------------------------
// Chicory Redline (experimental, side-by-side with vanilla AOT)
//
// Redline uses Cranelift (the Wasmtime compiler backend) to lower
// disassembler.wasm to *native* machine code at build time, one slice per
// (OS, arch) target. At runtime, Redline's universal builder picks the
// matching native slice via SPI (Panama FFM on Java 25+, jffi on Java 11+);
// when no slice matches it falls back to Chicory's bytecode interpreter,
// preserving the pure-JVM portability the existing AOT path provides.
//
// Output layout (under `build/generated/chicory-redline/`):
//  - `classes/`          — bytecode fallback `.class` files
//                          (`DisassemblerWasmMachine` + emitters).
//  - `compiled-classes/` — the `DisassemblerWasm.java` source, compiled.
//  - `resources/`        — `.native` slices (one per target triple),
//                          `.meta` wasm.
//  - `sources/`          — generated `DisassemblerWasm.java` source.
//
// Runtime selection is wired in `CapstoneWasmModule.jvm.kt` via the
// `hexana.disassembly.backend` system property (`aot` default | `redline`).
// ---------------------------------------------------------------------------

val redlineClassesDir = layout.buildDirectory.dir("generated/chicory-redline/classes")
val redlineCompiledClassesDir = layout.buildDirectory.dir("generated/chicory-redline/compiled-classes")
val redlineResourcesDir = layout.buildDirectory.dir("generated/chicory-redline/resources")
val redlineSourcesDir = layout.buildDirectory.dir("generated/chicory-redline/sources")

/**
 * Classpath the JavaCompile step needs to resolve the Redline runtime API
 * the generated `DisassemblerWasm.java` imports (`RedlineInstance`,
 * `RedlineTarget`, `NativeCodeSerializer`, …). Separate from the project's
 * jvmMain configuration because the compile happens before Gradle has
 * resolved that source set's classpath.
 */
val redlineCodegenClasspath: Configuration = configurations.create("redlineCodegenClasspath") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    add(redlineCodegenClasspath.name, "io.roastedroot:redline:0.0.4")
    add(redlineCodegenClasspath.name, "com.dylibso.chicory:runtime:1.4.0")
    add(redlineCodegenClasspath.name, "com.dylibso.chicory:wasm:1.4.0")
}

val redlineCompileDisassemblerWasm by tasks.registering {
    description = "Cranelift-compiled native variant of disassembler.wasm via chicory-redline."
    val wasm = layout.projectDirectory.file("src/commonMain/resources/disassembler.wasm")
    inputs.file(wasm)
    outputs.dir(redlineClassesDir)
    outputs.dir(redlineResourcesDir)
    outputs.dir(redlineSourcesDir)
    doLast {
        listOf(redlineClassesDir, redlineResourcesDir, redlineSourcesDir).forEach { d ->
            val f = d.get().asFile
            f.deleteRecursively()
            f.mkdirs()
        }
        val cfg = RedlineConfig.builder()
            .withWasmFile(wasm.asFile.toPath())
            .withName("org.jetbrains.hexana.disassembly.capstone.redline.DisassemblerWasm")
            .withTargetClassFolder(redlineClassesDir.get().asFile.toPath())
            .withTargetResourceFolder(redlineResourcesDir.get().asFile.toPath())
            .withTargetSourceFolder(redlineSourcesDir.get().asFile.toPath())
            // bytecodeFallback = true preserves portability: on a platform with
            // no matching native slice (or with both Panama + jffi disabled)
            // the universal builder transparently uses the Chicory bytecode
            // path. We default ALL_TARGETS (Linux/macOS/Windows × x86_64/aarch64)
            // — the same matrix Hexana already targets.
            .withBytecodeFallback(true)
            .build()
        val gen = RedlineGenerator(cfg)
        gen.generateNativeCode()
        gen.generateMetaWasm()
        gen.generateChicoryBytecode()
        gen.generateSources()
    }
}

val redlineCompileJava by tasks.registering(JavaCompile::class) {
    description = "Compiles the Redline-generated DisassemblerWasm.java."
    dependsOn(redlineCompileDisassemblerWasm)
    source(redlineSourcesDir)
    // DisassemblerWasm.java references DisassemblerWasmMachine (bytecode
    // fallback) which lives in the same package under redlineClassesDir;
    // include it on the classpath so resolution works.
    classpath = redlineCodegenClasspath + files(redlineClassesDir)
    destinationDirectory.set(redlineCompiledClassesDir)
    // Target 17, not 21: TeamCity build agents may run an older JDK that
    // doesn't support `-release 21`. The Redline-generated source uses
    // only stdlib features available in 17 (try-with-resources, generics,
    // lambdas), and 17 is the IntelliJ Platform minimum so the produced
    // class files load in any supported IDE. The KMP plugin doesn't apply
    // the full `java` plugin so JavaToolchainService isn't available here;
    // any JDK 17+ accepts `--release 17`, which matches JBR / TC agents.
    options.release.set(17)
}

val redlineClassesJar by tasks.registering(Jar::class) {
    description = "Packages Redline bytecode-fallback + generated DisassemblerWasm classes."
    dependsOn(redlineCompileDisassemblerWasm, redlineCompileJava)
    archiveBaseName.set("capstone-wasm-redline")
    destinationDirectory.set(layout.buildDirectory.dir("generated/chicory-redline/jar"))
    from(redlineClassesDir)
    from(redlineCompiledClassesDir)
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
            // Both AOT (`<...>.aot/DisassemblerWasm.meta`) and Redline
            // (`<...>.redline/DisassemblerWasm.meta` + `.native` slices)
            // resource trees are added; the runtime-side switch
            // (`hexana.disassembly.backend`) decides which to load.
            resources.srcDir(aotResourcesDir)
            resources.srcDir(redlineResourcesDir)
            dependencies {
                implementation(libs.chicory.runtime)
                implementation(libs.chicory.compiler)
                // Redline runtime (api/bridge transitively pulled). Pulls
                // Panama FFM (Java 25+) or jffi (Java 11+) backend via SPI.
                implementation("io.roastedroot:redline:0.0.4")
            }
        }
    }
}

// Precompiled .class files: ship them as a real jar (see [aotClassesJar] /
// [redlineClassesJar]) rather than a loose-class directory, so IntelliJ
// plugin classloaders — which scan `lib/*.jar` only — can resolve the
// generated classes at runtime. Direct JVM consumers (tests, other Gradle
// modules) pick the jars up off the runtime classpath the same way they
// would a loose tree.
dependencies {
    "jvmMainImplementation"(files(aotClassesJar))
    "jvmMainImplementation"(files(redlineClassesJar))
}

// Provider<Directory> from layout.buildDirectory.dir(...) doesn't auto-track
// the task that produces it when wired through resources.srcDir, so attach
// an explicit dependency for each consumer.
listOf("jvmProcessResources").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(aotCompileDisassemblerWasm, redlineCompileDisassemblerWasm)
    }
}
