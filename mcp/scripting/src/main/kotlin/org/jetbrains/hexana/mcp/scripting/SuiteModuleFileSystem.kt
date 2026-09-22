package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.io.FileSystem
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute

/**
 * Read-only, rooted [FileSystem] for the suite profile's ESM module loader.
 *
 * Reads are allowed only under the suite root and the optional library root
 * (canonicalized prefix check). Every write operation throws. Relative paths
 * are resolved against the suite root: GraalJS resolves relative imports of a
 * literal main source against the FS working directory, while modules it loaded
 * through this FS resolve against their own (absolute) referrer path.
 *
 * Bare import specifiers arrive at [parsePath] verbatim and are resolved
 * node-style under `<libRoot>/<package>` using `package.json`:
 * `exports` (string, or object with `"."`/subpath keys and `import`/`default`
 * conditions), then the `module` field, then `main` (the latter only when the
 * package declares `"type": "module"` or the entry ends in `.mjs`). A package
 * that resolves only to CommonJS entries is rejected: suite libraries are
 * ESM-only.
 */
internal class SuiteModuleFileSystem(
    roots: List<Path>,
    private val libRoot: Path?,
    private var cwd: Path,
) : FileSystem {

    private val canonicalRoots: List<Path> = roots.map { it.toRealPath() }

    private fun absolute(path: Path): Path =
        if (path.isAbsolute) path.normalize() else cwd.resolve(path).normalize()

    private fun requireInRoots(path: Path): Path {
        val real = try {
            absolute(path).toRealPath()
        } catch (_: Exception) {
            throw AccessDeniedException(path.toString())
        }
        if (canonicalRoots.none { real.startsWith(it) }) {
            throw AccessDeniedException("$path is outside the suite and library roots")
        }
        return real
    }

    override fun parsePath(uri: URI): Path = Path.of(uri)

    override fun parsePath(path: String): Path {
        val looksRelative = path.startsWith("./") || path.startsWith("../")
        if (looksRelative || Path.of(path).isAbsolute) return Path.of(path)
        return resolveBareSpecifier(path)
    }

    override fun checkAccess(path: Path, modes: MutableSet<out AccessMode>, vararg linkOptions: LinkOption) {
        if (AccessMode.WRITE in modes || AccessMode.EXECUTE in modes) {
            throw AccessDeniedException("suite module file system is read-only: $path")
        }
        requireInRoots(path)
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>?) {
        throw SecurityException("suite module file system is read-only")
    }

    override fun delete(path: Path) {
        throw SecurityException("suite module file system is read-only")
    }

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>?,
    ): SeekableByteChannel {
        if (options.any { it != StandardOpenOption.READ }) {
            throw SecurityException("suite module file system is read-only: $path")
        }
        return Files.newByteChannel(requireInRoots(path), StandardOpenOption.READ)
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> =
        Files.newDirectoryStream(requireInRoots(dir), filter)

    override fun toAbsolutePath(path: Path): Path = absolute(path)

    override fun setCurrentWorkingDirectory(currentWorkingDirectory: Path) {
        cwd = currentWorkingDirectory
    }

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path = requireInRoots(path)

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
        Files.readAttributes(requireInRoots(path), attributes, *options)

    // -------------------------------------------------------------------------
    // Node-style bare specifier resolution (ESM only)
    // -------------------------------------------------------------------------

    private fun resolveBareSpecifier(specifier: String): Path {
        val lib = libRoot
            ?: throw IllegalArgumentException(
                "Cannot resolve \"$specifier\": no suite library root configured (pass --lib-root)"
            )
        val segments = specifier.split('/')
        val packageNameSegments = if (specifier.startsWith("@")) 2 else 1
        if (segments.size < packageNameSegments || segments.take(packageNameSegments).any { it.isEmpty() }) {
            throw IllegalArgumentException("Cannot resolve \"$specifier\": malformed package specifier")
        }
        val packageName = segments.take(packageNameSegments).joinToString("/")
        val subpath = segments.drop(packageNameSegments).joinToString("/").ifEmpty { "." }

        val packageDir = lib.resolve(packageName).normalize()
        if (!packageDir.startsWith(lib)) {
            throw IllegalArgumentException("Cannot resolve \"$specifier\": escapes the library root")
        }
        val packageJson = packageDir.resolve("package.json")
        if (!Files.isRegularFile(packageJson)) {
            throw IllegalArgumentException(
                "Cannot resolve \"$specifier\": package \"$packageName\" not found under the suite library root"
            )
        }
        val manifest = MiniJson.parse(Files.readString(packageJson)) as? Map<*, *>
            ?: throw IllegalArgumentException("Cannot resolve \"$specifier\": malformed package.json")

        val entry = resolveEntry(manifest, packageName, subpath)
        val resolved = packageDir.resolve(entry).normalize()
        if (!resolved.startsWith(packageDir)) {
            throw IllegalArgumentException("Cannot resolve \"$specifier\": entry \"$entry\" escapes the package")
        }
        return resolved
    }

    private fun resolveEntry(manifest: Map<*, *>, packageName: String, subpath: String): String {
        val exports = manifest["exports"]
        if (exports != null) return resolveExports(exports, packageName, subpath)

        if (subpath != ".") {
            // Legacy direct-file subpath, e.g. "pkg/util.mjs".
            return subpath
        }
        val moduleField = manifest["module"] as? String
        if (moduleField != null) return moduleField

        val type = manifest["type"] as? String
        val main = (manifest["main"] as? String) ?: "index.js"
        if (type == "module" || main.endsWith(".mjs")) return main
        throw IllegalArgumentException(
            "Cannot import \"$packageName\": suite libraries are ESM-only, " +
                "and this package only provides a CommonJS entry (\"$main\")"
        )
    }

    private fun resolveExports(exports: Any, packageName: String, subpath: String): String {
        val target: Any? = when (exports) {
            is String -> if (subpath == ".") exports else null
            is Map<*, *> -> {
                val keys = exports.keys.filterIsInstance<String>()
                if (keys.any { it.startsWith(".") }) {
                    val key = if (subpath == ".") "." else "./$subpath"
                    exports[key]
                } else {
                    // Bare conditions object applies to the root subpath only.
                    if (subpath == ".") exports else null
                }
            }
            else -> null
        } ?: throw IllegalArgumentException(
            "Cannot import \"$packageName\": package.json exports does not provide subpath \"$subpath\""
        )
        val entry = resolveConditions(target)
            ?: throw IllegalArgumentException(
                "Cannot import \"$packageName\": suite libraries are ESM-only, and its exports " +
                    "for \"$subpath\" provide no \"import\" or \"default\" condition"
            )
        if (entry.endsWith(".cjs")) {
            throw IllegalArgumentException(
                "Cannot import \"$packageName\": suite libraries are ESM-only, " +
                    "and \"$subpath\" resolves to a CommonJS entry (\"$entry\")"
            )
        }
        return entry
    }

    /** Resolves a conditional exports target, preferring "import", then "default". */
    private fun resolveConditions(target: Any?): String? = when (target) {
        is String -> target
        is Map<*, *> -> resolveConditions(target["import"]) ?: resolveConditions(target["default"])
        else -> null
    }
}

/**
 * Minimal JSON parser for `package.json` files. Objects become `Map<String, Any?>`,
 * arrays `List<Any?>`, plus String/Double/Boolean/null. No dependencies, no
 * streaming: package manifests are tiny.
 *
 * The deliberate choice to keep this local avoids adding a serialization library
 * dependency to `mcp/scripting` for the single package.json use case.
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Trailing characters after JSON value" }
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            require(pos < text.length) { "Unexpected end of JSON" }
            return text[pos]
        }

        private fun expect(char: Char) {
            require(!atEnd() && text[pos] == char) { "Expected '$char' at offset $pos" }
            pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                val char = peek()
                pos++
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        val escaped = peek()
                        pos++
                        when (escaped) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(pos + 4 <= text.length) { "Truncated \\u escape" }
                                builder.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape '\\$escaped'")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, pos)) { "Bad literal at offset $pos" }
            pos += literal.length
            return value
        }

        private fun parseNumber(): Double {
            val start = pos
            if (!atEnd() && (text[pos] == '-' || text[pos] == '+')) pos++
            while (!atEnd() && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            return text.substring(start, pos).toDouble()
        }
    }
}
