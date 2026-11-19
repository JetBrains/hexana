package org.jetbrains.hexana.shared.blazor

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.PredefinedFilter
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.TableTabsProvider
import org.jetbrains.hexana.shared.col
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.shared.sizeTreeTab

const val BLAZOR_APP_TAB_NAME: String = "Blazor App"
const val DOTNET_AOT_TAB_NAME: String = ".NET AOT"

/** Files a browser fetches while the app starts; the rest load on demand (or never, for PDBs). */
private val STARTUP_KINDS = setOf(
    BlazorResourceKind.WasmNative,
    BlazorResourceKind.CoreAssembly,
    BlazorResourceKind.Assembly,
    BlazorResourceKind.JsModule,
)

/**
 * The **Blazor App** tab: every file of the Blazor WebAssembly app a `.wasm` in `_framework` belongs to,
 * as its boot manifest lists them, with the size on disk, the precompressed Brotli and gzip copies
 * the server sends, and whether each file still matches the manifest's integrity hash. The host
 * reads the directory ([BlazorBundle.load]) and hands the result over in [TableTabsContext.blazorBundle].
 */
class BlazorBundleTableTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> = emptyList()


    override fun tableTabs(provider: BinaryProvider, context: TableTabsContext): List<TableTabDescriptor> =
        availableTabNames(provider, context).first.mapNotNull { singleTab(it, provider, context) }

    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>> {
        if (provider !is WasmBinaryProvider) return emptyList<String>() to emptyList()
        val names = listOfNotNull(
            DOTNET_AOT_TAB_NAME.takeIf { context.monoAot != null },
            BLAZOR_APP_TAB_NAME.takeIf { context.blazorBundle != null },
        )
        return names to emptyList()
    }

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is WasmBinaryProvider) return null
        return when (name) {
            BLAZOR_APP_TAB_NAME -> context.blazorBundle?.let(::blazorBundleTab)
            DOTNET_AOT_TAB_NAME -> context.monoAot?.let { sizeTreeTab(DOTNET_AOT_TAB_NAME, it.root, monoAotPrologue(it)) }
            else -> null
        }
    }
}

internal fun blazorBundleTab(bundle: BlazorBundle): TableTabDescriptor {
    val rows = bundle.files.sortedWith(
        compareBy<BlazorBundleFile> { it.resource.kind.ordinal }.thenByDescending { it.downloadSize ?: -1L },
    )
    fun size(value: Long?): CellValue<Long> = CellValue(value ?: -1L, value?.let(::formatSizeLong) ?: "")
    return TableTabDescriptor(
        name = BLAZOR_APP_TAB_NAME,
        columns = listOf(
            col("Kind", ColumnWidth.MeasuredMin(BlazorResourceKind.Satellite.label)) { CellValue(rows[it].resource.kind.label) },
            col("Name", ColumnWidth.Fill(0.35f)) {
                val file = rows[it]
                val culture = file.resource.culture?.let { c -> " [$c]" }.orEmpty()
                val marker = if (file.resource.fileName == bundle.currentFileName) "  (this file)" else ""
                CellValue(file.resource.virtualPath + culture + marker)
            },
            col("File", ColumnWidth.Fill(0.35f)) { CellValue(rows[it].resource.fileName) },
            col("Size", ColumnWidth.MeasuredMin("1023.9 KB")) { size(rows[it].size) },
            col("Brotli", ColumnWidth.MeasuredMin("1023.9 KB")) { size(rows[it].brotliSize) },
            col("Gzip", ColumnWidth.MeasuredMin("1023.9 KB")) { size(rows[it].gzipSize) },
            col("Integrity", ColumnWidth.Fill()) {
                val file = rows[it]
                val problem = file.integrity == BlazorIntegrity.Mismatch || file.integrity == BlazorIntegrity.Missing
                CellValue(file.integrity.label, tooltip = file.resource.integrity, tint = if (problem) RowTint.Warning else null)
            },
        ),
        rowCount = rows.size,
        prologueText = blazorBundlePrologue(bundle),
        searchPlaceholder = "Search files",
        predefinedFilters = listOf(
            PredefinedFilter("Assemblies") { rows[it].resource.kind in ASSEMBLY_KINDS },
            PredefinedFilter("Integrity problems") {
                rows[it].integrity == BlazorIntegrity.Mismatch || rows[it].integrity == BlazorIntegrity.Missing
            },
        ),
        defaultFilterShowAll = true,
        rowTintProvider = { index ->
            rows.getOrNull(index)?.integrity?.takeIf { it == BlazorIntegrity.Mismatch || it == BlazorIntegrity.Missing }?.let { RowTint.Warning }
        },
        emptyMessage = "The boot manifest lists no files.",
    )
}

private val ASSEMBLY_KINDS = setOf(
    BlazorResourceKind.CoreAssembly,
    BlazorResourceKind.Assembly,
    BlazorResourceKind.LazyAssembly,
    BlazorResourceKind.Satellite,
)

/** The summary above the table: what the app is, what a first visit downloads, and what is wrong. */
internal fun blazorBundlePrologue(bundle: BlazorBundle): String = buildString {
    val config = bundle.config
    append("Blazor WebAssembly app")
    config.mainAssemblyName?.let { append(" '").append(it).append('\'') }
    append(" — manifest ").append(bundle.configFileName).append(" (").append(config.source.label).append(")\n")

    val startup = bundle.files.filter { it.resource.kind in STARTUP_KINDS }
    val startupDownload = startup.sumOf { it.downloadSize ?: 0L }
    val startupRaw = startup.sumOf { it.size ?: 0L }
    val compression = when {
        startup.all { it.brotliSize != null } -> "Brotli"
        startup.all { it.gzipSize != null } -> "gzip"
        else -> "as available"
    }
    append("Startup download: ").append(formatSizeLong(startupDownload)).append(" (").append(compression).append(") for ")
        .append(startup.size).append(" files, ").append(formatSizeLong(startupRaw)).append(" uncompressed")
    val icu = bundle.files.filter { it.resource.kind == BlazorResourceKind.Icu && it.downloadSize != null }
    if (icu.isNotEmpty()) {
        val smallest = formatSizeLong(icu.minOf { it.downloadSize!! })
        val largest = formatSizeLong(icu.maxOf { it.downloadSize!! })
        append(", plus ICU data: ")
        append(if (config.globalizationMode == "sharded" && icu.size > 1) "one of ${icu.size} shards by culture, $smallest–$largest" else largest)
    }
    append('\n')

    val settings = buildList {
        config.globalizationMode?.let { add("globalization $it") }
        config.linkerEnabled?.let { add(if (it) "trimmed" else "not trimmed") }
        config.debugLevel?.let { add(if (it == 0) "debugging off" else "debug level $it") }
        config.applicationEnvironment?.let { add("environment $it") }
    }
    if (settings.isNotEmpty()) append(settings.joinToString(", ").replaceFirstChar { it.uppercase() }).append('\n')

    val mismatched = bundle.files.filter { it.integrity == BlazorIntegrity.Mismatch }
    if (mismatched.isNotEmpty()) {
        append("⚠ ").append(mismatched.size).append(if (mismatched.size == 1) " file differs" else " files differ")
            .append(" from the manifest's integrity hash; browsers refuse to load ")
            .append(if (mismatched.size == 1) "it" else "them").append(": ")
            .append(mismatched.joinToString { it.resource.fileName }).append('\n')
    }
    val missing = bundle.files.filter { it.integrity == BlazorIntegrity.Missing }
    if (missing.isNotEmpty()) {
        append("⚠ Missing from the directory: ").append(missing.joinToString { it.resource.fileName }).append('\n')
    }
    bundle.current?.let { file ->
        append("This file: ").append(file.resource.virtualPath).append(" (").append(file.resource.kind.label.lowercase()).append(')')
        file.integrity.label.takeIf { it.isNotEmpty() }?.let { append(", integrity ").append(it) }
    }
}.trimEnd()

/** The summary above the .NET AOT tree: how much of the code is .NET, and what the rest is. */
internal fun monoAotPrologue(report: MonoAotReport): String = buildString {
    val root = report.root
    val code = root.size.toLong()
    fun share(size: Int): String = "${formatSizeLong(size.toLong())} (${(size * 1000L / code.coerceAtLeast(1)).let { "${it / 10}.${it % 10}" }}%)"
    append("Ahead-of-time compiled .NET app: ").append(formatSizeLong(code)).append(" of code in a ")
        .append(formatSizeLong(report.moduleSize.toLong())).append(" module\n")
    val groups = root.children.associateBy { it.name }
    val dotnet = listOfNotNull(groups[MonoAotGroups.MANAGED], groups[MonoAotGroups.GENERIC_INSTANCES], groups[MonoAotGroups.WRAPPERS])
    append(dotnet.joinToString(" · ") { "${it.name} ${share(it.size)}" }).append('\n')
    val native = root.children.filter { it !in dotnet }
    if (native.isNotEmpty()) append(native.joinToString(" · ") { "${it.name} ${share(it.size)}" }).append('\n')
    groups[MonoAotGroups.MANAGED]?.children?.take(3)?.takeIf { it.isNotEmpty() }?.let { top ->
        append("Largest: ").append(top.joinToString(", ") { "${it.name} ${formatSizeLong(it.size.toLong())}" }).append('\n')
    }
    if (report.withoutMetadata.isNotEmpty()) {
        append("No metadata for ").append(report.withoutMetadata.joinToString())
            .append(": their code is listed by symbol name only\n")
    }
}.trimEnd()

