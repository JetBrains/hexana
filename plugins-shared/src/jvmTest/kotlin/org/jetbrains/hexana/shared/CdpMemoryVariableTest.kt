package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfTypeInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests for [CdpSourceLocationResolver.memoryVariablesAt] against
 * the Rust wasm32 fixture `debugInfo/dwarf-fixtures/test-data/wasm32/dwarf-memvars.wasm`.
 *
 * Fixture source: `dwarf-memvars.rs`, compiled with:
 *     rustc --target=wasm32-unknown-unknown --crate-type=cdylib
 *           -g -C opt-level=0 -C lto=off -o dwarf-memvars.wasm lib.rs
 *
 * Verified with `llvm-dwarfdump --debug-info dwarf-memvars.wasm`:
 *   - `make` subprogram: low_pc 0x03, high_pc +0x104,
 *     frame_base = DW_OP_WASM_location local 1
 *   - variable `p`: DW_OP_fbreg +4, type `lib::Point`
 *     (struct, byte_size 8; members x:i32@0, y:i16@4, z:i8@6)
 *
 * `opt-level=0` matters: at -O1 rustc const-propagates locals into
 * `DW_OP_lit`/`DW_OP_stack_value` pieces and nothing lives in linear memory.
 */
class CdpMemoryVariableTest {

    private fun fixtureBytes(): ByteArray? {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("debugInfo").resolve("dwarf-fixtures")) }
            ?: return null
        val fixture = root.resolve("debugInfo").resolve("dwarf-fixtures")
            .resolve("test-data").resolve("wasm32").resolve("dwarf-memvars.wasm")
        return if (Files.exists(fixture)) Files.readAllBytes(fixture) else null
    }

    @Test
    fun `memoryVariablesAt finds the fbreg struct local in make`() {
        val bytes = fixtureBytes() ?: fail("dwarf-memvars.wasm fixture missing")
        val resolver = CdpSourceLocationResolver.create(bytes)
            ?: fail("resolver creation failed for dwarf-memvars.wasm")

        // Module-absolute offsets inside make's body, from the line table
        // (line 11 is `p.x += 1;` — after the struct is initialized).
        val offsets = resolver.sourceToAllWasmOffsets("lib.rs", 11)
        assertTrue(offsets.isNotEmpty(), "Expected line-table offsets for lib.rs:11")

        val vars = resolver.memoryVariablesAt(offsets.first().toULong())
        val p = vars.firstOrNull { it.name == "p" }
            ?: fail("Expected fbreg variable 'p' in make, got: $vars")

        assertEquals(4L, p.fbregOffset, "p is at DW_OP_fbreg +4")
        assertNotNull(p.frameBaseLocalIndex, "fbreg variable must carry the frame-base local")
        assertTrue(p.byteSize > 0 && p.byteSize <= CdpMemoryVariable.MAX_SLAB_BYTES)

        val struct = p.type as? DwarfTypeInfo.Struct
            ?: fail("p must resolve to a Struct type (CU-relative DW_AT_type), got ${p.type}")
        assertEquals("Point", struct.name)
        assertEquals(8, struct.byteSize)
        val memberOffsets: Map<String, Int> = struct.members.associate { (it.name ?: "?") to it.offset }
        assertEquals(mapOf("x" to 0, "y" to 4, "z" to 6), memberOffsets)
    }

    @Test
    fun `memoryVariablesAt returns empty before code section`() {
        val bytes = fixtureBytes() ?: return
        val resolver = CdpSourceLocationResolver.create(bytes) ?: return

        val vars = resolver.memoryVariablesAt(0uL)
        assertTrue(vars.isEmpty(), "Expected empty list before code section, got $vars")
    }
}
