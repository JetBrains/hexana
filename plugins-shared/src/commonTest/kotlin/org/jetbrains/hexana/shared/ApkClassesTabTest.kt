package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkClassesTabTest {

    private val dex = buildSampleDex()

    private fun apk(vararg entries: Pair<String, ByteArray>): ZipBinaryProvider =
        ZipBinaryProvider(buildStoredZip(entries.toList()).toCommonByteBuffer())

    /** Stored entries are their own bytes, so a slice of the archive reads them. */
    private fun storedReader(provider: ZipBinaryProvider): (ZipEntry) -> CommonByteBuffer? {
        val archive = provider.bytes
        return { entry ->
            ByteArray(entry.uncompressedSize.toInt()) { archive[entry.dataOffset.toInt() + it] }.toCommonByteBuffer()
        }
    }

    @Test
    fun dexEntriesAreInMultiDexLoadOrder() {
        val provider = apk(
            "classes10.dex" to dex,
            "res/raw/extra.dex" to dex,
            "classes2.dex" to dex,
            "AndroidManifest.xml" to byteArrayOf(0),
            "classes.dex" to dex,
        )

        assertEquals(
            listOf("classes.dex", "classes2.dex", "classes10.dex", "res/raw/extra.dex"),
            apkDexEntries(provider).map { it.name },
        )
    }

    @Test
    fun classesOfEveryDexAreListedWithTheirEntryAndClassDefIndex() {
        val provider = apk("classes.dex" to dex, "classes2.dex" to dex)
        val progress = mutableListOf<String>()
        val classes = collectApkDexClasses(
            provider,
            onProgress = { index, total, entry -> progress += "${index + 1}/$total ${entry.name}" },
            readEntry = storedReader(provider),
        )

        assertEquals(listOf("1/2 classes.dex", "2/2 classes2.dex"), progress)
        assertEquals(listOf("classes.dex", "classes2.dex"), classes.rows.map { it.entry.name })
        assertEquals(listOf(0, 0), classes.rows.map { it.classDefIndex })
        assertTrue(classes.rows.all { it.className == "Lcom/example/Foo;" })
        assertTrue(classes.rows.all { it.superclass == "Ljava/lang/Object;" })
        assertTrue(classes.unreadableEntries.isEmpty())

        val tab = apkClassesDescriptor(classes)
        assertEquals(APK_CLASSES_TAB_NAME, tab.name)
        assertEquals(2, tab.rowCount)
        assertEquals("classes2.dex", tab.columns.single { it.name == "DEX" }.cellProvider(1).representation)
        // One filter per DEX file once there is more than one.
        assertEquals(listOf("classes.dex", "classes2.dex"), tab.predefinedFilters.map { it.label })
        assertTrue(tab.predefinedFilters[1].predicate(1))
        assertFalse(tab.predefinedFilters[1].predicate(0))
    }

    @Test
    fun revealInDexPassesTheRowsEntryAndClassDefIndex() {
        val provider = apk("classes.dex" to dex, "classes2.dex" to dex)
        val classes = collectApkDexClasses(provider, readEntry = storedReader(provider))
        val revealed = mutableListOf<Pair<String, Int>>()
        val tab = apkClassesDescriptor(classes) { row -> revealed += row.entry.name to row.classDefIndex }

        val item = tab.contextMenuItems.single()
        assertEquals(APK_REVEAL_IN_DEX_LABEL, item.labelProvider(1))
        item.action(1)

        assertEquals(listOf("classes2.dex" to 0), revealed)
    }

    @Test
    fun unreadableDexEntriesAreReportedAndSkipped() {
        val provider = apk("classes.dex" to dex, "classes2.dex" to byteArrayOf(1, 2, 3))
        val reader = storedReader(provider)
        val classes = collectApkDexClasses(provider) { entry ->
            if (entry.name == "classes2.dex") null else reader(entry)
        }

        assertEquals(1, classes.rows.size)
        assertEquals(listOf("classes2.dex"), classes.unreadableEntries)
        assertEquals("Could not read: classes2.dex", apkClassesDescriptor(classes).prologueText)
    }

    @Test
    fun anApkWithoutDexFilesHasAnEmptyTableWithoutActions() {
        val provider = apk("AndroidManifest.xml" to byteArrayOf(0))
        val tab = apkClassesDescriptor(collectApkDexClasses(provider, readEntry = storedReader(provider)))

        assertEquals(0, tab.rowCount)
        assertEquals("(no DEX files)", tab.emptyMessage)
        assertTrue(tab.contextMenuItems.isEmpty())
        assertNull(tab.prologueText)
    }

    @Test
    fun onlyApkFileNamesQualify() {
        assertTrue(isApkFileName("app-release.APK"))
        assertFalse(isApkFileName("lib.jar"))
        assertFalse(isApkFileName(null))
    }
}

/** A minimal ZIP whose entries are all stored (method 0); CRCs are left zero. */
private fun buildStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = mutableListOf<Byte>()
    fun u2(v: Int) { out += (v and 0xFF).toByte(); out += ((v shr 8) and 0xFF).toByte() }
    fun u4(v: Int) { u2(v and 0xFFFF); u2((v ushr 16) and 0xFFFF) }

    val localOffsets = mutableListOf<Int>()
    for ((name, data) in entries) {
        localOffsets += out.size
        val nameBytes = name.encodeToByteArray()
        u4(0x04034b50); u2(20); u2(0); u2(0); u2(0); u2(0); u4(0)
        u4(data.size); u4(data.size); u2(nameBytes.size); u2(0)
        out += nameBytes.toList(); out += data.toList()
    }
    val cdOffset = out.size
    entries.forEachIndexed { i, (name, data) ->
        val nameBytes = name.encodeToByteArray()
        u4(0x02014b50); u2(20); u2(20); u2(0); u2(0); u2(0); u2(0); u4(0)
        u4(data.size); u4(data.size); u2(nameBytes.size); u2(0); u2(0); u2(0); u2(0); u4(0)
        u4(localOffsets[i])
        out += nameBytes.toList()
    }
    val cdSize = out.size - cdOffset
    u4(0x06054b50); u2(0); u2(0); u2(entries.size); u2(entries.size); u4(cdSize); u4(cdOffset); u2(0)
    return out.toByteArray()
}
