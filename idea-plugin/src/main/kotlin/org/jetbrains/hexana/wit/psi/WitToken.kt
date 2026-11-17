package org.jetbrains.hexana.wit.psi

import com.intellij.psi.tree.IElementType
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.wit.WitLanguage

class WitElementType(@NonNls debugName: String) : IElementType(debugName, WitLanguage)

class WitToken(val name: String) : IElementType(name, WitLanguage)