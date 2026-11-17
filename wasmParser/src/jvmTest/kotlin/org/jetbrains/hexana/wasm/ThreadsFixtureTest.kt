package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.wat2wasm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class ThreadsFixtureTest {
    @Test
    fun sharedMemoryImportBinaryMatchesItsWatSource() = runTest {
        val wat = resource("threads/shared-memory-import.wat").decodeToString()
        val committed = resource("threads/shared-memory-import.wasm")

        assertContentEquals(committed, wat2wasm(wat))
    }

    private fun resource(path: String): ByteArray {
        val stream = ThreadsFixtureTest::class.java.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "Resource $path should be available on the classpath")
        return stream.use { it.readBytes() }
    }
}
