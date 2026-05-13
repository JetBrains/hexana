package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.providers.format.WasmChunkTree
import org.jetbrains.hexana.providers.format.WasmSectionKinds
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Horizontal jump bar above the WAT panel. Lists every section in the
 * binary as a clickable label (`type`, `import`, `function`, `code`, ...);
 * clicking jumps the LazyColumn straight to that section's sticky header
 * via [navigator]'s `gotoChunk`. Scrolls horizontally when the section
 * count overflows the panel width (typical for binaries with many custom
 * sections).
 *
 * The bar reads its list from [chunkTree]'s [BinaryChunk.SectionHeader]
 * elements in their on-disk order, so the bar mirrors the panel's vertical
 * layout — no separate ordering logic to keep in sync.
 */
@Composable
internal fun SectionShortcutBar(
    chunkTree: WasmChunkTree,
    navigator: WasmWatNavigator,
) {
    val entries: List<Pair<ChunkId, String>> = remember(chunkTree) {
        val out = ArrayList<Pair<ChunkId, String>>()
        // Same-name custom sections are common (e.g. multiple `name`
        // customs); the chunk tree gives each a distinct ChunkId, so the
        // labels disambiguate by index suffix when needed.
        val nameCounts = HashMap<String, Int>()
        for (i in 0 until chunkTree.totalChunks) {
            val c = chunkTree.chunkAt(i)
            if (c is BinaryChunk.SectionHeader) {
                val base = WasmSectionKinds.displayName(c.kind)
                val count = nameCounts[base] ?: 0
                nameCounts[base] = count + 1
                val label = if (count == 0) base else "$base#$count"
                out += c.id to label
            }
        }
        out
    }
    if (entries.isEmpty()) return

    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((chunkId, label) in entries) {
            Text(
                text = label,
                style = ROW_TEXT_STYLE.copy(
                    color = HexanaTheme.textColor,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.clickable {
                    org.jetbrains.hexana.HexanaLog.LOG.warn(
                        "[wat-nav-debug] section-bar click chunkId=$chunkId label=$label",
                    )
                    navigator.gotoChunk(chunkId)
                },
            )
        }
    }
}
