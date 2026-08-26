package org.jetbrains.hexana.whatsnew

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay

internal data class HexanaWhatsNewUiModel(
    val version: String,
    val features: List<HexanaWhatsNewFeature>,
)

internal data class HexanaWhatsNewFeature(
    val eyebrow: String,
    val title: String,
    val summary: String,
    val asset: Path?,
    val entries: List<String>,
)

internal sealed interface HexanaWhatsNewVisual {
    data class Static(val bitmap: ImageBitmap) : HexanaWhatsNewVisual

    data class Animated(val frames: List<HexanaWhatsNewAnimationFrame>) : HexanaWhatsNewVisual {
        init {
            require(frames.isNotEmpty()) { "An animated What's New asset needs at least one frame" }
        }
    }
}

internal data class HexanaWhatsNewAnimationFrame(
    val bitmap: ImageBitmap,
    val durationMillis: Int,
)

private const val MAX_ANIMATED_FRAME_COUNT = 120
private const val MAX_ANIMATED_DECODED_BYTES = 48 * 1024 * 1024

private data class WhatsNewSource(
    val version: String = "",
    val clusters: List<WhatsNewCluster> = emptyList(),
)

private data class WhatsNewCluster(
    val eyebrow: String = "",
    val title: String = "",
    val summary: String = "",
    val asset: String? = null,
    val entries: List<WhatsNewEntry> = emptyList(),
)

private data class WhatsNewEntry(
    val text: String = "",
)

internal fun loadWhatsNewUiModel(content: HexanaWhatsNewContent): HexanaWhatsNewUiModel {
    val sourceFile = content.rootDirectory.resolve("source.json")
    val source = Files.newBufferedReader(sourceFile).use { Gson().fromJson(it, WhatsNewSource::class.java) }
    check(source.version == content.resourceVersion) {
        "What's New source version '${source.version}' does not match '${content.resourceVersion}'"
    }
    return HexanaWhatsNewUiModel(
        version = content.version,
        features = source.clusters.map { cluster ->
            HexanaWhatsNewFeature(
                eyebrow = cluster.eyebrow,
                title = cluster.title,
                summary = cluster.summary,
                asset = cluster.asset?.let(content.rootDirectory::resolve),
                entries = cluster.entries.map { plainReleaseNote(it.text) },
            )
        },
    )
}

private fun plainReleaseNote(text: String): String = text
    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
    .replace(Regex("""`([^`]+)`"""), "$1")

private data class WhatsNewColors(
    val background: Color,
    val panel: Color,
    val panelSoft: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
)

@Composable
private fun whatsNewColors(): WhatsNewColors = if (JewelTheme.isDark) {
    WhatsNewColors(
        background = Color(0xFF1E1F22),
        panel = Color(0xFF2B2D31),
        panelSoft = Color(0xFF25272B),
        text = Color(0xFFF2F3F5),
        muted = Color(0xFFB6BCC7),
        accent = Color(0xFF72A7FF),
        border = Color(0xFF44484F),
    )
} else {
    WhatsNewColors(
        background = Color(0xFFF7F8FA),
        panel = Color.White,
        panelSoft = Color(0xFFEEF2F7),
        text = Color(0xFF1F2328),
        muted = Color(0xFF5F6673),
        accent = Color(0xFF1F6FEB),
        border = Color(0xFFD8DEE8),
    )
}

@Composable
internal fun HexanaWhatsNewScreen(
    model: HexanaWhatsNewUiModel,
    onOpenMarketplace: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    val colors = whatsNewColors()
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 1080.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                HeroCard(model.version, colors, onOpenMarketplace, onOpenGitHub)
                model.features.filter { it.asset != null }.forEachIndexed { index, feature ->
                    FeatureCard(feature, imageFirst = index % 2 == 1, colors)
                }
                val compactFeatures = model.features.filter { it.asset == null }
                if (compactFeatures.isNotEmpty()) {
                    AlsoInCard(model.version, compactFeatures, colors)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun HeroCard(
    version: String,
    colors: WhatsNewColors,
    onOpenMarketplace: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    SurfaceCard(colors, Modifier.fillMaxWidth()) {
        Text("HEXANA UPDATE", style = eyebrowStyle(colors))
        Spacer(Modifier.height(10.dp))
        Text(
            "What's New in Hexana $version",
            style = TextStyle(color = colors.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Explore the latest improvements to binary inspection, analysis, and navigation.",
            style = TextStyle(color = colors.muted, fontSize = 17.sp, lineHeight = 25.sp),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinkButton("Open release notes", primary = true, colors, onOpenMarketplace)
            LinkButton("Open GitHub", primary = false, colors, onOpenGitHub)
        }
    }
}

@Composable
private fun FeatureCard(
    feature: HexanaWhatsNewFeature,
    imageFirst: Boolean,
    colors: WhatsNewColors,
) {
    var expanded by remember(feature.title) { mutableStateOf(false) }
    val image = remember(feature.asset) { feature.asset?.let(::loadWhatsNewVisual) }
    SurfaceCard(colors, Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val horizontal = maxWidth >= 760.dp && image != null
            if (horizontal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (imageFirst) FeatureImage(image, feature.title, colors, Modifier.weight(1.1f))
                    FeatureText(feature, expanded, { expanded = !expanded }, colors, Modifier.weight(0.9f))
                    if (!imageFirst) FeatureImage(image, feature.title, colors, Modifier.weight(1.1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    FeatureText(feature, expanded, { expanded = !expanded }, colors)
                    image?.let { FeatureImage(it, feature.title, colors, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun FeatureText(
    feature: HexanaWhatsNewFeature,
    expanded: Boolean,
    onToggle: () -> Unit,
    colors: WhatsNewColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(feature.eyebrow.uppercase(), style = eyebrowStyle(colors))
        Text(
            feature.title,
            style = TextStyle(
                color = colors.text,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Text(feature.summary, style = TextStyle(color = colors.muted, fontSize = 14.sp, lineHeight = 21.sp))
        if (expanded) {
            feature.entries.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = TextStyle(color = colors.accent, fontSize = 14.sp))
                    Text(
                        entry,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp),
                    )
                }
            }
        }
        if (feature.entries.isNotEmpty()) {
            Text(
                if (expanded) "Show less" else "Show details",
                modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 3.dp),
                style = TextStyle(color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun FeatureImage(
    image: HexanaWhatsNewVisual,
    description: String,
    colors: WhatsNewColors,
    modifier: Modifier,
) {
    val bitmap = when (image) {
        is HexanaWhatsNewVisual.Static -> image.bitmap
        is HexanaWhatsNewVisual.Animated -> animatedFrame(image)
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.panelSoft)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun animatedFrame(image: HexanaWhatsNewVisual.Animated): ImageBitmap {
    var frameIndex by remember(image) { mutableStateOf(0) }
    LaunchedEffect(image) {
        while (true) {
            delay(image.frames[frameIndex].durationMillis.toLong())
            frameIndex = (frameIndex + 1) % image.frames.size
        }
    }
    return image.frames[frameIndex].bitmap
}

@Composable
private fun AlsoInCard(version: String, features: List<HexanaWhatsNewFeature>, colors: WhatsNewColors) {
    SurfaceCard(colors, Modifier.fillMaxWidth()) {
        Text(
            "Also in $version",
            style = TextStyle(color = colors.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(18.dp))
        features.forEachIndexed { index, feature ->
            if (index > 0) Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    feature.title,
                    style = TextStyle(color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
                Text(feature.summary, style = TextStyle(color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp))
            }
        }
    }
}

@Composable
private fun SurfaceCard(
    colors: WhatsNewColors,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(30.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

@Composable
private fun LinkButton(
    label: String,
    primary: Boolean,
    colors: WhatsNewColors,
    onClick: () -> Unit,
) {
    val background = if (primary) colors.accent else colors.panelSoft
    val foreground = if (primary) Color.White else colors.text
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .border(1.dp, if (primary) colors.accent else colors.border, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

private fun eyebrowStyle(colors: WhatsNewColors): TextStyle = TextStyle(
    color = colors.accent,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    fontFamily = FontFamily.SansSerif,
    letterSpacing = 0.8.sp,
)

internal fun loadWhatsNewVisual(path: Path): HexanaWhatsNewVisual? = runCatching {
    if (path.fileName.toString().endsWith(".gif", ignoreCase = true)) {
        loadAnimatedGif(path)
    } else {
        HexanaWhatsNewVisual.Static(SkiaImage.makeFromEncoded(Files.readAllBytes(path)).toComposeImageBitmap())
    }
}.getOrNull()

private fun loadAnimatedGif(path: Path): HexanaWhatsNewVisual {
    Data.makeFromBytes(Files.readAllBytes(path)).use { data ->
        Codec.makeFromData(data).use { codec ->
            val frameCount = codec.frameCount
            require(frameCount in 1..MAX_ANIMATED_FRAME_COUNT) {
                "Animated GIF has $frameCount frames; the maximum is $MAX_ANIMATED_FRAME_COUNT"
            }
            val decodedBytes = frameCount.toLong() * codec.imageInfo.width * codec.imageInfo.height * 4
            require(decodedBytes <= MAX_ANIMATED_DECODED_BYTES) {
                "Animated GIF needs $decodedBytes decoded bytes; the maximum is $MAX_ANIMATED_DECODED_BYTES"
            }
            if (frameCount == 1) {
                return HexanaWhatsNewVisual.Static(codec.readPixels().let(SkiaImage::makeFromBitmap).toComposeImageBitmap())
            }

            val framesInfo = codec.framesInfo
            val decodedFrames = mutableMapOf<Int, Bitmap>()
            fun decodeFrame(index: Int): Bitmap {
                decodedFrames[index]?.let { return it }
                val frameInfo = framesInfo[index]
                val bitmap = if (frameInfo.requiredFrame >= 0) {
                    decodeFrame(frameInfo.requiredFrame).makeClone()
                } else {
                    Bitmap().apply {
                        allocPixels(codec.imageInfo)
                        erase(0)
                    }
                }
                codec.readPixels(bitmap, index, frameInfo.requiredFrame)
                return bitmap.also { decodedFrames[index] = it }
            }

            return HexanaWhatsNewVisual.Animated(
                framesInfo.indices.map { index ->
                    val frameInfo = framesInfo[index]
                    HexanaWhatsNewAnimationFrame(
                        bitmap = SkiaImage.makeFromBitmap(decodeFrame(index)).toComposeImageBitmap(),
                        durationMillis = frameInfo.duration.coerceIn(20, 10_000),
                    )
                },
            )
        }
    }
}
