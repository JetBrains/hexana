package org.jetbrains.hexana.mcp.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingJsonlWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendsLinesWithoutRollingBeforeTheSizeLimit() {
        val file = temporaryFolder.newFolder("logs").toPath().resolve("log.jsonl")
        RotatingJsonlWriter(file, maxFileSizeBytes = 1_024, maxRolledIndex = 2).use { writer ->
            assertFalse(writer.append("{\"a\":1}"))
            assertFalse(writer.append("{\"a\":2}"))
        }

        assertEquals(listOf("{\"a\":1}", "{\"a\":2}"), Files.readAllLines(file))
    }

    @Test
    fun shiftsRolledFilesAndDropsTheOldestOne() {
        val directory = temporaryFolder.newFolder("rolled").toPath()
        val file = directory.resolve("log.jsonl")
        val rolledCount = RotatingJsonlWriter(file, maxFileSizeBytes = 5, maxRolledIndex = 2).use { writer ->
            (1..5).count { index -> writer.append("record-$index") }
        }

        assertEquals(4, rolledCount)
        assertEquals(listOf("record-5"), Files.readAllLines(file))
        assertEquals(listOf("record-4"), Files.readAllLines(directory.resolve("log.1.jsonl")))
        assertEquals(listOf("record-3"), Files.readAllLines(directory.resolve("log.2.jsonl")))
        assertFalse(Files.exists(directory.resolve("log.3.jsonl")))
    }

    @Test
    fun continuesAnExistingFileAndRollsWhenItIsAlreadyFull() {
        val directory = temporaryFolder.newFolder("existing").toPath()
        val file = Files.writeString(directory.resolve("log.jsonl"), "0123456789\n")
        RotatingJsonlWriter(file, maxFileSizeBytes = 10, maxRolledIndex = 1).use { writer ->
            assertTrue(writer.append("fresh"))
        }

        assertEquals(listOf("0123456789"), Files.readAllLines(directory.resolve("log.1.jsonl")))
        assertEquals(listOf("fresh"), Files.readAllLines(file))
    }
}
