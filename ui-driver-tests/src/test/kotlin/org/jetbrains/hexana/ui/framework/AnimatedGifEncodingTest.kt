package org.jetbrains.hexana.ui.framework

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.io.path.deleteIfExists

class AnimatedGifEncodingTest {
    @Test
    fun writesLoopingGifWithEveryCapturedFrame() {
        val output = Files.createTempFile("hexana-whats-new", ".gif")
        try {
            AnimatedGifCapture(output).apply {
                addFrame(frame(Color(0x35, 0x73, 0xF0)), durationMillis = 250)
                addFrame(frame(Color(0x78, 0xC2, 0x57)), durationMillis = 500)
                write()
            }

            requireNotNull(ImageIO.createImageInputStream(output.toFile())).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().first()
                try {
                    reader.input = input
                    assertEquals(2, reader.getNumImages(true))
                    assertEquals(4, reader.getWidth(0))
                    assertEquals(3, reader.getHeight(0))
                    assertEquals("25", graphicControlExtension(reader.getImageMetadata(0)).getAttribute("delayTime"))
                    assertEquals("50", graphicControlExtension(reader.getImageMetadata(1)).getAttribute("delayTime"))
                } finally {
                    reader.dispose()
                }
            }
            assertTrue(Files.size(output) > 0)
        } finally {
            output.deleteIfExists()
        }
    }

    private fun frame(color: Color): BufferedImage = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB).also { image ->
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
    }

    private fun graphicControlExtension(metadata: javax.imageio.metadata.IIOMetadata): IIOMetadataNode {
        val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
        return (0 until root.length)
            .map { root.item(it) as IIOMetadataNode }
            .first { it.nodeName == "GraphicControlExtension" }
    }
}
