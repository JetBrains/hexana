package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.ui.CLASS_FILE_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.ClassFileNavTarget
import org.jetbrains.hexana.wasm.analysis.GraalJavaSymbol

/**
 * Opens the Hexana bytecode view (or falls back to PSI source navigation) for a
 * [GraalJavaSymbol] resolved from a GraalVM Web Image WASM function name.
 *
 * Because the symbol carries only a REDUCED (package-less) class name, resolution
 * uses [PsiShortNamesCache] rather than [com.intellij.psi.JavaPsiFacade.findClasses].
 * All index work runs off the EDT in a non-blocking read action; the editor is
 * opened on the UI thread.
 */
object GraalSymbolNavigator {

    /**
     * Resolve [symbol] to a JVM class file or PSI source element and open the
     * appropriate view. Navigation is asynchronous: the method returns immediately
     * and the editor opens once the background resolution completes.
     *
     * When the class cannot be found at all, a warning is emitted to the Hexana
     * log so the caller gets some indication of failure without blocking the UI.
     */
    fun navigateToBytecode(
        project: Project,
        symbol: GraalJavaSymbol,
    ) {
        ReadAction
            .nonBlocking<Resolution?> { resolve(project, symbol) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { resolution ->
                when (resolution) {
                    is Resolution.Bytecode -> openBytecodeView(project, resolution.vf, resolution.methodName, resolution.descriptor)
                    is Resolution.Source -> resolution.nav.navigate(true)
                    null -> HexanaLog.LOG.warn(
                        "GraalSymbolNavigator: could not resolve '${symbol.display()}' in project '${project.name}'"
                    )
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Off-EDT resolution step. Runs entirely inside the read action established
     * by [ReadAction.nonBlocking]. Returns a [Resolution] describing how to open
     * the target, or `null` if nothing could be found.
     */
    private fun resolve(project: Project, symbol: GraalJavaSymbol): Resolution? {
        val scope = GlobalSearchScope.allScope(project)
        val candidates = PsiShortNamesCache.getInstance(project).getClassesByName(symbol.simpleClassName, scope)
        if (candidates.isEmpty()) return null

        // Prefer the candidate whose qualifiedName exactly matches the dotted form of className
        // (e.g. "JavacCompilerWrapper$Result" → "JavacCompilerWrapper.Result").
        val dotted = symbol.className.replace('$', '.')
        val ordered = buildList {
            candidates.firstOrNull { cls ->
                val qn = cls.qualifiedName ?: return@firstOrNull false
                qn == dotted || qn.endsWith(".$dotted")
            }?.let { add(it) }
            candidates.filterTo(this) { cls ->
                val qn = cls.qualifiedName ?: return@filterTo false
                qn != dotted && !qn.endsWith(".$dotted")
            }
        }

        for (cls in ordered) {
            val methods = cls.findMethodsByName(symbol.methodName, /* checkBases = */ true)
            val method = methods.firstOrNull { it.parameterList.parametersCount == symbol.parameterTypes.size }
                ?: methods.firstOrNull()
                ?: continue

            val file = cls.containingFile
            if (file is PsiCompiledFile) {
                val vf = file.virtualFile ?: continue
                val descriptor = runCatching {
                    com.intellij.psi.util.ClassUtil.getAsmMethodSignature(method)
                }.getOrDefault("")
                return Resolution.Bytecode(vf = vf, methodName = symbol.methodName, descriptor = descriptor)
            } else {
                val nav = (method as? Navigatable) ?: (cls as? Navigatable)
                if (nav != null) return Resolution.Source(nav)
            }
        }

        // No candidate yielded a matching method — fall back to navigating to the first class.
        val fallbackNav = ordered.firstOrNull()?.let { it as? Navigatable }
        return fallbackNav?.let { Resolution.Source(it) }
    }

    // -------------------------------------------------------------------------
    // Opening the bytecode view
    // -------------------------------------------------------------------------

    /**
     * Open [vf] in the Hexana bytecode view and ask it to scroll to the given
     * method. If the editor is already open, routes the request through its
     * [HexanaFileEditor.requestClassFileNav] flow so the tab reacts even when
     * [FileEditorManager.openFile] is a no-op.
     */
    private fun openBytecodeView(project: Project, vf: VirtualFile, methodName: String, descriptor: String) {
        val target = ClassFileNavTarget(
            methodName = methodName,
            methodDescriptor = descriptor,
            bci = null,
        )
        val mgr = FileEditorManager.getInstance(project)
        val existing = mgr.getEditors(vf).filterIsInstance<HexanaFileEditor>().firstOrNull()
        if (existing != null) {
            existing.requestClassFileNav(target)
        } else {
            vf.putUserData(CLASS_FILE_NAV_TARGET_KEY, target)
        }
        mgr.openFile(vf, /* focusEditor = */ true)
        mgr.setSelectedEditor(vf, HexanaFileEditorProvider.EDITOR_TYPE_ID)
    }

    // -------------------------------------------------------------------------
    // Resolution model
    // -------------------------------------------------------------------------

    private sealed class Resolution {
        class Bytecode(val vf: VirtualFile, val methodName: String, val descriptor: String) : Resolution()
        class Source(val nav: Navigatable) : Resolution()
    }
}
