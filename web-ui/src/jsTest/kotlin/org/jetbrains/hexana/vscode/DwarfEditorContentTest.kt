package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.DwarfOutlineNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DwarfEditorContentTest {

    private fun tab(label: String) = PanelTab(label) { }

    @Test
    fun severalSectionsFoldIntoOneDwarfTab() {
        val grouped = groupPanelTabs("DWARF", listOf(tab(".debug_abbrev"), tab(".debug_info"), tab(".debug_line")))
        assertEquals(listOf("DWARF"), grouped.map { it.label })
    }

    /**
     * The IntelliJ rule, not the grouped-table one: a lone section keeps its own name rather than
     * becoming "DWARF: .debug_info", so the two editors agree.
     */
    @Test
    fun aLoneSectionKeepsItsOwnTab() {
        val grouped = groupPanelTabs("DWARF", listOf(tab(".debug_info")))
        assertEquals(listOf(".debug_info"), grouped.map { it.label })
    }

    @Test
    fun noSectionsMeansNoTab() {
        assertEquals(emptyList(), groupPanelTabs("DWARF", emptyList()).map { it.label })
    }

    // --- flattening -------------------------------------------------------

    private fun leaf(id: String) = DwarfOutlineNode(id = id, label = id)

    private fun node(id: String, vararg children: DwarfOutlineNode) =
        DwarfOutlineNode(id = id, label = id, childrenProvider = { children.toList() })

    private val roots = listOf(
        node("a", leaf("a/1"), node("a/2", leaf("a/2/x"))),
        leaf("b"),
    )

    private fun flatten(expanded: Set<String>) =
        flattenDwarfRows(roots, expanded, mutableMapOf()).map { it.node.id }

    @Test
    fun collapsedTreeShowsOnlyRoots() {
        assertEquals(listOf("a", "b"), flatten(emptySet()))
    }

    @Test
    fun expandingShowsOneLevelAtATime() {
        assertEquals(listOf("a", "a/1", "a/2", "b"), flatten(setOf("a")))
        assertEquals(listOf("a", "a/1", "a/2", "a/2/x", "b"), flatten(setOf("a", "a/2")))
    }

    @Test
    fun expandingANodeWhoseParentIsClosedChangesNothing() {
        assertEquals(listOf("a", "b"), flatten(setOf("a/2")))
    }

    @Test
    fun rowsCarryTheirDepthForIndentation() {
        val rows = flattenDwarfRows(roots, setOf("a", "a/2"), mutableMapOf())
        assertEquals(
            listOf("a" to 0, "a/1" to 1, "a/2" to 1, "a/2/x" to 2, "b" to 0),
            rows.map { it.node.id to it.depth },
        )
    }

    /**
     * The model's children providers are pure and un-memoized, so the cache is what keeps a
     * `.debug_line` program from being re-decoded on every re-flatten.
     */
    @Test
    fun childrenAreParsedOnceAndThenServedFromTheCache() {
        var calls = 0
        val counted = listOf(
            DwarfOutlineNode(id = "unit", label = "unit", childrenProvider = {
                calls++
                listOf(leaf("unit/0"))
            })
        )
        val cache = mutableMapOf<String, List<DwarfOutlineNode>>()
        repeat(3) { flattenDwarfRows(counted, setOf("unit"), cache) }
        assertEquals(1, calls, "children should be parsed once across re-flattens")
    }

    @Test
    fun aCollapsedNodeIsNeverParsed() {
        var calls = 0
        val counted = listOf(
            DwarfOutlineNode(id = "unit", label = "unit", childrenProvider = {
                calls++
                listOf(leaf("unit/0"))
            })
        )
        flattenDwarfRows(counted, emptySet(), mutableMapOf())
        assertEquals(0, calls, "opening a section must not decode collapsed subtrees")
    }

    /**
     * A bare `Int?` reveal target is dropped when it repeats, so scrolling away and pressing
     * find-next onto the row you were already on did nothing. The request id makes each ask
     * distinct.
     */
    @Test
    fun repeatedRevealRequestsForTheSameRowAreDistinct() {
        assertTrue(
            ListReveal(index = 7, requestId = 1) != ListReveal(index = 7, requestId = 2),
            "the same row asked for twice must produce two distinct requests",
        )
        assertEquals(ListReveal(7, 1), ListReveal(7, 1))
    }

    @Test
    fun searchIndexIsNotBuiltUntilItIsAskedFor() {
        var built = 0
        val holder = LazyDwarfSearchIndex {
            built++
            org.jetbrains.hexana.shared.dwarfSearchIndex(roots)
        }
        assertEquals(0, built, "constructing the holder must not walk the tree")
        holder.get()
        holder.get()
        assertEquals(1, built, "the index should be built once and reused")
    }

    @Test
    fun searchIndexReachesCollapsedSubtrees() {
        val index = org.jetbrains.hexana.shared.dwarfSearchIndex(roots)
        val matches = index.idsMatching("a/2/x")
        assertEquals(listOf("a/2/x"), matches)
        assertTrue(
            index.ancestorsOf("a/2/x") == listOf("a/2", "a"),
            "ancestors must be nearest-first so the host can open its way down: ${index.ancestorsOf("a/2/x")}",
        )
    }
}
