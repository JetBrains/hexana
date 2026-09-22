package org.jetbrains.hexana.run

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * SM test-runner console properties for the Hexana Suite run configuration:
 * wires the `hexana-script://` test locator and the rerun-failed action.
 *
 * For directory configurations the rerun filter is one global path set applied
 * to every suite file, so identically-pathed tests in other files also rerun
 * (the filter is a superset, never a no-op for files not originally failing).
 */
class HexanaSuiteConsoleProperties(
    configuration: HexanaSuiteRunConfiguration,
    executor: Executor,
) : SMTRunnerConsoleProperties(configuration, FRAMEWORK_NAME, executor) {

    override fun getTestLocator(): SMTestLocator = HexanaScriptTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView?): AbstractRerunFailedTestsAction? {
        val container = consoleView as? ComponentContainer ?: return null
        return HexanaSuiteRerunFailedTestsAction(container, this)
    }

    companion object {
        const val FRAMEWORK_NAME = "HexanaSuite"
    }
}

/**
 * Resolves `hexana-script://<absoluteFile>:<line>` locationHint URLs emitted
 * with the suite service messages. The line is split off at the last ':' so
 * absolute paths containing ':' still resolve; navigation targets the PSI
 * element at the start of the 1-based line.
 */
object HexanaScriptTestLocator : SMTestLocator {

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol != HEXANA_SCRIPT_LOCATION_PROTOCOL) return emptyList()
        val lastColon = path.lastIndexOf(':')
        if (lastColon <= 0) return emptyList()
        val filePath = path.substring(0, lastColon)
        val line = path.substring(lastColon + 1).toIntOrNull() ?: return emptyList()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        val element = if (document != null && line in 1..document.lineCount) {
            psiFile.findElementAt(document.getLineStartOffset(line - 1)) ?: psiFile
        } else {
            psiFile
        }
        val location = PsiLocation.fromPsiElement(element) ?: return emptyList()
        return listOf(location)
    }
}

/**
 * Rerun-failed for suite runs: maps every failed test proxy to its
 * describe path (list of describe names + test name, suite file excluded) and
 * runs a configuration clone whose transient [HexanaSuiteRunConfiguration.testPathFilter]
 * carries that set. The engine reports the non-selected tests as SKIP, so the
 * rerun tree keeps the shape of the original run.
 */
class HexanaSuiteRerunFailedTestsAction(
    componentContainer: ComponentContainer,
    properties: HexanaSuiteConsoleProperties,
) : AbstractRerunFailedTestsAction(componentContainer) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? HexanaSuiteRunConfiguration ?: return null
        val failedPaths = getFailedTests(configuration.project)
            .filterIsInstance<SMTestProxy>()
            .filter { !it.isSuite }
            .mapNotNull { describePathForProxy(it) }
            .toSet()
        if (failedPaths.isEmpty()) return null
        val clone = configuration.clone() as HexanaSuiteRunConfiguration
        clone.testPathFilter = failedPaths
        return object : MyRunProfile(clone) {
            override fun getModules(): Array<Module> = Module.EMPTY_ARRAY
            override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState =
                HexanaSuiteCommandState(clone, env)
        }
    }
}

/**
 * Builds the describe path for a failed test proxy by walking its parents up
 * to (and excluding) the invisible SM root, then dropping the file-level suite
 * name via [proxyPathOf]. Proxy names mirror the engine's node names exactly,
 * so the resulting path keys match the engine's test-path filter.
 */
internal fun describePathForProxy(proxy: SMTestProxy): List<String>? {
    val names = mutableListOf<String>()
    var current: SMTestProxy? = proxy
    while (current != null && current.parent != null) {
        names.add(0, current.name)
        current = current.parent as? SMTestProxy
    }
    return proxyPathOf(names)
}
