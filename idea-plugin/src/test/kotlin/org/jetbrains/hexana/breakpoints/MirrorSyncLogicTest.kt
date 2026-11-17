package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity

/**
 * Unit tests for [MirrorSyncLogic].
 *
 * Tests use a [MirrorSyncLogic] with recording lambdas and assert exact
 * operation counts. No IntelliJ platform services are touched.
 */
class MirrorSyncLogicTest : UsefulTestCase() {

    private val identity = BinaryIdentity(contentSha256 = "aa", elfBuildId = null)
    private val anchor = BinaryAnchor.Wasm(funcIdx = 0, byteOffset = 10L, funcName = null, instrOrdinal = 0)

    private fun makeBp(id: String) = BinaryBreakpoint(
        id = id,
        identity = identity,
        anchor = anchor,
        enabled = true,
        rehomed = false,
        stale = false,
        filePath = null,
    )

    // ---- Registry add -> exactly 1 create op ----

    fun testRegistryAdd_exactlyOneCreate() {
        val creates = mutableListOf<String>()
        val removes = mutableListOf<String>()
        val enables = mutableListOf<Pair<String, Boolean>>()

        val logic = MirrorSyncLogic(
            onCreate = { creates.add(it) },
            onRemove = { removes.add(it) },
            onSetEnabled = { id, en -> enables.add(id to en) },
        )

        logic.onRegistryUpdate(listOf(makeBp("bp-1")))

        assertEquals("Exactly one create op", 1, creates.size)
        assertEquals("bp-1", creates[0])
        assertTrue("No removes", removes.isEmpty())
        assertTrue("No enable changes", enables.isEmpty())
        assertTrue("bp-1 is now mirrored", logic.isMirrored("bp-1"))
    }

    // ---- XBreakpoint enable toggle -> exactly 1 registry setEnabled op ----

    fun testXbpEnabledToggle_exactlyOneRegistryOp() {
        val enables = mutableListOf<Pair<String, Boolean>>()
        val logic = MirrorSyncLogic(
            onCreate = {},
            onRemove = {},
            onSetEnabled = { id, en -> enables.add(id to en) },
        )

        // Seed as already mirrored.
        logic.seedMirrored(listOf("bp-1"))

        logic.onXbpEnabledChanged("bp-1", enabled = false)

        assertEquals("Exactly one setEnabled op", 1, enables.size)
        assertEquals("bp-1" to false, enables[0])
    }

    // ---- 3-round exchange: no oscillation, exact op count ----

    fun testThreeRoundExchange_noOscillation() {
        val creates = mutableListOf<String>()
        val removes = mutableListOf<String>()
        val enables = mutableListOf<Pair<String, Boolean>>()

        val logic = MirrorSyncLogic(
            onCreate = { creates.add(it) },
            onRemove = { removes.add(it) },
            onSetEnabled = { id, en -> enables.add(id to en) },
        )

        // Round 1: registry add -> create op
        logic.onRegistryUpdate(listOf(makeBp("bp-1")))
        // Round 2: platform toggles enabled -> setEnabled op
        logic.onXbpEnabledChanged("bp-1", enabled = false)
        // Round 3: platform removes -> remove op, but NOT another registry remove round
        logic.onXbpRemoved("bp-1")

        assertEquals("3 rounds, 1 create", 1, creates.size)
        assertEquals("3 rounds, 1 enable-change", 1, enables.size)
        assertEquals("3 rounds, 1 remove", 1, removes.size)
        val totalOps = creates.size + enables.size + removes.size
        assertEquals("Total ops must be exactly 3, no oscillation", 3, totalOps)
    }

    // ---- Syncing guard: registry remove while syncing does not re-enter ----

    fun testSyncingGuard_noPlatformFeedback() {
        val creates = mutableListOf<String>()
        val removes = mutableListOf<String>()

        val logic = MirrorSyncLogic(
            onCreate = {
                creates.add(it)
                // Simulate platform calling back during sync (should be ignored).
                // This simulates a badly-behaved XBreakpointListener.
            },
            onRemove = { removes.add(it) },
            onSetEnabled = { _, _ -> },
        )

        logic.onRegistryUpdate(listOf(makeBp("bp-A"), makeBp("bp-B")))

        // Neither the create callbacks nor any subsequent re-entrant event
        // should trigger extra removes.
        assertEquals(2, creates.size)
        assertTrue("No removes during plain add", removes.isEmpty())
    }

    // ---- Registry remove -> exactly 1 remove op ----

    fun testRegistryRemove_exactlyOneRemove() {
        val creates = mutableListOf<String>()
        val removes = mutableListOf<String>()

        val logic = MirrorSyncLogic(
            onCreate = { creates.add(it) },
            onRemove = { removes.add(it) },
            onSetEnabled = { _, _ -> },
        )

        logic.onRegistryUpdate(listOf(makeBp("bp-1")))
        logic.onRegistryUpdate(emptyList())

        assertEquals("1 create", 1, creates.size)
        assertEquals("1 remove", 1, removes.size)
        assertEquals("bp-1", removes[0])
        assertFalse("bp-1 no longer mirrored", logic.isMirrored("bp-1"))
    }
}
