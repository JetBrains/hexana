package org.jetbrains.hexana.javaLang

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.ClassUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.navigation.JitInlineRequest
import org.jetbrains.hexana.navigation.SymbolNavigator
import org.jetbrains.hexana.ui.CLASS_FILE_NAV_TARGET_KEY
import org.jetbrains.hexana.ui.ClassFileNavTarget
import org.jetbrains.hexana.wasm.analysis.GraalJavaSymbol
import org.jetbrains.hexana.wasm.analysis.KotlinWasmSymbol

/**
 * Java-plugin-backed implementation of [SymbolNavigator]. Registered in
 * `org.jetbrains.hexana.java.xml`, so it only loads where `com.intellij.modules.java` is
 * present. It consolidates the three "symbol → source/bytecode" navigators that previously
 * lived in core (`GraalSymbolNavigator`, `KotlinSourceNavigator`, and the JIT inline-frame
 * navigation in `JitFileEditorView`); core now reaches them through the extension point so it
 * carries no `com.intellij.java` compile dependency.
 *
 * All resolution runs off the EDT inside a non-blocking read action; editors open on the UI thread.
 */
class JavaSymbolNavigator : SymbolNavigator {

    // -------------------------------------------------------------------------
    // GraalVM Web Image: bytecode view (or source fallback)
    // -------------------------------------------------------------------------

    override fun navigateToGraalSymbol(project: Project, symbol: GraalJavaSymbol) {
        ReadAction
            .nonBlocking<GraalResolution?> { resolveGraal(project, symbol) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { resolution ->
                when (resolution) {
                    is GraalResolution.Bytecode ->
                        openBytecodeView(project, resolution.vf, resolution.methodName, resolution.descriptor, bci = null)
                    is GraalResolution.Source -> resolution.nav.navigate(true)
                    null -> HexanaLog.LOG.warn(
                        "JavaSymbolNavigator: could not resolve '${symbol.display()}' in project '${project.name}'"
                    )
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Off-EDT resolution. The symbol carries only a package-less class name, so use short-name lookup. */
    private fun resolveGraal(project: Project, symbol: GraalJavaSymbol): GraalResolution? {
        val scope = GlobalSearchScope.allScope(project)
        val candidates = PsiShortNamesCache.getInstance(project).getClassesByName(symbol.simpleClassName, scope)
        if (candidates.isEmpty()) return null

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
                val descriptor = runCatching { ClassUtil.getAsmMethodSignature(method) }.getOrDefault("")
                return GraalResolution.Bytecode(vf = vf, methodName = symbol.methodName, descriptor = descriptor)
            } else {
                val nav = (method as? Navigatable) ?: (cls as? Navigatable)
                if (nav != null) return GraalResolution.Source(nav)
            }
        }

        val fallbackNav = ordered.firstOrNull()?.let { it as? Navigatable }
        return fallbackNav?.let { GraalResolution.Source(it) }
    }

    private sealed class GraalResolution {
        class Bytecode(val vf: VirtualFile, val methodName: String, val descriptor: String) : GraalResolution()
        class Source(val nav: Navigatable) : GraalResolution()
    }

    // -------------------------------------------------------------------------
    // Kotlin/Wasm: source navigation
    // -------------------------------------------------------------------------

    override fun navigateToKotlinSymbol(project: Project, symbol: KotlinWasmSymbol) {
        ReadAction
            .nonBlocking<Navigatable?> { resolveKotlin(project, symbol) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { nav ->
                if (nav != null) {
                    nav.navigate(true)
                } else {
                    HexanaLog.LOG.warn(
                        "JavaSymbolNavigator: could not resolve '${symbol.display()}' in project '${project.name}'"
                    )
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun resolveKotlin(project: Project, symbol: KotlinWasmSymbol): Navigatable? {
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

    // -------------------------------------------------------------------------
    // JIT inline frame: bytecode view (or source fallback)
    // -------------------------------------------------------------------------

    override fun navigateToJitInline(project: Project, request: JitInlineRequest) {
        ReadAction
            .nonBlocking<JitInlineTarget?> { resolveJitInline(project, request) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { target ->
                when (target) {
                    is JitInlineTarget.Bytecode ->
                        openBytecodeView(project, target.classFile, request.methodName, request.methodSignature, request.bci)
                    is JitInlineTarget.Source -> target.navigatable.navigate(true)
                    null -> Unit
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun resolveJitInline(project: Project, request: JitInlineRequest): JitInlineTarget? {
        val scope = GlobalSearchScope.allScope(project)
        val classes = JavaPsiFacade.getInstance(project).findClasses(request.classFqn, scope)
        if (classes.isEmpty()) return null

        val compiled = classes.firstOrNull { it.containingFile is PsiCompiledFile }
        if (compiled != null) {
            val vf = compiled.containingFile?.virtualFile
            if (vf != null) return JitInlineTarget.Bytecode(vf)
        }

        val cls = classes.first()
        val matched = cls.findMethodsByName(request.methodName, true).firstOrNull { method ->
            runCatching { ClassUtil.getAsmMethodSignature(method) == request.methodSignature }.getOrDefault(false)
        } ?: cls.findMethodsByName(request.methodName, true).firstOrNull()
        val nav = (matched as? Navigatable) ?: (cls as? Navigatable)
        return nav?.let { JitInlineTarget.Source(it) }
    }

    private sealed interface JitInlineTarget {
        data class Bytecode(val classFile: VirtualFile) : JitInlineTarget
        data class Source(val navigatable: Navigatable) : JitInlineTarget
    }

    // -------------------------------------------------------------------------
    // Shared: open Hexana's bytecode view and scroll to a method (+ optional BCI)
    // -------------------------------------------------------------------------

    private fun openBytecodeView(
        project: Project,
        vf: VirtualFile,
        methodName: String,
        descriptor: String,
        bci: Int?,
    ) {
        val target = ClassFileNavTarget(methodName = methodName, methodDescriptor = descriptor, bci = bci)
        val mgr = FileEditorManager.getInstance(project)
        val existing = mgr.getEditors(vf).filterIsInstance<HexanaFileEditor>().firstOrNull()
        if (existing != null) {
            existing.requestClassFileNav(target)
        } else {
            vf.putUserData(CLASS_FILE_NAV_TARGET_KEY, target)
        }
        mgr.openFile(vf, /* focusEditor = */ true)
        // Force Hexana's tab to the front; otherwise the platform restores the last-selected
        // editor (often the decompiler) for the file.
        mgr.setSelectedEditor(vf, HexanaFileEditorProvider.EDITOR_TYPE_ID)
    }
}
