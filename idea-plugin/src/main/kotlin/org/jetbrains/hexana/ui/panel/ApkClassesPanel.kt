package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.decompressZipEntry
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.shared.APK_CLASSES_TAB_NAME
import org.jetbrains.hexana.shared.ApkDexClasses
import org.jetbrains.hexana.shared.apkClassesDescriptor
import org.jetbrains.hexana.shared.collectApkDexClasses
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.LoadingTabContent
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.jewel.ui.component.Text

/**
 * The APK tab listing the classes of every DEX file in the archive.
 *
 * Nothing is read until the tab is first selected — tabs compose only then — and the DEX
 * files are inflated and parsed off the EDT, so the editor opens at the cost of the ZIP
 * central directory alone. [onRevealInDex] opens a class's DEX entry with that class
 * selected on its own Classes tab.
 */
internal fun apkClassesPanels(
    provider: ZipBinaryProvider,
    state: HexViewStateHolder,
    onRevealInDex: ((entry: ZipEntry, classDefIndex: Int) -> Unit)?,
): List<Panel> =
    listOf(Panel(APK_CLASSES_TAB_NAME) { ApkClassesTab(provider, state, onRevealInDex) })

@Composable
private fun ApkClassesTab(
    provider: ZipBinaryProvider,
    state: HexViewStateHolder,
    onRevealInDex: ((entry: ZipEntry, classDefIndex: Int) -> Unit)?,
) {
    var classes by remember(provider) { mutableStateOf<Result<ApkDexClasses>?>(null) }
    var progress by remember(provider) { mutableStateOf<String?>(null) }
    LaunchedEffect(provider) {
        classes = withContext(Dispatchers.IO) {
            try {
                val archive = provider.bytes
                Result.success(
                    collectApkDexClasses(
                        provider,
                        onProgress = { index, total, entry ->
                            progress = "Reading ${entry.name} (${index + 1} of $total)\u2026"
                        },
                    ) { entry ->
                        decompressZipEntry(entry, archive)?.toCommonByteBuffer()
                    },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                HexanaLog.LOG.warn("Failed to read the classes of this APK", e)
                Result.failure(e)
            }
        }
    }

    val result = classes
    if (result == null) {
        // The same spinner-and-label the tab showed while it was a placeholder, so the
        // hand-over to this panel is seamless; the label tracks which DEX is being read.
        LoadingTabContent(progress ?: "Loading classes\u2026")
        return
    }
    val ready = result.getOrElse { failure ->
        Box(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
            Text(
                text = "Failed to read the classes of this APK: ${failure.message ?: failure::class.simpleName}",
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        return
    }

    val table = remember(ready) {
        apkClassesDescriptor(
            classes = ready,
            onRevealInDex = onRevealInDex?.let { reveal -> { row -> reveal(row.entry, row.classDefIndex) } },
        ).toPanel(state)
    }
    table.render()
}
