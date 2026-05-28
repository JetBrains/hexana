import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
            dependencies {
                api(project(":encdec"))
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit4)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// JVMTI agent — built via CMake out of jvmti-agent/.
//
// Three-step pipeline:
//   1. configureJitviewAgent — runs `cmake <src>` to generate the build tree.
//   2. buildJitviewAgent     — runs `cmake --build .` to produce
//      libjitview.{dylib,so,dll}.
//   3. bundleJitviewAgent    — copies the artifact into a generated
//      resources tree (`native/<os-tag>/libjitview.<ext>`), which is then
//      included in jvmMain.resources so the file ships inside the jvm jar
//      (and through that, in the IntelliJ plugin).
//
// Native build failures propagate as task failures so CI (per-OS slice
// builds) goes red when the toolchain is missing or the C source doesn't
// compile, instead of silently publishing nothing and breaking downstream
// consumers at artifact-resolution time.
// -----------------------------------------------------------------------------

val agentSourceDir = layout.projectDirectory.dir("jvmti-agent")
val agentBuildDir = layout.buildDirectory.dir("jvmti-agent")
val agentNativeResourcesDir = layout.buildDirectory.dir("generated/jvm-jit-native-resources")

// Cross-compile selector. When set to a known target (currently only
// `windows-x86_64`), the build runs on a non-matching host (typically Linux)
// and emits a Windows DLL using a mingw-w64 toolchain. Pass via Gradle prop:
//   ./gradlew :jvm-jit:buildJitviewAgent -Pjvm-jit.crossTarget=windows-x86_64
// The TeamCity `BuildJitviewAgentWindowsX64` slice sets this and apt-installs
// the toolchain before invoking Gradle (the Default Windows agent pool has
// no cmake / MSVC, so we cross-compile from Linux instead).
val crossTarget: String? = providers.gradleProperty("jvm-jit.crossTarget").orNull
val crossToolchainFile: File? = when (crossTarget) {
    null -> null
    "windows-x86_64" -> agentSourceDir.file("cmake/mingw-w64-x86_64.toolchain.cmake").asFile
    else -> error("Unsupported jvm-jit.crossTarget: $crossTarget")
}

fun resolveExecutableOnPath(name: String): File? {
    val path = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?: return null
    val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    val executableNames = if (isWindows && !name.contains('.')) {
        val pathExt = System.getenv("PATHEXT")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?: listOf(".exe", ".cmd", ".bat")
        listOf(name) + pathExt.map { name + it.lowercase() }
    } else {
        listOf(name)
    }

    return path.asSequence()
        .flatMap { dir -> executableNames.asSequence().map { executable -> File(dir, executable) } }
        .firstOrNull { it.isFile && it.canExecute() }
}

// Probe for cmake on PATH at configuration time. When cmake is absent and
// this isn't a CI slice that explicitly needs it (no crossTarget set), the
// native-build tasks disable themselves and the bundle step produces an
// empty `native/<tag>/` resource directory. `JitviewAgentLocator` returns
// null at runtime when nothing is on the classpath, so tests still pass.
//
// CI slice builds set `-Pjvm-jit.crossTarget=…` (cross-compile) or run on
// agents that have cmake installed; in both cases this probe is true and
// any cmake/compile failure propagates as a task failure (no more silent
// green-with-no-artifact). The Tests build configuration does neither, so
// agents without cmake skip the native build cleanly.
val cmakeExecutable: File? = resolveExecutableOnPath("cmake")
val cmakeCommand: String = cmakeExecutable?.absolutePath ?: "cmake"
val cmakeAvailable: Boolean = cmakeExecutable != null && try {
    ProcessBuilder(cmakeCommand, "--version")
        .redirectErrorStream(true)
        .start()
        .also { it.inputStream.readAllBytes() }
        .waitFor() == 0
} catch (_: Throwable) {
    false
}
val nativeBuildEnabled: Boolean = crossTarget != null || cmakeAvailable
if (!nativeBuildEnabled) {
    logger.warn(
        "CMake was not found on PATH; skipping :jvm-jit JVMTI agent native build."
    )
}

val configureJitviewAgent by tasks.registering(Exec::class) {
    description = "Configures the JVMTI agent CMake build tree."
    enabled = nativeBuildEnabled
    inputs.dir(agentSourceDir)
    outputs.dir(agentBuildDir)

    // When cross-compiling we need the *target* JDK's JNI headers (jni_md.h
    // is platform-specific), not the host's. The TeamCity step exports
    // WIN_JDK_HOME pointing at an extracted Windows JDK 17.
    val javaHome = if (crossTarget != null) {
        System.getenv("WIN_JDK_HOME")
            ?: error("WIN_JDK_HOME must be set when jvm-jit.crossTarget=$crossTarget")
    } else {
        System.getenv("JAVA_HOME")
            ?: System.getProperty("java.home")
    }
    environment("JAVA_HOME", javaHome)

    doFirst { agentBuildDir.get().asFile.mkdirs() }

    workingDir = agentBuildDir.get().asFile
    // Invoke cmake directly rather than through `sh -c`: Windows agents
    // resolve `sh` only inside Git's bundled bash, which doesn't have
    // cmake on its PATH, so the shelled-out invocation silently fails.
    commandLine(buildList {
        add(cmakeCommand)
        add(agentSourceDir.asFile.absolutePath)
        add("-DCMAKE_BUILD_TYPE=Release")
        crossToolchainFile?.let { add("-DCMAKE_TOOLCHAIN_FILE=${it.absolutePath}") }
    })
}

val buildJitviewAgent by tasks.registering(Exec::class) {
    description = "Builds the JVMTI agent (libjitview.{dylib,so,dll}) via CMake."
    enabled = nativeBuildEnabled
    dependsOn(configureJitviewAgent)
    inputs.dir(agentSourceDir)
    outputs.dir(agentBuildDir)

    workingDir = agentBuildDir.get().asFile
    commandLine(cmakeCommand, "--build", ".", "--config", "Release")
}

/**
 * Tags the host OS for the resource path the runtime locator looks under.
 * macOS produces a universal `.dylib` (x86_64 + arm64) so we shelve it under
 * `darwin/`; Linux / Windows ship per-arch.
 */
fun hostNativeTag(): String {
    val os = System.getProperty("os.name").lowercase()
    val rawArch = System.getProperty("os.arch").lowercase()
    val arch = when (rawArch) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> rawArch
    }
    return when {
        "mac" in os || "darwin" in os -> "darwin"
        "win" in os -> "windows-$arch"
        else -> "linux-$arch"
    }
}

fun hostLibraryFileName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os || "darwin" in os -> "libjitview.dylib"
        "win" in os -> "jitview.dll"
        else -> "libjitview.so"
    }
}

val bundleJitviewAgent by tasks.registering(Copy::class) {
    description = "Copies the host's libjitview into jvmMain resources at native/<os-tag>/."
    dependsOn(buildJitviewAgent)
    val tag = hostNativeTag()
    val libName = hostLibraryFileName()
    from(agentBuildDir) {
        include(libName)
    }
    into(agentNativeResourcesDir.map { it.dir("native").dir(tag) })
    doFirst { agentNativeResourcesDir.get().asFile.mkdirs() }
}

kotlin {
    sourceSets {
        jvmMain {
            // Generated native artifacts ship as JVM resources so that
            // downstream consumers (the IntelliJ plugin) get them transitively
            // via the regular `implementation(project(":jvm-jit"))`.
            resources.srcDir(agentNativeResourcesDir)
        }
    }
}

// Wire bundling into the standard resource-processing pipeline so the file
// is present when the jvm jar is assembled, both for `runIde` packaging and
// for `jvmTest` (so the locator can find it on the test classpath).
listOf("jvmProcessResources", "jvmTestProcessResources").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(bundleJitviewAgent)
    }
}

tasks.named("jvmTest") {
    val agentDir = agentBuildDir.get().asFile.absolutePath
    doFirst {
        (this as Test).systemProperty("hexana.jit.agentBuildDir", agentDir)
    }
}
