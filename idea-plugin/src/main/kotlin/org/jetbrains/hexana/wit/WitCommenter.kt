package org.jetbrains.hexana.wit

class WitCommenter: com.intellij.lang.Commenter {
    override fun getLineCommentPrefix(): String {
        return "// "
    }

    override fun getBlockCommentPrefix(): String {
        return "/* "
    }

    override fun getBlockCommentSuffix(): String {
        return " */"
    }

    override fun getCommentedBlockCommentPrefix(): String? {
        return null
    }

    override fun getCommentedBlockCommentSuffix(): String? {
        return null
    }
}