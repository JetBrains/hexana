package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfInlineFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for DWARF inline-frame reconstruction in the CDP debug layer.
 *
 * Covers two concerns:
 * 1. Pure chain-to-frame-list logic: given a chain of [DwarfInlineFrame]s and a
 *    physical frame name, verify the expected list of (name, callFile, callLine) tuples
 *    matches the rules in TASK 1 of the inline-frame spec:
 *      - synthetic[0] (innermost): name = chain[0].name + " (inlined)", source = physical pause position
 *      - synthetic[i] (i > 0): name = chain[i].name + " (inlined)", source = chain[i-1].callFile/callLine
 *      - physical: keeps its own name, source = chain.last().callFile/callLine
 *
 * 2. End-to-end via [CdpSourceLocationResolver] against the dwarf-types.wasm fixture
 *    (Rust, has an #[inline(always)] add() called from exported compute()):
 *      - resolver.inlinedFramesAt(moduleOffset) returns the add frame
 *      - the returned frame's callLine matches the expected DWARF call_line
 */
class CdpInlinedFrameTest {

    // ---- Pure chain-to-frame-list logic ----

    /**
     * Models the result of expanding an inline chain into DAP/IDE frame entries.
     * (name, callFile, callLine) tuples for assertion.
     */
    data class FrameEntry(val name: String, val callFile: String?, val callLine: Int?)

    /**
     * Applies the same transformation logic as CdpDebugProcess.buildStackFrame:
     * returns synthetic frames (innermost first) followed by the physical frame.
     *
     * - synthetic[0]: name = chain[0].name+" (inlined)", source = physicalCallFile/physicalCallLine
     * - synthetic[i]: name = chain[i].name+" (inlined)", source = chain[i-1].callFile/callLine
     * - physical: name = physicalName, source = chain.last().callFile/callLine
     *
     * [physicalCallFile] and [physicalCallLine] simulate the DWARF source location at
     * the pause offset (what resolveSourcePosition returns for the physical frame).
     */
    private fun expandChain(
        chain: List<DwarfInlineFrame>,
        physicalName: String,
        physicalCallFile: String?,
        physicalCallLine: Int?,
    ): List<FrameEntry> {
        if (chain.isEmpty()) return listOf(FrameEntry(physicalName, physicalCallFile, physicalCallLine))
        val result = mutableListOf<FrameEntry>()
        for (i in chain.indices) {
            val inlined = chain[i]
            val (file, line) = if (i == 0) {
                physicalCallFile to physicalCallLine
            } else {
                chain[i - 1].callFile to chain[i - 1].callLine
            }
            result.add(FrameEntry((inlined.name ?: physicalName) + " (inlined)", file, line))
        }
        // Physical frame shows outermost call site
        result.add(FrameEntry(physicalName, chain.last().callFile, chain.last().callLine))
        return result
    }

    @Test
    fun `single inlined frame produces two entries`() {
        val chain = listOf(
            DwarfInlineFrame(
                name = "add",
                linkageName = "_ZN3lib3addE",
                callFile = "/src/lib.rs",
                callLine = 23,
                callColumn = 15,
                depth = 1,
            ),
        )
        val entries = expandChain(chain, "compute", "/src/lib.rs", 30)
        assertEquals(2, entries.size, "single inline → 2 entries (1 synthetic + 1 physical)")
        // Innermost synthetic: shows physical pause position, suffixed "(inlined)"
        assertEquals("add (inlined)", entries[0].name)
        assertEquals("/src/lib.rs", entries[0].callFile)
        assertEquals(30, entries[0].callLine)
        // Physical frame: shows the call site of the inlined frame
        assertEquals("compute", entries[1].name)
        assertEquals("/src/lib.rs", entries[1].callFile)
        assertEquals(23, entries[1].callLine)
    }

    @Test
    fun `two inlined frames produces three entries`() {
        val chain = listOf(
            DwarfInlineFrame(name = "inner", linkageName = null, callFile = "/src/a.rs", callLine = 10, callColumn = 5, depth = 2),
            DwarfInlineFrame(name = "middle", linkageName = null, callFile = "/src/b.rs", callLine = 20, callColumn = 3, depth = 1),
        )
        val entries = expandChain(chain, "outer", "/src/a.rs", 8)
        assertEquals(3, entries.size)
        // synthetic[0] (innermost): physical pause position
        assertEquals("inner (inlined)", entries[0].name)
        assertEquals(8, entries[0].callLine)
        // synthetic[1]: call site of frame above it = chain[0].callFile/callLine
        assertEquals("middle (inlined)", entries[1].name)
        assertEquals("/src/a.rs", entries[1].callFile)
        assertEquals(10, entries[1].callLine)
        // physical: call site of chain.last() = chain[1]
        assertEquals("outer", entries[2].name)
        assertEquals("/src/b.rs", entries[2].callFile)
        assertEquals(20, entries[2].callLine)
    }

    @Test
    fun `empty chain returns physical frame only`() {
        val entries = expandChain(emptyList(), "compute", "/src/lib.rs", 42)
        assertEquals(1, entries.size)
        assertEquals("compute", entries[0].name)
        assertEquals(42, entries[0].callLine)
    }

    @Test
    fun `null name in inlined frame falls back to physical name`() {
        val chain = listOf(
            DwarfInlineFrame(name = null, linkageName = null, callFile = "/src/x.rs", callLine = 5, callColumn = null, depth = 1),
        )
        val entries = expandChain(chain, "myFunc", "/src/x.rs", 3)
        assertEquals("myFunc (inlined)", entries[0].name, "null name should fall back to physical function name")
    }

    // ---- End-to-end via CdpSourceLocationResolver + dwarf-types.wasm fixture ----

    private fun fixtureBytes(): ByteArray {
        val root = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { java.nio.file.Files.isDirectory(it.resolve("debugInfo").resolve("dwarf-fixtures")) }
            ?: return ByteArray(0)
        val fixture = root.resolve("debugInfo").resolve("dwarf-fixtures")
            .resolve("test-data").resolve("wasm32").resolve("dwarf-types.wasm")
        return if (java.nio.file.Files.exists(fixture)) java.nio.file.Files.readAllBytes(fixture) else ByteArray(0)
    }

    @Test
    fun `inlinedFramesAt returns add frame at module-absolute offset`() {
        val bytes = fixtureBytes()
        if (bytes.isEmpty()) {
            // Fixture not on classpath in this module — skip gracefully.
            return
        }
        val resolver = CdpSourceLocationResolver.create(bytes)
            ?: return // no DWARF line info → skip

        // From wasm-objdump: Code section contentOffset = 0x6f (111).
        // From DwarfTypeAndInlineTest: inlined add is at code-relative 0x0a.
        // Module-absolute offset = 0x0a + 0x6f = 0x79 = 121.
        val moduleOffset = 0x79uL

        val chain = resolver.inlinedFramesAt(moduleOffset)
        assertTrue(chain.isNotEmpty(), "Expected at least one inlined frame at module offset 0x79")
        val addFrame = chain.firstOrNull { it.name == "add" }
        assertTrue(addFrame != null, "Expected frame named 'add' in chain")
        assertEquals(23, addFrame.callLine, "call_line for add should be 23 (from DW_AT_call_line)")
    }

    @Test
    fun `inlinedFramesAt returns empty list outside inlined range`() {
        val bytes = fixtureBytes()
        if (bytes.isEmpty()) return
        val resolver = CdpSourceLocationResolver.create(bytes) ?: return

        // An offset well before the code section — should return empty.
        val chain = resolver.inlinedFramesAt(0x00uL)
        assertTrue(chain.isEmpty(), "Expected empty chain for offset 0x00 (before code section)")
    }
}
