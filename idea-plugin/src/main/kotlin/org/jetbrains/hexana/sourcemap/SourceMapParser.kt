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
                }
                catch (_: InvalidPathException) {
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
    internal constructor(fields: Array<Int>) : this(fields[0], fields[1], fields[2], fields[3], /*ignore name for now*/)
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


fun main() {
    val sourceMap = parseSourceMap(null, sourceMapText)
    println(sourceMap.getSourceLocationForOffset(100))
    println(sourceMap.getSourceLocationForOffset(650))
    println(sourceMap.getSourceLocationForOffset(651))
    println(sourceMap.getSourceLocationForOffset(679))
    println(sourceMap.getSourceLocationForOffset(680))
    println(sourceMap.getSourceLocationForOffset(12154))
    println(sourceMap.getSourceLocationForOffset(12155))
}


private class CharSequenceIterator(private val content: CharSequence) : Base64VLQ.CharIterator {
    private val length = content.length
    private var current = 0

    override fun next() = content.get(current++)
    override fun hasNext() = current < length
}


//val sourceMapText ="""
//{"version":3,"sources":["NATIVE_IMPLEMENTATIONS.kt","../hello.kt"],"sourcesContent":[null,null],"ignoreList":[0],"x_google_ignoreList":[0],"names":[],"mappings":"wnyDAAA,6v4MCCU,UAAR,GACD,OAGS,UAAR,GACD,OAGS,KACJ,EAAI,EAAJ,GACF,IAEA,GAEH,GDfD"}
//""".trimIndent()
//    {"version":3,"sources":["NATIVE_IMPLEMENTATIONS.kt","../hello.kt"],"sourcesContent":[null,null],"ignoreList":[0],"x_google_ignoreList":[0],"names":[],"mappings":"+myDAAA,6v4MCCU,UAAR,GACD,OAGS,KACJ,EAAI,EAAJ,GACF,GAEH,GDTD"}

val sourceMapText = """
{"version":3,"sources":["utf8.c","/emsdk/emscripten/system/lib/libc/crt1.c","/emsdk/emscripten/system/lib/libc/musl/src/exit/exit.c","/emsdk/emscripten/system/lib/libc/musl/src/exit/_Exit.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/printf.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/__stdio_write.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/stdout.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/__lockfile.c","/emsdk/emscripten/system/lib/libc/musl/src/errno/__errno_location.c","/emsdk/emscripten/system/lib/libc/emscripten_memcpy_bulkmem.S","/emsdk/emscripten/system/lib/libc/emscripten_memcpy.c","/emsdk/emscripten/system/lib/libc/musl/src/string/memset.c","/emsdk/emscripten/system/lib/pthread/library_pthread_stub.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/ofl.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/__stdio_exit.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/__towrite.c","/emsdk/emscripten/system/lib/libc/musl/src/string/memchr.c","/emsdk/emscripten/system/lib/libc/musl/src/string/strnlen.c","/emsdk/emscripten/system/lib/libc/musl/src/math/frexp.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/fwrite.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/vfprintf.c","/emsdk/emscripten/system/lib/libc/musl/include/math.h","/emsdk/emscripten/system/lib/libc/wasi-helpers.c","/emsdk/emscripten/system/lib/libc/emscripten_syscall_stubs.c","/emsdk/emscripten/system/lib/libc/musl/src/unistd/getpid.c","/emsdk/emscripten/system/lib/pthread/pthread_self_stub.c","/emsdk/emscripten/system/lib/libc/musl/src/multibyte/wcrtomb.c","/emsdk/emscripten/system/lib/libc/musl/src/multibyte/wctomb.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/__stdio_close.c","/emsdk/emscripten/system/lib/libc/musl/src/unistd/lseek.c","/emsdk/emscripten/system/lib/libc/musl/src/stdio/__stdio_seek.c","/emsdk/emscripten/system/lib/compiler-rt/stack_limits.S","/emsdk/emscripten/system/lib/compiler-rt/lib/builtins/ashlti3.c","/emsdk/emscripten/system/lib/compiler-rt/lib/builtins/lshrti3.c","/emsdk/emscripten/system/lib/compiler-rt/lib/builtins/trunctfdf2.c","/emsdk/emscripten/system/lib/compiler-rt/lib/builtins/fp_trunc.h","/emsdk/emscripten/system/lib/compiler-rt/lib/builtins/fp_trunc_impl.inc","/emsdk/emscripten/system/lib/compiler-rt/stack_ops.S","/emsdk/emscripten/system/lib/libc/musl/src/errno/strerror.c"],"sourcesContent":[],"names":[],"mappings":"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"}
"""