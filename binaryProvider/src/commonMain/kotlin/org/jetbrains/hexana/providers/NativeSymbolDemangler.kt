package org.jetbrains.hexana.providers

object NativeSymbolDemangler {
    fun demangle(name: String): String? {
        if (!name.startsWith("_Z")) return null
        val parser = ItaniumParser(name, index = 2)
        return parser.parse()?.takeIf { it != name }
    }
}

private class ItaniumParser(
    private val text: String,
    private var index: Int,
) {
    fun parse(): String? {
        val qualifiedName = parseName() ?: return null
        val params = mutableListOf<String>()
        while (index < text.length) {
            params.add(parseType() ?: return null)
        }
        return if (params.singleOrNull() == "void") {
            "$qualifiedName()"
        } else {
            "$qualifiedName(${params.joinToString(", ")})"
        }
    }

    private fun parseName(): String? {
        return if (peek() == 'N') {
            index++
            val parts = mutableListOf<String>()
            while (index < text.length && peek() != 'E') {
                parts.add(parseLengthPrefixedName() ?: return null)
            }
            if (peek() != 'E') return null
            index++
            parts.takeIf { it.isNotEmpty() }?.joinToString("::")
        } else {
            parseLengthPrefixedName()
        }
    }

    private fun parseLengthPrefixedName(): String? {
        val lengthStart = index
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        if (lengthStart == index) return null
        val length = text.substring(lengthStart, index).toIntOrNull() ?: return null
        if (length <= 0 || index + length > text.length) return null
        return text.substring(index, index + length).also {
            index += length
        }
    }

    private fun parseType(): String? =
        when (val code = take()) {
            'v' -> "void"
            'b' -> "bool"
            'c' -> "char"
            'a' -> "signed char"
            'h' -> "unsigned char"
            's' -> "short"
            't' -> "unsigned short"
            'i' -> "int"
            'j' -> "unsigned int"
            'l' -> "long"
            'm' -> "unsigned long"
            'x' -> "long long"
            'y' -> "unsigned long long"
            'f' -> "float"
            'd' -> "double"
            'P' -> parseType()?.let { "$it*" }
            'R' -> parseType()?.let { "$it&" }
            'K' -> parseType()?.let { "const $it" }
            else -> code?.let { null }
        }

    private fun peek(): Char? = text.getOrNull(index)

    private fun take(): Char? = text.getOrNull(index)?.also { index++ }
}
