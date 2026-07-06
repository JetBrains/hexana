package org.jetbrains.hexana.mcp.tool.runtime

sealed interface RenderedToolOutput {
    val contentType: String

    data class Text(
        val text: String,
        override val contentType: String = UTF8_TEXT_CONTENT_TYPE,
    ) : RenderedToolOutput {
        init {
            requireValidContentType(contentType)
            require(contentType.declaresUtf8Charset()) {
                "Text output contentType must declare UTF-8 charset."
            }
        }
    }

    class Binary(
        bytes: ByteArray,
        override val contentType: String,
    ) : RenderedToolOutput {
        private val bytesCopy = bytes.copyOf()

        init {
            requireValidContentType(contentType)
        }

        fun bytes(): ByteArray = bytesCopy.copyOf()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false

            return contentType == other.contentType && bytesCopy.contentEquals(other.bytesCopy)
        }

        override fun hashCode(): Int {
            var result = bytesCopy.contentHashCode()
            result = 31 * result + contentType.hashCode()
            return result
        }

        override fun toString(): String = "Binary(contentType=$contentType, bytes=${bytesCopy.size})"
    }

    companion object {
        const val UTF8_TEXT_CONTENT_TYPE: String = "text/plain; charset=utf-8"
        private val UTF8_CHARSET_PARAMETER = Regex(
            pattern = """(?:^|;)\s*charset\s*=\s*"?utf-8"?\s*(?:;|$)""",
            option = RegexOption.IGNORE_CASE
        )

        private fun requireValidContentType(contentType: String) {
            require(contentType.isNotBlank()) { "contentType must not be blank." }
            require('/' in contentType.substringBefore(';')) {
                "contentType must include a media type."
            }
        }

        private fun String.declaresUtf8Charset(): Boolean = UTF8_CHARSET_PARAMETER.containsMatchIn(this)
    }
}
