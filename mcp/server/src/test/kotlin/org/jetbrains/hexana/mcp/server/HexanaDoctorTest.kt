package org.jetbrains.hexana.mcp.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaDoctorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inspectReportsSuccessWhenRequiredChecksPass() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val environment = baseEnvironment(
            allowedRoots = listOf(allowedRoot.toString()),
            classPath = installedDistributionClasspath(),
            consoleLogTargets = setOf(ConsoleLogTarget.STDERR)
        )

        val report = HexanaDoctor.inspect(environment)

        assertEquals(DoctorStatus.SUCCESS, report.status)
        assertEquals(0, report.exitCode)
        assertTrue(report.checks.all { it.severity == DoctorSeverity.OK })
    }

    @Test
    fun inspectKeepsWarningOnlyReportSuccessfulForFirstRunSupport() {
        val report = HexanaDoctor.inspect(
            baseEnvironment(
                version = "dev",
                allowedRoots = emptyList(),
                classPath = "",
                consoleLogTargets = emptySet()
            )
        )

        assertEquals(DoctorStatus.WARNING, report.status)
        assertEquals(0, report.exitCode)
        assertTrue(report.checks.any { it.severity == DoctorSeverity.WARNING && it.name == "allowed roots" })
        assertTrue(HexanaDoctor.render(report).contains("Overall: WARNING"))
    }

    @Test
    fun inspectFailsWhenRequiredRuntimeCheckFails() {
        val report = HexanaDoctor.inspect(
            baseEnvironment(
                javaVersion = "17.0.10",
                javaSpecificationVersion = "17"
            )
        )

        assertFailure(report, "runtime")
    }

    @Test
    fun inspectFailsWhenConfiguredAllowedRootIsInvalid() {
        val missingRoot = temporaryFolder.root.toPath().resolve("missing")

        val report = HexanaDoctor.inspect(
            baseEnvironment(
                allowedRoots = listOf(missingRoot.toString())
            )
        )

        assertFailure(report, "allowed roots")
    }

    @Test
    fun inspectFailsWhenConsoleLogsTargetStdout() {
        val report = HexanaDoctor.inspect(
            baseEnvironment(
                consoleLogTargets = setOf(ConsoleLogTarget.STDOUT)
            )
        )

        assertFailure(report, "logs")
    }

    @Test
    fun inspectFailsWhenDetectedInstalledDistributionIsMissingLauncher() {
        val installDir = temporaryFolder.newFolder("broken-install").toPath()
        Files.createDirectories(installDir.resolve("lib"))
        val classPath = installDir.resolve("lib/server-0.3.0.jar").toString()

        val report = HexanaDoctor.inspect(
            baseEnvironment(
                classPath = classPath
            )
        )

        assertFailure(report, "installed layout")
    }

    private fun assertFailure(report: DoctorReport, checkName: String) {
        assertEquals(DoctorStatus.FAILURE, report.status)
        assertEquals(1, report.exitCode)
        assertTrue(report.checks.any { it.severity == DoctorSeverity.ERROR && it.name == checkName })
    }

    private fun installedDistributionClasspath(): String {
        val installDir = temporaryFolder.newFolder("install").toPath()
        val libDir = installDir.resolve("lib")
        val binDir = installDir.resolve("bin")
        Files.createDirectories(libDir)
        Files.createDirectories(binDir)
        Files.createFile(libDir.resolve("server-0.3.0.jar"))
        Files.createFile(binDir.resolve(launcherFileName()))
        return libDir.resolve("server-0.3.0.jar").toString()
    }

    private fun launcherFileName(): String {
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "server.bat"
        } else {
            "server"
        }
    }

    private fun baseEnvironment(
        version: String = "0.3.0",
        allowedRoots: List<String> = listOf(temporaryFolder.root.toPath().toString()),
        nativeImage: Boolean = false,
        javaVersion: String = "21.0.1",
        javaSpecificationVersion: String = "21",
        classPath: String = installedDistributionClasspath(),
        consoleLogTargets: Set<ConsoleLogTarget>? = setOf(ConsoleLogTarget.STDERR),
    ): DoctorEnvironment {
        return DoctorEnvironment(
            version = version,
            allowedRoots = allowedRoots,
            nativeImage = nativeImage,
            javaVersion = javaVersion,
            javaVendor = "Test JDK",
            javaHome = Path.of("/test/jdk").toString(),
            javaSpecificationVersion = javaSpecificationVersion,
            classPath = classPath,
            consoleLogTargets = consoleLogTargets,
        )
    }
}
