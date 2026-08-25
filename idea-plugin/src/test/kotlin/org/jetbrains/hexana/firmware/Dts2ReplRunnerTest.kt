package org.jetbrains.hexana.firmware

import com.intellij.testFramework.UsefulTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Pins the PYTHONPATH derivation for `python -m dts2repl`.
 *
 * Regression for the sandbox failure "No module named dts2repl": [Dts2ReplRunner]
 * resolved the archive root correctly but then put its PARENT on PYTHONPATH, one level
 * above the directory containing the `dts2repl` package.
 */
class Dts2ReplRunnerTest : UsefulTestCase() {

    fun testArchiveRootIsUsedAsIs() {
        val dir = createTempDirectory("dts2repl-pp-").toFile()
        try {
            File(dir, "dts2repl/__main__.py").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(dir.toPath(), Dts2ReplRunner().pythonPathFor(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testOverridePointingAtPackageDirUsesParent() {
        val root = createTempDirectory("dts2repl-pp-").toFile()
        try {
            val pkg = File(root, "dts2repl")
            File(pkg, "__main__.py").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(root.toPath(), Dts2ReplRunner().pythonPathFor(pkg.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    fun testUnknownLayoutFallsBackToGivenDir() {
        val dir = createTempDirectory("dts2repl-pp-").toFile()
        try {
            assertEquals(dir.toPath(), Dts2ReplRunner().pythonPathFor(dir.toPath()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
