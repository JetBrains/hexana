package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import kotlin.io.path.exists

internal data class AnimatedGifFrame(
    val image: BufferedImage,
    val durationMillis: Int,
)

internal class AnimatedGifCapture(
    private val output: Path,
) {
    private val frames = mutableListOf<AnimatedGifFrame>()

    fun captureFrame(driver: Driver, screenshotName: String, durationMillis: Int = DEFAULT_FRAME_DURATION_MILLIS) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val image = ImageIO.read(source.toFile())
            ?: error("Driver screenshot is not a readable image: $source")
        addFrame(image, durationMillis)
    }

    fun addFrame(image: BufferedImage, durationMillis: Int = DEFAULT_FRAME_DURATION_MILLIS) {
        require(durationMillis > 0) { "GIF frame duration must be positive: $durationMillis" }
        val normalized = image.toRgbImage()
        val firstFrame = frames.firstOrNull()
        require(firstFrame == null || (normalized.width == firstFrame.image.width && normalized.height == firstFrame.image.height)) {
            "GIF frame size ${normalized.width}x${normalized.height} does not match " +
                "${firstFrame?.image?.width}x${firstFrame?.image?.height}"
        }
        frames += AnimatedGifFrame(normalized, durationMillis)
    }

    fun write() {
        require(frames.isNotEmpty()) { "Cannot write an animated GIF without frames: $output" }
        output.parent?.let(Files::createDirectories)
        writeAnimatedGif(output, frames)
    }

    private companion object {
        const val DEFAULT_FRAME_DURATION_MILLIS = 700
    }
}

internal data class GifCursor(
    val xFraction: Float,
    val yFraction: Float,
)

/** Captures a UI-driver interaction as a short, video-like GIF timeline. */
internal class DriverGifCapture(
    output: Path,
    private val maxWidth: Int = 640,
) {
    private val capture = AnimatedGifCapture(output)
    fun captureFrame(driver: Driver, screenshotName: String, durationMillis: Int, cursor: GifCursor) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val image = ImageIO.read(source.toFile())
            ?: error("Driver screenshot is not a readable image: $source")
        val targetWidth = minOf(image.width, maxWidth)
        val targetHeight = (image.height.toLong() * targetWidth / image.width).toInt()
        val timelineFrame = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB).also { target ->
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(image, 0, 0, target.width, target.height, null)
                drawPointer(graphics, target.width, target.height, cursor)
            } finally {
                graphics.dispose()
            }
        }
        capture.addFrame(timelineFrame, durationMillis)
    }

    fun write() = capture.write()

    private fun drawPointer(graphics: Graphics2D, targetWidth: Int, targetHeight: Int, cursor: GifCursor) {
        val x = (cursor.xFraction.coerceIn(0f, 1f) * targetWidth).toInt()
        val y = (cursor.yFraction.coerceIn(0f, 1f) * targetHeight).toInt()
        val cursorShape = Polygon(intArrayOf(x, x, x + 10, x + 4, x + 1), intArrayOf(y, y + 17, y + 12, y + 10, y + 16), 5)
        graphics.color = java.awt.Color.WHITE
        graphics.fillPolygon(cursorShape)
        graphics.color = java.awt.Color.BLACK
        graphics.drawPolygon(cursorShape)
    }
}

internal fun writeAnimatedGif(output: Path, frames: List<AnimatedGifFrame>) {
    require(frames.isNotEmpty()) { "Cannot write an animated GIF without frames: $output" }
    val writer = ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
        ?: error("No GIF ImageIO writer is available")
    val writeParameters = writer.defaultWriteParam
    ImageIO.createImageOutputStream(output.toFile()).use { stream ->
        writer.output = stream
        try {
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, frame ->
                writer.writeToSequence(
                    IIOImage(frame.image, null, gifMetadata(writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame.image), writeParameters), frame.durationMillis, index == 0)),
                    writeParameters,
                )
            }
            writer.endWriteSequence()
        } finally {
            writer.dispose()
        }
    }
}

private fun gifMetadata(metadata: IIOMetadata, durationMillis: Int, firstFrame: Boolean): IIOMetadata {
    val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
    root.child("GraphicControlExtension").apply {
        setAttribute("disposalMethod", "none")
        setAttribute("userInputFlag", "FALSE")
        setAttribute("transparentColorFlag", "FALSE")
        setAttribute("delayTime", (durationMillis.coerceAtLeast(10) / 10).toString())
        setAttribute("transparentColorIndex", "0")
    }
    if (firstFrame) {
        val applicationExtensions = root.child("ApplicationExtensions")
        val loopExtension = IIOMetadataNode("ApplicationExtension").apply {
            setAttribute("applicationID", "NETSCAPE")
            setAttribute("authenticationCode", "2.0")
            userObject = byteArrayOf(1, 0, 0)
        }
        applicationExtensions.appendChild(loopExtension)
    }
    return metadata.apply { setFromTree(nativeMetadataFormatName, root) }
}

private fun IIOMetadataNode.child(name: String): IIOMetadataNode {
    for (index in 0 until length) {
        val child = item(index) as? IIOMetadataNode
        if (child?.nodeName == name) return child
    }
    return IIOMetadataNode(name).also(::appendChild)
}

private fun BufferedImage.toRgbImage(): BufferedImage {
    if (type == BufferedImage.TYPE_INT_RGB) return this
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { rgb ->
        val graphics: Graphics2D = rgb.createGraphics()
        try {
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }
}
