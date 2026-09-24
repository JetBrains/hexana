package org.jetbrains.hexana.run

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.hexana.DwarfIndexId
import java.io.File

/**
 * End-to-end reproduction of source-line breakpoint placement gating:
 * a Rust source referenced by DWARF in a project wasm module must be
 * accepted by [WasmLineBreakpointType.canPutAt].
 *
 * Exercises the real chain: wasm file type -> DwarfIndex ->
 * [WasmDwarfSourceNamesCache] -> canPutAt, on the committed fixture
 * wasmParser/src/commonTest/resources/rust/dwarf-memvars.wasm.
 */
class WasmLineBreakpointCanPutAtTest : BasePlatformTestCase() {

    fun testCanPutAtRustSourceReferencedByDwarf() {
        val resourceDir = File("../wasmParser/src/commonTest/resources/rust")
        val wasmFixture = File(resourceDir, "dwarf-memvars.wasm")
        val rsFixture = File(resourceDir, "dwarf-memvars.rs")
        assertTrue("Fixture must exist: ${wasmFixture.absolutePath}", wasmFixture.exists())

        val rsFile = myFixture.addFileToProject("rust/dwarf-memvars.rs", rsFixture.readText()).virtualFile
        WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val wasm = rsFile.parent.createChildData(this, "dwarf-memvars.wasm")
            wasm.setBinaryContent(wasmFixture.readBytes())
            wasm
        }

        val keys = runReadAction {
            FileBasedIndex.getInstance().getAllKeys(DwarfIndexId, project)
        }
        assertTrue(
            "DwarfIndex must contain dwarf-memvars.rs, got: $keys",
            keys.any { it.substringAfterLast('/') == "dwarf-memvars.rs" },
        )

        // The cache loads asynchronously; the VFS listener forces a refresh
        // when the wasm file appears. Poll until it contains the name.
        val cache = project.service<WasmDwarfSourceNamesCache>()
        cache.scheduleRefreshIfStale()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline &&
            cache.sourceFileNames()?.contains("dwarf-memvars.rs") != true
        ) {
            Thread.sleep(50)
        }
        assertEquals(
            "WasmDwarfSourceNamesCache never picked up dwarf-memvars.rs; cache: ${cache.sourceFileNames()}",
            true,
            cache.sourceFileNames()?.contains("dwarf-memvars.rs"),
        )

        // Source line 10 is 0-based line 9 for XLineBreakpointType.
        assertTrue(
            "canPutAt must accept dwarf-memvars.rs:10",
            WasmLineBreakpointType().canPutAt(rsFile, 9, project),
        )
    }
}
