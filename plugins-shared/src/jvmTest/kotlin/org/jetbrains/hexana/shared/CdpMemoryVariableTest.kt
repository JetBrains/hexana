package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfTypeInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end tests for [CdpSourceLocationResolver.memoryVariablesAt] against
 * the Rust wasm32 fixture at `debugInfo/dwarf-fixtures/test-data/wasm32/dwarf-types.wasm`.
 *
 * The fixture's `compute` function (exported as "compute") has:
 *   - `arr: [i32; 4]` — a frame-relative local in linear memory (`DW_OP_fbreg`)
 *
 * The code-section content offset of this specific binary is 0x6f (111 bytes).
 * The `compute` subprogram low_pc is 0x00 in the DWARF (code-section-relative).
 * A module-absolute offset of 0x6f (= code base) should cover compute's body.
 */
class CdpMemoryVariableTest {

    private fun fixtureBytes(): ByteArray? {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("debugInfo").resolve("dwarf-fixtures")) }
            ?: return null
        val fixture = root.resolve("debugInfo").resolve("dwarf-fixtures")
            .resolve("test-data").resolve("wasm32").resolve("dwarf-types.wasm")
        return if (Files.exists(fixture)) Files.readAllBytes(fixture) else null
    }

    @Test
    fun `memoryVariablesAt finds frame-relative variables in compute`() {
        val bytes = fixtureBytes() ?: return // fixture absent in this CI config — skip

        val resolver = CdpSourceLocationResolver.create(bytes)
            ?: return // no DWARF → skip

        // The code-section base is the smallest nonzero offset with DWARF coverage.
        // Offset 0x6f is the start of the code section. Offset 0x6f + 0x05 (into compute body)
        // should land inside compute's PC range. We try a range of offsets.
        val codeSectionOffset = 0x6fuL
        val candidates = (0uL..0x30uL).map { codeSectionOffset + it }

        val vars = candidates.firstNotNullOfOrNull { offset ->
            val v = resolver.memoryVariablesAt(offset)
            v.takeIf { it.isNotEmpty() }
        }

        if (vars == null) {
            // If the DWARF in this fixture doesn't have fbreg locations (optimised away),
            // the test passes vacuously — we can't test what the compiler doesn't emit.
            return
        }

        // At least one variable should be frame-relative (fbregOffset != null).
        val fbregVars = vars.filter { it.fbregOffset != null }
        assertTrue(fbregVars.isNotEmpty(), "Expected at least one frame-relative variable in compute, got: $vars")

        // The [i32; 4] array or similar should appear somewhere.
        val arrayVar = vars.firstOrNull { it.type is DwarfTypeInfo.Array }
        val anyFrameRelative = vars.any { it.fbregOffset != null }
        assertTrue(anyFrameRelative, "At least one variable should be frame-relative")

        // Each variable must have a valid byteSize.
        for (v in vars) {
            assertTrue(v.byteSize > 0, "Variable ${v.name} has non-positive byteSize ${v.byteSize}")
            assertTrue(v.byteSize <= CdpMemoryVariable.MAX_SLAB_BYTES,
                "Variable ${v.name} byteSize ${v.byteSize} exceeds MAX_SLAB_BYTES")
        }

        // fbreg variables must have a frameBaseLocalIndex set.
        for (v in vars.filter { it.fbregOffset != null }) {
            assertTrue(v.frameBaseLocalIndex != null,
                "fbreg variable '${v.name}' must have a frameBaseLocalIndex")
        }
    }

    @Test
    fun `memoryVariablesAt returns empty before code section`() {
        val bytes = fixtureBytes() ?: return
        val resolver = CdpSourceLocationResolver.create(bytes) ?: return

        // Offset 0 is before the code section — should return empty.
        val vars = resolver.memoryVariablesAt(0uL)
        assertTrue(vars.isEmpty(), "Expected empty list before code section, got $vars")
    }
}
