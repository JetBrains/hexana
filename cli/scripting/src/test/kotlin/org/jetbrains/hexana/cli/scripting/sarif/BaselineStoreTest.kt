package org.jetbrains.hexana.cli.scripting.sarif

import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaselineStoreTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val findings = listOf(
        CheckFinding(checkId = "check.one", severity = CheckSeverity.ERROR, message = "err msg", anchor = ScriptAnchor.Function(42)),
        CheckFinding(checkId = "check.two", severity = CheckSeverity.WARNING, message = "warn msg", anchor = null),
    )

    @Test
    fun classifyOnEmptyDirReturnsAllNew() {
        val store = BaselineStore(tmp.newFolder("baseline").toPath())
        val classified = store.classify(findings)
        assertEquals(findings.size, classified.size)
        assertTrue(classified.all { it.baselineState == BaselineState.NEW })
    }

    @Test
    fun afterUpdateClassifyReturnsAllUnchanged() {
        val dir = tmp.newFolder("baseline").toPath()
        val store = BaselineStore(dir)
        store.update(findings)
        val classified = store.classify(findings)
        assertEquals(findings.size, classified.size)
        assertTrue(classified.all { it.baselineState == BaselineState.UNCHANGED }, "All must be UNCHANGED, got: $classified")
    }

    @Test
    fun removedFindingBecomesAbsent() {
        val dir = tmp.newFolder("baseline").toPath()
        val store = BaselineStore(dir)
        store.update(findings)

        // Now classify with only the second finding; the first becomes ABSENT
        val partialFindings = listOf(findings[1])
        val classified = store.classify(partialFindings)

        val unchanged = classified.filter { it.baselineState == BaselineState.UNCHANGED }
        val absent = classified.filter { it.baselineState == BaselineState.ABSENT }

        assertEquals(1, unchanged.size, "One finding should be UNCHANGED")
        assertEquals(1, absent.size, "One finding should be ABSENT")
        assertEquals(findings[1], unchanged[0].finding)
        assertEquals("check.one", absent[0].finding.checkId)
        // Absent finding carries the stored message
        assertEquals("err msg", absent[0].finding.message)
    }

    @Test
    fun updateIdempotentByteEquality() {
        val dir = tmp.newFolder("baseline").toPath()
        val store = BaselineStore(dir)
        store.update(findings)

        // Read files after first update
        val filesAfterFirst = dir.toFile().listFiles()!!
            .sortedBy { it.name }
            .associate { it.name to it.readText() }

        store.update(findings)

        // Read files after second update
        val filesAfterSecond = dir.toFile().listFiles()!!
            .sortedBy { it.name }
            .associate { it.name to it.readText() }

        assertEquals(filesAfterFirst, filesAfterSecond, "Baseline files must be byte-identical across two updates with the same input")
    }

    @Test
    fun missingDirectoryTreatsEverythingAsNew() {
        val nonExistent = tmp.root.toPath().resolve("does-not-exist")
        val store = BaselineStore(nonExistent)
        val classified = store.classify(findings)
        assertTrue(classified.all { it.baselineState == BaselineState.NEW })
    }

    /**
     * Check IDs containing '_' and ':' round-trip correctly and do not collide.
     *
     * On Windows the filename sanitizer replaces ':' with '_', so `user:foo` and
     * `user_foo` would map to the same filename without the in-document `"checkId"` field.
     * This test verifies that the two stores remain separate regardless of the
     * filename mapping.
     */
    @Test
    fun checkIdWithColonAndUnderscoreDoNotCollide() {
        val dir = tmp.newFolder("baseline-collision").toPath()
        val store = BaselineStore(dir)

        val colonId = "user:my-check"
        val underscoreId = "user_my-check"

        val colonFinding = CheckFinding(checkId = colonId, severity = CheckSeverity.ERROR, message = "colon msg", anchor = null)
        val underscoreFinding = CheckFinding(checkId = underscoreId, severity = CheckSeverity.WARNING, message = "underscore msg", anchor = null)

        store.update(listOf(colonFinding, underscoreFinding))

        // Both ids round-trip to UNCHANGED after update.
        val classified = store.classify(listOf(colonFinding, underscoreFinding))
        val states = classified.associate { it.finding.checkId to it.baselineState }
        assertEquals(BaselineState.UNCHANGED, states[colonId], "colon id must be UNCHANGED")
        assertEquals(BaselineState.UNCHANGED, states[underscoreId], "underscore id must be UNCHANGED")

        // Classify with only one id: the other becomes ABSENT (not merged or lost).
        val colonOnly = store.classify(listOf(colonFinding))
        assertEquals(1, colonOnly.count { it.baselineState == BaselineState.UNCHANGED }, "colon id must be UNCHANGED")
        assertEquals(1, colonOnly.count { it.baselineState == BaselineState.ABSENT }, "underscore id must be ABSENT")
        assertEquals(underscoreId, colonOnly.first { it.baselineState == BaselineState.ABSENT }.finding.checkId)
    }
}
