package org.jetbrains.hexana

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE: @NonNls String = "messages.HexanaBundle"

object HexanaBundle : DynamicBundle(BUNDLE) {
    @JvmStatic
    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): @Nls String {
        return getMessage(key, *params)
    }
}
