package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.CdpSourceLocationResolver
import java.io.File

class CdpSourceLocationResolverTest : UsefulTestCase() {

    private fun loadDebugAddWasm(): ByteArray {
        val file = File("../ui-driver-tests/testData/projects/hexana_wasm_project/debug-add.wasm")
        assertTrue("Test resource must exist: ${file.absolutePath}", file.exists())
        return file.readBytes()
    }

    fun testCodeSectionOffsetAndBreakpointOffset() {
        val bytes = loadDebugAddWasm()
        val resolver = CdpSourceLocationResolver.create(bytes)
        assertNotNull("Resolver should be created from debug-add.wasm", resolver)
        resolver!!

        // DWARF line table for debug_add.rs:
        //   line 9  (fn signature) -> DWARF offset 0x1e = 30
        //   line 10 (a + b)        -> DWARF offset 0x46 = 70
        // Code section content starts at byte 78 in the module.
        // V8 CDP expects module-absolute offsets as columnNumber.
        // So line 10 should map to module offset 78 + 70 = 148.

        // IDE line is 0-based, so source line 10 = IDE line 9
        val offsets = resolver.sourceToAllWasmOffsets("debug_add.rs", 9)
        assertFalse("Should find offsets for debug_add.rs line 10 (IDE line 9)", offsets.isEmpty())

        println("[DEBUG_LOG] offsets for debug_add.rs IDE line 9: $offsets")
        println("[DEBUG_LOG] offsets hex: ${offsets.map { "0x${it.toString(16)}" }}")

        // The correct module offset should be 148 (78 + 70), not 195
        // If this fails, the codeSectionOffset or DWARF offset base is wrong
        for (offset in offsets) {
            println("[DEBUG_LOG] offset=$offset (0x${offset.toString(16)})")
        }

        // The correct module offset is 148 = codeSectionOffset(78) + DWARF offset(0x46=70)
        // 0x46 is the first (prologue_end) entry for line 10 in the add function.
        // Previously the code used MAX offset per function which picked 0x75 (epilogue),
        // giving the wrong module offset 195.
        assertTrue("Expected offset 148 (0x94) for line 10, got $offsets", offsets.contains(148.toULong()))
    }

    /**
     * Verifies that wasmOffsetToSource returns the full DWARF path
     * in SourceLocation.file. For debug-add.wasm the DWARF records
     * the file as "debug_add.rs" (no compDir) so the full path equals
     * the basename; the important thing is it is not null and matches.
     */
    fun testWasmOffsetToSourceExposesFullDwarfPath() {
        val bytes = loadDebugAddWasm()
        val resolver = CdpSourceLocationResolver.create(bytes)!!

        // Module offset 148 = codeSectionOffset(78) + DWARF(70) maps to debug_add.rs line 10
        val loc = resolver.wasmOffsetToSource(148.toULong())
        assertNotNull("wasmOffsetToSource(148) should resolve", loc)
        loc!!

        // The full DWARF path must be present (not just a basename stripped to "")
        assertFalse("SourceLocation.file must not be blank", loc.file.isBlank())
        // For this fixture the DWARF path recorded is "debug_add.rs"
        assertEquals(
            "SourceLocation.file should be the DWARF path 'debug_add.rs'",
            "debug_add.rs",
            loc.file,
        )
        // Line must be 1-based DWARF line (source line 10)
        assertEquals("Expected DWARF line 10", 10, loc.line)
    }

    /**
     * Verifies that sourceToAllWasmOffsets works when the caller
     * passes a full path (with directories) instead of just a basename.
     * Path-suffix matching should resolve against the DWARF-recorded path.
     * For debug-add.wasm the DWARF path is "debug_add.rs" (no directory),
     * so a caller path of ".../project/debug_add.rs" should still resolve.
     */
    fun testPathSuffixResolutionWithFullPath() {
        val bytes = loadDebugAddWasm()
        val resolver = CdpSourceLocationResolver.create(bytes)!!

        // Simulate the IDE passing a full VirtualFile path that ends with the DWARF basename
        val fakeFullPath = "/Users/dev/my_project/src/debug_add.rs"
        val offsets = resolver.sourceToAllWasmOffsets(fakeFullPath, 9)
        assertFalse(
            "Path-suffix resolution should find offsets when given a full path ending in debug_add.rs",
            offsets.isEmpty(),
        )
        assertTrue(
            "Expected offset 148 via path-suffix resolution, got $offsets",
            offsets.contains(148.toULong()),
        )
    }

    /**
     * Tests the pure-function path-suffix matching logic used by
     * findSourceFileByName to pick the best candidate among files
     * that share the same basename.
     *
     * This is a unit test of the selection algorithm independent of
     * the DWARF fixture.
     */
    fun testPickBestSuffixMatchLogic() {
        // Simulate the three candidates all named "lib.rs" but in different crates
        data class Candidate(val path: String)

        fun pathSuffixMatches(candidatePath: String, dwarfSuffix: String): Boolean {
            val norm = candidatePath.replace('\\', '/')
            val suffix = dwarfSuffix.replace('\\', '/')
            if (!norm.endsWith(suffix)) return false
            val prefixLen = norm.length - suffix.length
            return prefixLen == 0 || norm[prefixLen - 1] == '/'
        }

        fun scoreSuffix(candidatePath: String, dwarfPath: String): Int {
            val norm = candidatePath.replace('\\', '/')
            val segments = dwarfPath.replace('\\', '/').split('/')
            for (startIdx in 0 until segments.size) {
                val suffix = segments.drop(startIdx).joinToString("/")
                if (pathSuffixMatches(norm, suffix)) return segments.size - startIdx
            }
            return -1
        }

        val dwarfPath = "mycrate/src/lib.rs"

        val candidates = listOf(
            "/project/anothercrate/src/lib.rs",  // shares only "src/lib.rs"
            "/project/mycrate/src/lib.rs",         // shares full "mycrate/src/lib.rs"
            "/project/thirdcrate/lib.rs",           // shares only "lib.rs"
        )

        val scores = candidates.map { scoreSuffix(it, dwarfPath) }
        println("[DEBUG_LOG] scores=$scores")

        // mycrate/src/lib.rs should score 3 (all 3 segments match)
        assertEquals("mycrate scores 3 segments", 3, scores[1])
        // anothercrate/src/lib.rs scores 2 (src/lib.rs matches)
        assertEquals("anothercrate scores 2 segments", 2, scores[0])
        // thirdcrate/lib.rs scores 1 (lib.rs matches)
        assertEquals("thirdcrate scores 1 segment", 1, scores[2])

        val bestIdx = scores.indices.maxByOrNull { scores[it] }!!
        assertEquals("mycrate/src/lib.rs wins", "/project/mycrate/src/lib.rs", candidates[bestIdx])
    }

    /**
     * Tests that knownDwarfPaths returns the full paths recorded in
     * the DWARF line table (not just basenames).
     */
    fun testKnownDwarfPathsContainsFullPath() {
        val bytes = loadDebugAddWasm()
        val resolver = CdpSourceLocationResolver.create(bytes)!!
        val paths = resolver.knownDwarfPaths()
        assertFalse("knownDwarfPaths must not be empty", paths.isEmpty())
        // debug_add.wasm records "debug_add.rs" as the DWARF path
        assertTrue(
            "knownDwarfPaths should contain 'debug_add.rs', got: $paths",
            paths.any { it.endsWith("debug_add.rs") },
        )
    }
}
