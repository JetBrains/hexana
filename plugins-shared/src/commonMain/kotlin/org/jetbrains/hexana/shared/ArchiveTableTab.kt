package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.ArchiveMemberClassification
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.classifyArchiveMember
import org.jetbrains.hexana.providers.pe.PeArchiveMember

/**
 * Lightweight row holder for a single archive member, carrying the parsed
 * member and its lazily classified format. Platform code maps the provider's
 * member list into this before calling [archiveMembersDescriptor].
 */
class ArchiveMemberRow(
    val member: PeArchiveMember,
    val classification: ArchiveMemberClassification,
)

/**
 * Builds a [TableTabDescriptor] for a list of archive members.
 *
 * The descriptor is platform-agnostic and can be rendered by both the
 * IntelliJ and VS Code UI layers.
 *
 * @param rows the member rows to display
 * @param onRowClick optional callback invoked when a row is clicked
 * @param searchPlaceholder placeholder text for the search bar
 * @param emptyMessage message shown when [rows] is empty
 */
fun archiveMembersDescriptor(
    rows: List<ArchiveMemberRow>,
    onRowClick: ((index: Int) -> Unit)? = null,
    searchPlaceholder: String = "Search members\u2026",
    emptyMessage: String = "(no object members)",
): TableTabDescriptor = TableTabDescriptor(
    name = "Members",
    columns = listOf(
        ColumnDescriptor(
            name = "Member",
            widthBehavior = ColumnWidth.Fill(),
        ) { index ->
            val name = rows[index].member.name
            CellValue(name, tooltip = name.takeIf { it.isNotEmpty() })
        },
        ColumnDescriptor(
            name = "Format",
            widthBehavior = ColumnWidth.Measured("Short import___"),
        ) { index ->
            CellValue(rows[index].classification.displayName)
        },
        ColumnDescriptor(
            name = "Size",
            widthBehavior = ColumnWidth.Measured("00,000,000 bytes"),
        ) { index ->
            CellValue(rows[index].member.size.toLong(), formatSizeLong(rows[index].member.size.toLong()))
        },
    ),
    rowCount = rows.size,
    defaultSortColumn = null,
    rowClickAction = onRowClick,
    alwaysShowSearch = true,
    searchPlaceholder = searchPlaceholder,
    emptyMessage = emptyMessage,
    showIdColumn = false,
)

/**
 * [TableTabsProvider] for static-library archives (`.a` / `.lib`).
 *
 * Produces a single "Members" table tab listing all non-bookkeeping members
 * when the provider is an [ArchiveBinaryProvider]. Classification of each
 * member is done eagerly (header-only, no body read) so the shared descriptor
 * has all the data it needs.
 */
class ArchiveTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is ArchiveBinaryProvider) return emptyList()
        val members = provider.members.filterNot { it.isBookkeeping }
        if (members.isEmpty()) return listOf(
            archiveMembersDescriptor(rows = emptyList())
        )

        val rows = members.map { member ->
            ArchiveMemberRow(
                member = member,
                classification = classifyArchiveMember(provider.bytes, member.contentRange),
            )
        }
        return listOf(archiveMembersDescriptor(rows = rows))
    }
}
