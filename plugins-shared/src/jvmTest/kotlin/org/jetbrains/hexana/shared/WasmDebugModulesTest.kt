package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [WasmDebugModules] — the module index a wasm debug session resolves stops
 * against.
 *
 * The point of the class is component-model binaries: a gdbstub addresses a stop as
 * `(module id, offset within that module)`, so a component's offsets belong to the
 * embedded core module's own space and NOT to the component file. Ground truth for
 * the two component fixtures, from `wasm-tools objdump`/`print` plus a live wasmtime
 * 44 + LLDB 22 session (see `kb/wiki/entities/wasm-component-gdbstub-offsets.md`):
 *
 * `add.component.wasm` — one embedded module, magic at component offset 0xb:
 * ```
 * func[0] add: 0x38 local.get 0; 0x3a local.get 1; 0x3c i32.add; 0x3d end   (module-relative)
 *              0x43              0x45              0x47         0x48        (component-absolute)
 * ```
 *
 * `hello_component.wasm` — four embedded modules, magics at 0x7fd, 0x892, 0x306e,
 * 0x3493; the stub reported `pc=0x4000000100001656` in module 1 (`global.get 2`)
 * and `pc=0x400000020000007f` in module 2 (`local.get 0`).
 */
class WasmDebugModulesTest {

    /** A fixture from another module's test resources; the test runs with the module dir as cwd. */
    private fun fixture(relativePath: String): File =
        File(relativePath).also { assertTrue(it.exists(), "test fixture must exist: ${it.absolutePath}") }

    private fun modulesOf(bytes: ByteArray, name: String): WasmDebugModules = assertNotNull(
        WasmDebugModules.create(bytes.toCommonByteBuffer()),
        "$name must index",
    )

    private fun coreModule(): WasmDebugModules {
        val stream = WasmDebugModulesTest::class.java.classLoader.getResourceAsStream("wasmtime-tiny.wasm")
            ?: error("Test resource not found: wasmtime-tiny.wasm")
        return modulesOf(stream.readBytes(), "wasmtime-tiny.wasm")
    }

    private fun singleModuleComponent(): WasmDebugModules {
        val file = fixture("../wasmParser/src/commonTest/resources/component/add.component.wasm")
        return modulesOf(file.readBytes(), file.name)
    }

    private fun multiModuleComponent(): WasmDebugModules {
        val file = fixture("../ui-driver-tests/testData/projects/hexana_wasm_project/hello_component.wasm")
        return modulesOf(file.readBytes(), file.name)
    }

    private fun rowsAt(modules: WasmDebugModules, moduleId: Int?, offset: Int): List<Pair<Int, String>> {
        val module = assertNotNull(
            modules.moduleFor(moduleId, offset),
            "expected a module owning offset 0x${offset.toString(16)} (id=$moduleId)",
        )
        val localIdx = module.disassembly.functionIndexForOffset(offset)
        assertTrue(localIdx >= 0, "the resolved module must have a body at 0x${offset.toString(16)}")
        return module.disassembly.disassembleFunction(localIdx).map { it.offset to it.instruction }
    }

    // ── Core modules keep behaving exactly as before ─────────────────────

    @Test fun `a core module is a single module with id 0`() {
        val modules = coreModule()
        assertFalse(modules.isComponent)
        assertEquals(1, modules.moduleCount)
        assertEquals(0, assertNotNull(modules.moduleFor(0, 0x52)).moduleId)
    }

    @Test fun `a core module resolves with an unknown or out of range id`() {
        val modules = coreModule()
        // The legacy JIT addrmap path reports no module id at all...
        assertEquals(0, assertNotNull(modules.moduleFor(null, 0x52)).moduleId)
        // ...and an id that cannot be right still resolves via the unique match.
        assertEquals(0, assertNotNull(modules.moduleFor(7, 0x52)).moduleId)
    }

    @Test fun `non wasm bytes do not index`() {
        assertNull(WasmDebugModules.create(byteArrayOf(1, 2, 3, 4).toCommonByteBuffer()))
    }

    // ── Components index their embedded core modules ─────────────────────

    @Test fun `a component indexes its embedded core module`() {
        val modules = singleModuleComponent()
        assertTrue(modules.isComponent)
        assertEquals(1, modules.moduleCount)
        assertEquals(
            listOf(0x38 to "local.get 0", 0x3a to "local.get 1", 0x3c to "i32.add", 0x3d to "end"),
            rowsAt(modules, moduleId = 0, offset = 0x3c),
        )
        assertNotNull(
            assertNotNull(modules.moduleFor(0, 0x3c)).stackAnalyzer,
            "the embedded module must carry its own stack analyzer",
        )
    }

    /**
     * The regression this class exists for: reading the stub's offset as a
     * component-file offset resolves nothing (or, worse, the wrong body), which is
     * why the Wasm tab used to come up empty for every component.
     */
    @Test fun `component offsets are module relative not component absolute`() {
        val modules = singleModuleComponent()
        val moduleRelative = 0x3c
        val componentAbsolute = 0x47 // the same i32.add, counted from the component's magic
        assertNotNull(modules.moduleFor(0, moduleRelative))
        assertNull(
            modules.moduleFor(0, componentAbsolute),
            "component-absolute offsets must not resolve — the stub does not report them",
        )
    }

    @Test fun `each embedded module of a multi module component resolves in its own space`() {
        val modules = multiModuleComponent()
        assertTrue(modules.isComponent)
        assertEquals(4, modules.moduleCount)

        // Live-observed stop in module 1 (the wasi_snapshot_preview1 adapter).
        assertEquals(1, assertNotNull(modules.moduleFor(1, 0x1656)).moduleId)
        assertTrue(
            rowsAt(modules, moduleId = 1, offset = 0x1656).contains(0x1656 to "global.get 2"),
            "expected the live-observed global.get 2 at module 1 offset 0x1656",
        )

        // Live-observed stop in module 2 (the adapter trampolines).
        assertEquals(2, assertNotNull(modules.moduleFor(2, 0x7f)).moduleId)
        assertTrue(
            rowsAt(modules, moduleId = 2, offset = 0x7f).contains(0x7f to "local.get 0"),
            "expected the live-observed local.get 0 at module 2 offset 0x7f",
        )
    }

    @Test fun `an offset outside every code section resolves to no module`() {
        // Offset 0 is each module's magic, long before its code section.
        assertNull(multiModuleComponent().moduleFor(1, 0))
    }

    @Test fun `the reported module id wins over an offset another module also owns`() {
        val modules = multiModuleComponent()
        // Module 1's code section covers 0x1656; module 2's covers 0x7f. Asking for
        // module 2 at module 1's offset must not silently answer with module 2.
        val ambiguous = modules.moduleFor(2, 0x1656)
        if (ambiguous != null) assertEquals(1, ambiguous.moduleId) // unique fallback, still not module 2
    }
}
