package org.jetbrains.hexana.wit.psi

import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.psi.tree.TokenSet

class WitParserUtil: GeneratedParserUtilBase() {
    companion object {
        @JvmStatic
        fun white_space(builder: PsiBuilder, level: Int): Boolean {
            return builder.eof() || TokenSet.WHITE_SPACE.contains(builder.rawLookup(-1))
        }
    }
}