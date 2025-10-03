package org.jetbrains.hexana.wat.psi

import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wat.WatLanguage


class WatToken(debugName: String) : IElementType(debugName, WatLanguage) {
    companion object {
        @JvmField val NUMBER = WatToken("number")
    }
}
