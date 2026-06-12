package org.jetbrains.hexana.wasm.analysis.diff

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmEntityDiffTest {

    private fun input(key: String, name: String? = null, detail: String? = null) =
        EntityInput(MatchKey(key), name, detail)

    private fun diffOne(
        kind: EntityKind,
        lefts: List<EntityInput>,
        rights: List<EntityInput>,
    ): List<EntityDiffEntry> =
        WasmEntityDiff.diff(mapOf(kind to (lefts to rights))).entries

    @Test
    fun `added removed modified identical across one kind`() {
        val lefts = listOf(
            input("imp:env print", "print", "func type=0"),     // identical
            input("imp:env tick", "tick", "func type=1"),        // modified (detail changes)
            input("imp:env gone", "gone", "func type=2"),        // removed
        )
        val rights = listOf(
            input("imp:env print", "print", "func type=0"),
            input("imp:env tick", "tick", "func type=9"),        // detail differs -> MODIFIED
            input("imp:env new", "new", "func type=3"),          // added
        )

        val entries = diffOne(EntityKind.IMPORT, lefts, rights)

        assertEquals(1, entries.count { it.classification == DiffClass.IDENTICAL })
        assertEquals(1, entries.count { it.classification == DiffClass.MODIFIED })
        assertEquals(1, entries.count { it.classification == DiffClass.REMOVED })
        assertEquals(1, entries.count { it.classification == DiffClass.ADDED })

        val modified = entries.single { it.classification == DiffClass.MODIFIED }
        assertEquals("func type=1", modified.detailA)
        assertEquals("func type=9", modified.detailB)

        val added = entries.single { it.classification == DiffClass.ADDED }
        assertEquals(null, added.detailA)
        assertEquals("func type=3", added.detailB)

        val removed = entries.single { it.classification == DiffClass.REMOVED }
        assertEquals("func type=2", removed.detailA)
        assertEquals(null, removed.detailB)
    }

    @Test
    fun `modified detected from detail change at same key`() {
        val entries = diffOne(
            EntityKind.GLOBAL,
            listOf(input("glob:0", "g", "i32 mut")),
            listOf(input("glob:0", "g", "i64 mut")),
        )
        val entry = entries.single()
        assertEquals(DiffClass.MODIFIED, entry.classification)
    }

    @Test
    fun `duplicate keys match one-to-one and remainder is added`() {
        // Three entities share a key on the left, four on the right: 3 match, 1 ADDED.
        val lefts = List(3) { input("fn:idx:dup", "dup", "d$it") }
        val rights = List(4) { input("fn:idx:dup", "dup", "d$it") }

        val entries = diffOne(EntityKind.FUNCTION, lefts, rights)

        assertEquals(4, entries.size)
        assertEquals(1, entries.count { it.classification == DiffClass.ADDED })
        // The three positional matches have equal detail -> IDENTICAL.
        assertEquals(3, entries.count { it.classification == DiffClass.IDENTICAL })
        assertEquals(0, entries.count { it.classification == DiffClass.REMOVED })
    }

    @Test
    fun `summary counts per kind and class`() {
        val result = WasmEntityDiff.diff(
            mapOf(
                EntityKind.IMPORT to (
                    listOf(input("imp:env a", "a", "f")) to
                        listOf(input("imp:env a", "a", "f"), input("imp:env b", "b", "f"))
                    ),
                EntityKind.EXPORT to (
                    listOf(input("exp:x", "x", "0#1")) to emptyList()
                    ),
            ),
        )

        val summary = result.summary()
        assertEquals(1, summary[EntityKind.IMPORT]?.get(DiffClass.IDENTICAL))
        assertEquals(1, summary[EntityKind.IMPORT]?.get(DiffClass.ADDED))
        assertEquals(1, summary[EntityKind.EXPORT]?.get(DiffClass.REMOVED))
    }
}
