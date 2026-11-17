package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dex.DexClassDirectory
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.zip.ZipEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/** Name of the APK tab that lists the classes of every DEX file in the archive. */
const val APK_CLASSES_TAB_NAME: String = "Classes"

/** Label of the APK Classes-table action that opens a class's DEX with that class selected. */
const val APK_REVEAL_IN_DEX_LABEL: String = "Reveal in DEX"

/**
 * Largest declared uncompressed size of a DEX entry the Classes tab inflates. Real DEX files stay
 * well below it; a larger declaration is treated as unreadable rather than allocated, so a crafted
 * archive cannot exhaust the heap. Entries are read one at a time and dropped once parsed, so a
 * per-entry cap bounds the peak.
 */
const val APK_MAX_DEX_ENTRY_SIZE: Long = 256L * 1024 * 1024

/** Whether [fileName] names an Android package; matches the APK flavour of the Entries tab. */
fun isApkFileName(fileName: String?): Boolean = fileName?.endsWith(".apk", ignoreCase = true) == true

/**
 * One class of an APK: the DEX [entry] it is defined in and its [classDefIndex] there — an
 * index into `DexFile.classDefs`, which is also the row index of that DEX's own Classes tab.
 */
class ApkDexClassRow(
    val entry: ZipEntry,
    val classDefIndex: Int,
    val className: String,
    val accessFlags: String,
    val superclass: String,
)

/** Every class of an APK, plus the DEX entries that could not be read. */
class ApkDexClasses(
    val dexEntries: List<ZipEntry>,
    val rows: List<ApkDexClassRow>,
    val unreadableEntries: List<String>,
)

/**
 * The archive's `.dex` entries in the order the runtime loads them — `classes.dex`,
 * `classes2.dex`, … `classes10.dex` — followed by any other DEX entry by name.
 */
fun apkDexEntries(provider: ZipBinaryProvider): List<ZipEntry> =
    provider.displayEntries
        .filter { it.name.endsWith(".dex", ignoreCase = true) }
        .sortedWith(compareBy<ZipEntry> { multiDexOrdinal(it.name) }.thenBy { it.name })

private val MULTI_DEX_NAME = Regex("classes(\\d*)\\.dex")

private fun multiDexOrdinal(name: String): Int {
    val match = MULTI_DEX_NAME.matchEntire(name) ?: return Int.MAX_VALUE
    val suffix = match.groupValues[1]
    return if (suffix.isEmpty()) 1 else suffix.toIntOrNull() ?: Int.MAX_VALUE
}

/**
 * Reads the classes of every DEX entry in [provider]. [readEntry] inflates one entry — inflation
 * is host-specific, and asynchronous in a browser — and returns `null` when it cannot.
 * [onProgress] is told before each entry is read. An entry declaring more than
 * [APK_MAX_DEX_ENTRY_SIZE] bytes is reported unreadable without being inflated.
 *
 * Decodes every DEX in the archive: on the JVM call it off the UI thread. A browser has only the
 * one thread, so [pause] runs before each entry; there it must let the page paint — a plain
 * coroutine yield does not — or the whole collection shows as a single freeze.
 */
suspend fun collectApkDexClasses(
    provider: ZipBinaryProvider,
    onProgress: ((index: Int, total: Int, entry: ZipEntry) -> Unit)? = null,
    pause: suspend () -> Unit = { yield() },
    readEntry: suspend (ZipEntry) -> CommonByteBuffer?,
): ApkDexClasses {
    val dexEntries = apkDexEntries(provider)
    val rows = mutableListOf<ApkDexClassRow>()
    val unreadable = mutableListOf<String>()
    for ((index, entry) in dexEntries.withIndex()) {
        onProgress?.invoke(index, dexEntries.size, entry)
        pause()
        if (entry.uncompressedSize !in 0..APK_MAX_DEX_ENTRY_SIZE) {
            unreadable += entry.name
            continue
        }
        // Only the class definitions and their type names: a full parse decodes every string,
        // prototype, field and method, several times the work, on a browser's only thread.
        val entryRows = try {
            readEntry(entry)?.let { apkDexClassRows(entry, DexFileParser.parseClassDirectory(it.position(0))) }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
        if (entryRows == null) {
            unreadable += entry.name
            continue
        }
        rows += entryRows
    }
    return ApkDexClasses(dexEntries, rows, unreadable)
}

private fun apkDexClassRows(entry: ZipEntry, dex: DexClassDirectory): List<ApkDexClassRow> =
    dex.classDefs.mapIndexed { index, cd ->
        ApkDexClassRow(
            entry = entry,
            classDefIndex = index,
            className = dex.typeName(cd.classIdx) ?: "<#${cd.classIdx}>",
            accessFlags = renderDexClassAccessFlags(cd.accessFlags),
            superclass = if (cd.superclassIdx == -1) "" else dex.typeName(cd.superclassIdx) ?: "<#${cd.superclassIdx}>",
        )
    }

/**
 * The APK Classes table: one row per class across all DEX files, in load order. With several
 * DEX files, each gets a filter. [onRevealInDex], when given, backs a "Reveal in DEX" row action
 * that each host wires to opening [ApkDexClassRow.entry] with the class selected.
 */
fun apkClassesDescriptor(
    classes: ApkDexClasses,
    onRevealInDex: ((ApkDexClassRow) -> Unit)? = null,
): TableTabDescriptor {
    val rows = classes.rows
    val filters = if (classes.dexEntries.size > 1) {
        classes.dexEntries.map { entry ->
            PredefinedFilter(entry.name) { index -> rows[index].entry.name == entry.name }
        }
    } else emptyList()
    val prologue = classes.unreadableEntries.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "Could not read: ")
    return TableTabDescriptor(
        name = APK_CLASSES_TAB_NAME,
        columns = listOf(
            col("Class", ColumnWidth.Fill()) { CellValue(rows[it].className) },
            col("Access", ColumnWidth.MeasuredMin("public abstract interface")) { CellValue(rows[it].accessFlags) },
            col("Superclass", ColumnWidth.Fill(0.4f)) { CellValue(rows[it].superclass) },
            col("DEX", ColumnWidth.MeasuredMin("classes00.dex")) { CellValue(rows[it].entry.name) },
        ),
        rowCount = rows.size,
        contextMenuItems = if (onRevealInDex != null) {
            listOf(
                RowContextMenuItemDescriptor(
                    labelProvider = { APK_REVEAL_IN_DEX_LABEL },
                    action = { index -> onRevealInDex(rows[index]) },
                ),
            )
        } else emptyList(),
        alwaysShowSearch = true,
        searchPlaceholder = "Search classes",
        emptyMessage = if (classes.dexEntries.isEmpty()) "(no DEX files)" else "(no classes)",
        showIdColumn = false,
        prologueText = prologue,
        predefinedFilters = filters,
        defaultFilterShowAll = true,
    )
}
