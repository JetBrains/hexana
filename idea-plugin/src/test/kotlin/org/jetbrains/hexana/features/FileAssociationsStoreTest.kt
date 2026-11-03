package org.jetbrains.hexana.features

import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.features.FileAssociations
import org.jetbrains.hexana.shared.features.ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `.hexana/file_associations.json` read/write behaviour, against a temporary directory. */
class FileAssociationsStoreTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val basePath: String get() = projectDir.root.absolutePath

    @Test
    fun `reads nothing when the file is absent`() {
        assertTrue(FileAssociationsStore.read(basePath).isEmpty)
    }

    @Test
    fun `write lands the file and its schema where both editors look`() {
        FileAssociationsStore.write(basePath, FileAssociations(mapOf("a.bin" to BinaryParser.ELF.ref())))

        val hexana = File(basePath, ".hexana")
        assertTrue(File(hexana, "file_associations.json").isFile)
        assertTrue(File(hexana, "file_associations.schema.json").isFile)
    }

    @Test
    fun `round trip through disk preserves the associations`() {
        val associations = FileAssociations(
            mapOf("fw/boot.img" to BinaryParser.ELF.ref(), "blob.dat" to BinaryParser.GENERIC.ref()),
        )
        FileAssociationsStore.write(basePath, associations)
        assertEquals(associations, FileAssociationsStore.read(basePath))
    }

    @Test
    fun `writes leave no temporary files behind`() {
        FileAssociationsStore.write(basePath, FileAssociations(mapOf("a.bin" to BinaryParser.ELF.ref())))
        FileAssociationsStore.write(basePath, FileAssociations(mapOf("a.bin" to BinaryParser.PE.ref())))
        val names = File(basePath, ".hexana").list()!!.sorted()
        assertEquals(listOf("file_associations.json", "file_associations.schema.json"), names)
        assertEquals(BinaryParser.PE.ref(), FileAssociationsStore.read(basePath).parserFor("a.bin"))
    }

    @Test
    fun `an unchanged save does not touch the committed file`() {
        val associations = FileAssociations(mapOf("a.bin" to BinaryParser.PE.ref()))
        val file = FileAssociationsStore.write(basePath, associations)
        file.setLastModified(1_000L)
        FileAssociationsStore.write(basePath, associations)
        assertEquals(1_000L, file.lastModified())
    }

    @Test
    fun `a malformed file reads as nothing associated`() {
        val file = FileAssociationsStore.file(basePath)
        file.parentFile.mkdirs()
        file.writeText("{ broken")
        assertTrue(FileAssociationsStore.read(basePath).isEmpty)
    }

    @Test
    fun `tracker announces content changes only`() {
        val tracker = FileAssociationsTracker()
        val one = FileAssociations(mapOf("a.bin" to BinaryParser.PE.ref()))
        tracker.get { one }
        tracker.observe(FileAssociations(mapOf("a.bin" to BinaryParser.PE.ref())))
        assertEquals(0, tracker.changes.value)
        tracker.observe(one.with("a.bin", BinaryParser.GENERIC.ref()))
        assertEquals(1, tracker.changes.value)
        tracker.recordSave(FileAssociations.EMPTY)
        assertEquals(2, tracker.changes.value)
        assertFalse(tracker.get { one } == one)
    }
}
