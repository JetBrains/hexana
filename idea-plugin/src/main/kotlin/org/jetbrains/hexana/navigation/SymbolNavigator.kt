package org.jetbrains.hexana.navigation

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import org.jetbrains.hexana.wasm.analysis.GraalJavaSymbol
import org.jetbrains.hexana.wasm.analysis.KotlinWasmSymbol

/**
 * Plain (no-PSI) descriptor of a JIT inline-frame navigation target. The class FQN is
 * pre-resolved by the caller (via `javaFqn`) so this interface — and therefore the core —
 * stays free of any `com.intellij.java` type.
 */
data class JitInlineRequest(
    val classFqn: String,
    val methodName: String,
    val methodSignature: String,
    val bci: Int,
)

/**
 * Extension-point seam for "navigate from a WASM/JIT symbol to its Java/Kotlin source or
 * bytecode". The implementation lives in the Java-gated module ([org.jetbrains.hexana.javaLang],
 * registered in `org.jetbrains.hexana.java.xml`) because all the resolution it does relies on
 * `com.intellij.java` PSI (`JavaPsiFacade`, `PsiShortNamesCache`, `ClassUtil`).
 *
 * Core code calls [getInstance] and no-ops when the Java plugin is absent (CLion, RustRover,
 * WebStorm, …) — the plugin still loads, the symbols simply aren't clickable there. The
 * signatures intentionally use only core value types ([GraalJavaSymbol], [KotlinWasmSymbol],
 * [JitInlineRequest]) so the EP declaration carries no Java dependency.
 *
 * All three methods are fire-and-forget: they kick off an off-EDT [com.intellij.openapi.application.ReadAction]
 * and open the editor on the UI thread once resolution completes.
 */
interface SymbolNavigator {

    /** Open the bytecode view (or fall back to source) for a GraalVM Web Image symbol. */
    fun navigateToGraalSymbol(project: Project, symbol: GraalJavaSymbol)

    /** Navigate to the Kotlin source declaration for a Kotlin/Wasm symbol. */
    fun navigateToKotlinSymbol(project: Project, symbol: KotlinWasmSymbol)

    /** Navigate to the class/method behind a JIT inline-stack frame. */
    fun navigateToJitInline(project: Project, request: JitInlineRequest)

    companion object {
        val EP: ExtensionPointName<SymbolNavigator> =
            ExtensionPointName.create("org.jetbrains.hexana.symbolNavigator")

        /** The registered navigator, or `null` in an IDE without the Java plugin. */
        fun getInstance(): SymbolNavigator? = EP.extensionList.firstOrNull()
    }
}
