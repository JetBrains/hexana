package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.wasm.analysis.KotlinWasmSymbol

/**
 * Navigates to the Kotlin source declaration for a [KotlinWasmSymbol] resolved from a Kotlin/Wasm
 * WASM function name.
 *
 * Because Kotlin/Wasm names are plain FQNs (not mangled bytecode), resolution is simpler than the
 * GraalVM Web Image path: there is no bytecode view, only a jump to `.kt` source. The full FQN is
 * resolved through [JavaPsiFacade.findClass], which sees Kotlin classes and file-facade classes via
 * their light-class facades — so no explicit dependency on the Kotlin plugin is required. Top-level
 * declarations (whose facade segment the backend drops) fall back to a short-name method search
 * filtered by package. All index work runs off the EDT; navigation happens on the UI thread.
 *
 * @see KotlinWasmSymbol
 * @see GraalSymbolNavigator for the analogous Web Image (bytecode) navigator.
 */
object KotlinSourceNavigator {

    /**
     * Resolve [symbol] to a Kotlin source element and navigate to it. Asynchronous: returns
     * immediately and opens the source once background resolution completes. A failure to resolve
     * is logged rather than surfaced, mirroring [GraalSymbolNavigator].
     */
    fun navigateToSource(project: Project, symbol: KotlinWasmSymbol) {
        ReadAction
            .nonBlocking<Navigatable?> { resolve(project, symbol) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { nav ->
                if (nav != null) {
                    nav.navigate(true)
                } else {
                    HexanaLog.LOG.warn(
                        "KotlinSourceNavigator: could not resolve '${symbol.display()}' in project '${project.name}'"
                    )
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Off-EDT resolution. Tries the qualified class + member first; for a top-level declaration the
     * backend has dropped the `<File>Kt` facade segment, so we search methods by name and match the
     * package instead. Returns `null` if nothing resolves.
     */
    private fun resolve(project: Project, symbol: KotlinWasmSymbol): Navigatable? {
        val scope = GlobalSearchScope.allScope(project)

        symbol.classFqn?.let { fqn ->
            val cls = JavaPsiFacade.getInstance(project).findClass(fqn, scope)
                ?: symbol.simpleClassName?.let { simple ->
                    PsiShortNamesCache.getInstance(project).getClassesByName(simple, scope)
                        .firstOrNull { it.qualifiedName == fqn || it.qualifiedName?.endsWith(".$fqn") == true }
                }
            if (cls != null) {
                val member = symbol.member
                if (member != null && member != "<init>") {
                    cls.findMethodsByName(member, /* checkBases = */ true).firstOrNull()?.let { m ->
                        return (m as? Navigatable) ?: (cls as? Navigatable)
                    }
                }
                return cls as? Navigatable
            }
        }

        // Top-level function/property: lowered to a static method on a file facade whose name the
        // wasm symbol omits. Search by member name and prefer a match in the recorded package.
        val member = symbol.member ?: return null
        val byName = PsiShortNamesCache.getInstance(project).getMethodsByName(member, scope)
        if (byName.isEmpty()) return null
        val pkg = symbol.packageFqn
        val match = byName.firstOrNull { m ->
            val qn = m.containingClass?.qualifiedName ?: return@firstOrNull false
            pkg == null || qn == pkg || qn.startsWith("$pkg.")
        } ?: byName.first()
        return match as? Navigatable
    }
}
