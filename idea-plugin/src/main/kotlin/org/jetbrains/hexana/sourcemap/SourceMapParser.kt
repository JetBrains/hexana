package org.jetbrains.hexana.sourcemap

import com.google.gson.stream.JsonToken
import org.jetbrains.hexana.providers.HexanaSourceLocation
import org.jetbrains.hexana.providers.HexanaSourceLocationProvider
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class SourceMap(
    val sourceMapPath: Path?,
    val sources: List<String>,
    val mappings: List<MappingEntry>
) : HexanaSourceLocationProvider {
    override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? {
        var l = 0
        var r = mappings.lastIndex
        while (l <= r) {
            val mid = l + (r - l) / 2
            val entry = mappings[mid]
            if (entry.byteOffset <= byteOffset) {
                l = mid + 1
            } else {
                r = mid - 1
            }
        }
        return if (l > 0) {
            val entry = mappings[l - 1]
            sources.getOrNull(entry.fileIndex)?.let { path ->
                try {
                    val mappingPath = Path.of(path)
                    val ioPath = if (mappingPath.isAbsolute) {
                        mappingPath
                    } else {
                        sourceMapPath?.resolveSibling(path)
                    }
                    if (ioPath != null) {
                        HexanaSourceLocation(ioPath.toString(), entry.line, entry.column)
                    } else {
                        null
                    }
                } catch (_: InvalidPathException) {
                    null
                }

            }
        } else {
            null
        }
    }

}

data class MappingEntry(
    val byteOffset: Int,
    val fileIndex: Int,
    val line: Int,
    val column: Int,
) {
    internal constructor(fields: Array<Int>) : this(fields[0], fields[1], fields[2], fields[3] /*ignore name for now*/)
}


fun parseSourceMap(sourceMapPath: Path?, sourceMapText: CharSequence): SourceMap {
    var sources = emptyList<String>()
    var mappings = emptyList<MappingEntry>()

    val reader = JsonReaderEx(sourceMapText)
    reader.isLenient = true
    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "sources" -> {
                    sources = readArray(reader)
                }

                "mappings" -> {
                    mappings = readMappings(reader)
                }

                else -> {
                    reader.skipValue()
                }
            }
        }
    }
    reader.close()

    return SourceMap(sourceMapPath, sources, mappings)
}


fun readArray(reader: JsonReaderEx): List<String> {
    val result = arrayListOf<String>()
    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
        reader.beginArray()
        while (reader.peek() != JsonToken.END_ARRAY) {
            result.add(reader.nextString())
        }
        reader.endArray()
    }
    return result
}


fun readMappings(reader: JsonReaderEx): List<MappingEntry> {
    // wasm should have only one line group
    val result = arrayListOf<MappingEntry>()
    val mappingsText = reader.nextCharSequence()
    if (mappingsText.contains(';')) {
        return result
    }

    var mappingsSortedByOffset = true
    val fields = arrayOf(0, 0, 0, 0, 0)
    var segmentStart = 0
    while (segmentStart < mappingsText.length) {
        var segmentEnd = mappingsText.indexOf(',', segmentStart)
        if (segmentEnd == -1) {
            segmentEnd = mappingsText.length
        }

        var fieldIndex = 0
        val charIterator = CharSequenceIterator(mappingsText.subSequence(segmentStart, segmentEnd))
        while (charIterator.hasNext() && fieldIndex < fields.size) {
            val decodedField = Base64VLQ.decode(charIterator)
            if (fieldIndex == 0) {
                mappingsSortedByOffset = mappingsSortedByOffset && decodedField >= 0
            }
            fields[fieldIndex] += decodedField
            fieldIndex++
        }
        result.add(MappingEntry(fields))
        segmentStart = segmentEnd + 1
    }

    if (!mappingsSortedByOffset) {
        result.sortBy { it.byteOffset }
    }

    return result
}


private class CharSequenceIterator(private val content: CharSequence) : Base64VLQ.CharIterator {
    private val length = content.length
    private var current = 0

    override fun next() = content.get(current++)
    override fun hasNext() = current < length
}
