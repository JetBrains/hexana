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

val configureJitviewAgent by tasks.registering(Exec::class) {
    description = "Configures the JVMTI agent CMake build tree."
    inputs.dir(agentSourceDir)
    outputs.dir(agentBuildDir)

    val javaHome = System.getenv("JAVA_HOME")
        ?: System.getProperty("java.home")
    environment("JAVA_HOME", javaHome)

    doFirst { agentBuildDir.get().asFile.mkdirs() }

    workingDir = agentBuildDir.get().asFile
    // Invoke cmake directly rather than through `sh -c`: Windows agents
    // resolve `sh` only inside Git's bundled bash, which doesn't have
    // cmake on its PATH, so the shelled-out invocation silently fails.
    commandLine("cmake", agentSourceDir.asFile.absolutePath, "-DCMAKE_BUILD_TYPE=Release")
}

val buildJitviewAgent by tasks.registering(Exec::class) {
    description = "Builds the JVMTI agent (libjitview.{dylib,so,dll}) via CMake."
    dependsOn(configureJitviewAgent)
    inputs.dir(agentSourceDir)
    outputs.dir(agentBuildDir)

    workingDir = agentBuildDir.get().asFile
    commandLine("cmake", "--build", ".", "--config", "Release")
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
