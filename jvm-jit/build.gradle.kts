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
// The integration test in jvmTest (JitDumpIntegrationTest) spawns a child JVM
// with -agentpath:<this .dylib/.so>; if cmake or JAVA_HOME aren't usable on
// the host, the task is allowed to fail and the test skips via assumeTrue.
// -----------------------------------------------------------------------------

val agentSourceDir = layout.projectDirectory.dir("jvmti-agent")
val agentBuildDir = layout.buildDirectory.dir("jvmti-agent")

val buildJitviewAgent by tasks.registering(Exec::class) {
    description = "Builds the JVMTI agent (libjitview.{dylib,so,dll}) via CMake."
    inputs.dir(agentSourceDir)
    outputs.dir(agentBuildDir)

    val javaHome = System.getenv("JAVA_HOME")
        ?: System.getProperty("java.home")
    environment("JAVA_HOME", javaHome)

    doFirst { agentBuildDir.get().asFile.mkdirs() }

    workingDir = agentBuildDir.get().asFile
    commandLine(
        "sh", "-c",
        "cmake \"${agentSourceDir.asFile.absolutePath}\" -DCMAKE_BUILD_TYPE=Release " +
            "&& cmake --build .",
    )
    // Don't fail the wider build when cmake is unavailable — the
    // integration test detects a missing artifact and skips.
    isIgnoreExitValue = true
}

tasks.named("jvmTest") {
    dependsOn(buildJitviewAgent)
    val agentDir = agentBuildDir.get().asFile.absolutePath
    doFirst {
        (this as Test).systemProperty("hexana.jit.agentBuildDir", agentDir)
    }
}
