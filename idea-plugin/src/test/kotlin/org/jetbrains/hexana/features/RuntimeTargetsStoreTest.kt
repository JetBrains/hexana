package org.jetbrains.hexana.features

import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * No network, no IDE: the settings file's read/write behaviour is pure filesystem work and
 * is tested against a temporary directory.
 */
class RuntimeTargetsStoreTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val basePath: String get() = projectDir.root.absolutePath

    @Test
    fun `reads nothing when the file is absent`() {
        assertTrue(RuntimeTargetsStore.read(basePath).isEmpty)
    }

    @Test
    fun `write creates the hexana directory and the file lands where both editors look`() {
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Chrome", "120"))))

        val expected = File(File(basePath, ".hexana"), "runtimes.json")
        assertTrue("expected $expected to exist", expected.isFile)
        assertEquals(expected, RuntimeTargetsStore.file(basePath))
    }

    @Test
    fun `round trip through disk preserves the declaration`() {
        val targets = RuntimeTargets(
            listOf(RuntimeTarget("Chrome", "120"), RuntimeTarget("Wasmtime", "26.0"), RuntimeTarget("Node.js"))
        )
        RuntimeTargetsStore.write(basePath, targets)
        assertEquals(targets.targets, RuntimeTargetsStore.read(basePath).targets)
    }

    /**
     * The file is committed to version control, so re-applying an unchanged settings page
     * must not touch it.
     */
    @Test
    fun `writing an unchanged declaration leaves the file untouched`() {
        val targets = RuntimeTargets(listOf(RuntimeTarget("Chrome", "120")))
        val file = RuntimeTargetsStore.write(basePath, targets)
        val firstModified = file.lastModified()
        file.setLastModified(firstModified - 10_000)
        val staleModified = file.lastModified()

        RuntimeTargetsStore.write(basePath, targets)
        assertEquals("expected no rewrite for identical content", staleModified, file.lastModified())
    }

    @Test
    fun `writing a changed declaration replaces the file`() {
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Chrome", "120"))))
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Deno"))))
        assertEquals(listOf(RuntimeTarget("Deno")), RuntimeTargetsStore.read(basePath).targets)
    }

    @Test
    fun `an empty declaration is written as an empty target list, not deleted`() {
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Chrome"))))
        RuntimeTargetsStore.write(basePath, RuntimeTargets.EMPTY)

        assertTrue(RuntimeTargetsStore.file(basePath).isFile)
        assertTrue(RuntimeTargetsStore.read(basePath).isEmpty)
    }

    @Test
    fun `the schema is written next to the settings file when one is supplied`() {
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Chrome"))), "{\"schema\": true}")

        val schema = RuntimeTargetsStore.schemaFile(basePath)
        assertEquals(File(File(basePath, ".hexana"), "runtimes.schema.json"), schema)
        assertEquals("{\"schema\": true}", schema.readText())
        // The reference in the settings file has to resolve to what was just written.
        assertTrue("runtimes.schema.json" in RuntimeTargetsStore.file(basePath).readText())
    }

    @Test
    fun `an unchanged schema is left untouched while the declaration is rewritten`() {
        val schemaText = "{\"schema\": true}"
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Chrome"))), schemaText)
        val schema = RuntimeTargetsStore.schemaFile(basePath)
        schema.setLastModified(schema.lastModified() - 10_000)
        val staleModified = schema.lastModified()

        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Deno"))), schemaText)

        assertEquals("expected no rewrite for identical schema", staleModified, schema.lastModified())
        assertEquals(listOf(RuntimeTarget("Deno")), RuntimeTargetsStore.read(basePath).targets)
    }

    /** A project that predates the schema, or one whose catalog failed to load, still works. */
    @Test
    fun `no schema is written when none is supplied`() {
        RuntimeTargetsStore.write(basePath, RuntimeTargets(listOf(RuntimeTarget("Chrome"))))
        assertFalse(RuntimeTargetsStore.schemaFile(basePath).exists())
    }

    /** A hand-edited or half-merged file must not surface an error on every file open. */
    @Test
    fun `a corrupt file reads as empty`() {
        val file = RuntimeTargetsStore.file(basePath)
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        assertTrue(RuntimeTargetsStore.read(basePath).isEmpty)
    }

    @Test
    fun `a directory where the file should be reads as empty`() {
        RuntimeTargetsStore.file(basePath).mkdirs()
        assertTrue(RuntimeTargetsStore.read(basePath).isEmpty)
        assertFalse(RuntimeTargetsStore.file(basePath).isFile)
    }
}
