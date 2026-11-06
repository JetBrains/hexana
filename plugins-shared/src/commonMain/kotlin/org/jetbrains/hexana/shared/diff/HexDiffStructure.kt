package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.shared.TabPriority
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TableTabsProviderRegistry

/**
 * The structural layer of the hex diff, shared by every host: whether two sides' structure
 * can be compared at all, and their table tabs diffed pairwise.
 */
object HexDiffStructure {
    /**
     * Whether both sides' structure (sections, table tabs) can be compared: the same kind of
     * provider — two ELF files, two WASM modules, … A missing side — a file added or deleted —
     * compares against nothing, so its sections and rows all show as added or removed.
     */
    fun areComparable(left: BinaryProvider?, right: BinaryProvider?): Boolean =
        left == null || right == null || left::class == right::class

    /**
     * Whether the pair is likely to have table tabs, once parsed: comparable, and at least one
     * side a format Hexana parses (anything but the generic catch-all). Lets a host reserve the
     * tabs' space before they are computed.
     */
    fun expectsTabs(left: BinaryProvider?, right: BinaryProvider?): Boolean =
        areComparable(left, right) && listOfNotNull(left, right).any { it::class != GenericBinaryProvider::class }

    /**
     * Both sides' table tabs, diffed: every flat tab either side's
     * [org.jetbrains.hexana.shared.TableTabsProvider]s produce, paired by name within its
     * provider and ordered by priority, then provider. Tree tabs are left out for now.
     *
     * Blocking between suspensions — each provider parses its side. [between] runs after each
     * provider: the JVM checks for cancellation there, the single-threaded webview lets the
     * browser paint. A provider that fails on one side contributes nothing for that side.
     */
    suspend fun diffTableTabs(
        left: BinaryProvider?,
        right: BinaryProvider?,
        leftName: String?,
        rightName: String?,
        onError: (Throwable) -> Unit = {},
        between: suspend () -> Unit = {},
        onRowClick: (leftOffset: Long?, rightOffset: Long?) -> Unit = { _, _ -> },
    ): List<TableTabDiff> {
        fun tabsOf(index: Int, provider: BinaryProvider?, name: String?): List<TableTabDescriptor> {
            provider ?: return emptyList()
            return try {
                TableTabsProviderRegistry.providers[index].tableTabs(provider, TableTabsContext(fileName = name))
            } catch (e: Exception) {
                onError(e)
                emptyList()
            }
        }
        val result = ArrayList<Triple<TabPriority, Int, TableTabDiff>>()
        for (index in TableTabsProviderRegistry.providers.indices) {
            val leftTabs = tabsOf(index, left, leftName)
            val rightTabs = tabsOf(index, right, rightName)
            val priority = (leftTabs + rightTabs).associate { TableTabDiffer.baseName(it.name) to it.priority }
            TableTabDiffer.diffTabs(leftTabs, rightTabs) { l, r -> onRowClick(l?.offset, r?.offset) }
                .forEach { result += Triple(priority[it.name] ?: TabPriority.Medium, index, it) }
            between()
        }
        return result.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}
