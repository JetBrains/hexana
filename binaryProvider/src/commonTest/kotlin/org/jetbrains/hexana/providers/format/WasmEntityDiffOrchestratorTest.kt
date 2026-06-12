package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.EntityKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WasmEntityDiffOrchestratorTest {

    /** type ()->i32, one defined function, no imports. */
    private val noImports: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    /** Same, but with one imported function `env.print` (host namespace). */
    private val oneHostImport: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        // import section: env.print, func type 0  (content = count(1) + entry(12) = 13 = 0x0D)
        0x02, 0x0D, 0x01,
        0x03, 0x65, 0x6E, 0x76,                   // "env"
        0x05, 0x70, 0x72, 0x69, 0x6E, 0x74,       // "print"
        0x00, 0x00,                               // kind func, typeidx 0
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    private fun provider(bytes: ByteArray) = WasmBinaryProvider(bytes.toCommonByteBuffer())

    @Test
    fun `added host import is detected and flagged`() {
        // Sanity: framing parsed as expected before diffing.
        assertEquals(0, provider(noImports).imports.size)
        assertEquals(1, provider(oneHostImport).imports.size)

        val result = WasmEntityDiffOrchestrator.diff(provider(noImports), provider(oneHostImport))
        assertNotNull(result)

        val importEntries = result.entities.entries.filter { it.kind == EntityKind.IMPORT }
        assertEquals(1, importEntries.count { it.classification == DiffClass.ADDED })

        assertEquals(1, result.newImports.size)
        val delta = result.newImports.single()
        assertEquals("env", delta.module)
        assertEquals("print", delta.field)
        assertEquals("func", delta.kind)
        assertTrue(delta.isHost, "env is a known host namespace")

        // Adding an import must not spuriously change the defined function.
        assertEquals(0, result.entities.entries.count { it.kind == EntityKind.FUNCTION && it.classification != DiffClass.IDENTICAL })
    }

    @Test
    fun `self-diff has no new imports and all identical`() {
        val result = WasmEntityDiffOrchestrator.diff(provider(oneHostImport), provider(oneHostImport))
        assertNotNull(result)
        assertTrue(result.newImports.isEmpty())
        assertTrue(result.removedImports.isEmpty())
        assertTrue(
            result.entities.entries.all { it.classification == DiffClass.IDENTICAL },
            "self-diff must be all identical",
        )
    }

    @Test
    fun `host import classifier recognizes known namespaces`() {
        assertTrue(WasmHostImportClassifier.isHostImport("env", "malloc"))
        assertTrue(WasmHostImportClassifier.isHostImport("wasi_snapshot_preview1", "fd_write"))
        assertTrue(WasmHostImportClassifier.isHostImport("wasi:cli/run", "run"))
        assertTrue(!WasmHostImportClassifier.isHostImport("my_app_module", "callback"))
    }

    @Test
    fun `removed import is reported`() {
        val result = WasmEntityDiffOrchestrator.diff(provider(oneHostImport), provider(noImports))
        assertNotNull(result)
        assertEquals(1, result.removedImports.size)
        assertEquals("env", result.removedImports.single().module)
        assertEquals(1, result.entities.entries.count { it.kind == EntityKind.IMPORT && it.classification == DiffClass.REMOVED })
    }
}
