package org.jetbrains.hexana.mcp.server

import org.junit.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstalledServerJvmTest {
    @Test
    fun resolveCurrentJvmHomePrefersJavaHomeProperty() {
        val resolved = resolveCurrentJvmHome(
            javaHomeProperty = "/runtime/jbr",
            javaHomeEnvironment = "/env/jdk"
        )

        assertEquals(Path.of("/runtime/jbr").toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun resolveCurrentJvmHomeFallsBackToJavaHomeEnvironment() {
        val resolved = resolveCurrentJvmHome(
            javaHomeProperty = "   ",
            javaHomeEnvironment = "/env/jdk"
        )

        assertEquals(Path.of("/env/jdk").toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun resolveCurrentJvmHomeFailsWithClearMessageWhenBothSourcesMissing() {
        val error = assertFailsWith<IllegalStateException> {
            resolveCurrentJvmHome(
                javaHomeProperty = null,
                javaHomeEnvironment = " "
            )
        }

        assertEquals("Unable to determine Java home from `java.home` or `JAVA_HOME`", error.message)
    }

    @Test
    fun prependJavaBinToPathPrependsExistingPath() {
        val javaBinDir = Path.of("/runtime/jbr").resolve("bin").toString()
        val result = prependJavaBinToPath(
            javaHome = Path.of("/runtime/jbr"),
            currentPath = "/usr/bin"
        )

        assertEquals(
            "$javaBinDir${File.pathSeparator}/usr/bin",
            result
        )
    }

    @Test
    fun prependJavaBinToPathUsesOnlyJavaBinWhenPathMissing() {
        val javaBinDir = Path.of("/runtime/jbr").resolve("bin").toString()
        val result = prependJavaBinToPath(
            javaHome = Path.of("/runtime/jbr"),
            currentPath = null
        )

        assertEquals(javaBinDir, result)
    }
}
