package org.jetbrains.hexana.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import org.gradle.tooling.model.idea.IdeaProject
import org.jetbrains.hexana.chicory.ChicoryAotConfig
import org.jetbrains.hexana.chicory.ChicoryAotRegistry
import org.jetbrains.hexana.chicory.ConfigSource
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import java.io.File

private val LOG = logger<ChicoryGradleProjectResolver>()

/**
 * Gradle project resolver extension that discovers Chicory AOT compiler-plugin
 * configurations after each Gradle sync and populates [ChicoryAotRegistry].
 *
 * ### Model extraction approach
 *
 * The `chicory-compiler-gradle-plugin` does not expose a custom Gradle tooling
 * model, so there is no standard data class to request via
 * [getExtraProjectModelClasses].  Instead, [resolveFinished] is used: at that
 * point the `IdeaProject` model has been collected and each module's
 * `GradleProject` carries a reference to its `projectDirectory`.  We then
 * open the `build.gradle.kts` or `build.gradle` file under that directory and
 * apply the same lightweight regex/brace-counter scan that was used in the
 * (now-deleted) `ChicoryAotPluginIndex`.
 *
 * The scan tolerates both Kotlin DSL and Groovy DSL variants:
 * ```
 * chicory {
 *     wasms {
 *         register("Name") {
 *             wasmFile.set(file("path/to/foo.wasm"))   // Kotlin
 *             moduleInterface.set("com.example.Foo")    // Kotlin
 *             wasmFile = file("path/to/foo.wasm")       // Groovy
 *             moduleInterface = "com.example.Foo"       // Groovy
 *         }
 *     }
 * }
 * ```
 *
 * Registered in `META-INF/org.jetbrains.hexana.gradle.xml` under the
 * `org.jetbrains.plugins.gradle.projectResolve` extension point.
 */
class ChicoryGradleProjectResolver : AbstractProjectResolverExtension() {

    override fun resolveFinished(projectDataNode: DataNode<ProjectData>) {
        // Delegate to next in chain first, then run our collection.
        super.resolveFinished(projectDataNode)

        val ideProject = resolverCtx.getRootModel(IdeaProject::class.java)
        if (ideProject == null) {
            LOG.debug("ChicoryGradleProjectResolver: no IdeaProject model available")
            return
        }

        val collected = mutableListOf<ChicoryAotConfig>()

        for (module in ideProject.modules) {
            val projectDir: File = try {
                module.gradleProject.projectDirectory
            } catch (e: Exception) {
                LOG.debug("ChicoryGradleProjectResolver: could not get projectDirectory for ${module.name}", e)
                continue
            }

            val buildFile = findBuildFile(projectDir) ?: continue
            val baseDir = VfsUtil.pathToUrl(projectDir.absolutePath)

            try {
                val content = buildFile.readText()
                collected += extractGradleChicoryConfigs(content, baseDir)
            } catch (e: Exception) {
                LOG.warn("ChicoryGradleProjectResolver: failed to read ${buildFile.absolutePath}", e)
            }
        }

        // Dispatch to the registry on a background thread; resolveFinished already
        // runs off the EDT, but posting via invokeLater is not required here.
        val project = resolverCtx.externalSystemTaskId.findProject() ?: run {
            LOG.debug("ChicoryGradleProjectResolver: could not resolve Project from task ID")
            return
        }

        ChicoryAotRegistry.getInstance(project).setConfigs(ConfigSource.GRADLE, collected)
        LOG.debug("ChicoryGradleProjectResolver: registered ${collected.size} Chicory config(s)")
    }

    // -------------------------------------------------------------------------
    // Build-file discovery
    // -------------------------------------------------------------------------

    private fun findBuildFile(projectDir: File): File? {
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val f = File(projectDir, name)
            if (f.isFile) return f
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// Gradle DSL text extractor (identical semantics to the deleted index helper)
// ---------------------------------------------------------------------------

internal fun extractGradleChicoryConfigs(content: String, baseDir: String): List<ChicoryAotConfig> {
    val results = mutableListOf<ChicoryAotConfig>()

    val chicoryBlockStart = findBlockStart(content, "chicory") ?: return results
    val chicoryBlockEnd = findMatchingBrace(content, chicoryBlockStart) ?: return results
    val chicoryBlock = content.substring(chicoryBlockStart, chicoryBlockEnd)

    val wasmsBlockStart = findBlockStart(chicoryBlock, "wasms") ?: return results
    val wasmsBlockEnd = findMatchingBrace(chicoryBlock, wasmsBlockStart) ?: return results
    val wasmsBlock = chicoryBlock.substring(wasmsBlockStart, wasmsBlockEnd)

    for (registerMatch in REGISTER_REGEX.findAll(wasmsBlock)) {
        val blockOpen = registerMatch.range.last
        val blockEnd = findMatchingBrace(wasmsBlock, blockOpen) ?: continue
        val registerBlock = wasmsBlock.substring(blockOpen, blockEnd)

        val wasmFile = WASM_FILE_REGEX.find(registerBlock)?.groupValues?.get(1) ?: continue
        val moduleInterface = MODULE_INTERFACE_REGEX.find(registerBlock)?.groupValues?.get(1) ?: continue
        results += ChicoryAotConfig(wasmFile.trim(), moduleInterface.trim(), baseDir)
    }
    return results
}

private val REGISTER_REGEX = Regex("""register\s*\(\s*"[^"]*"\s*\)\s*\{""")

private val WASM_FILE_REGEX = Regex(
    """wasmFile\s*(?:=\s*file\s*\(\s*"|\.set\s*\(\s*file\s*\(\s*")([^"]+)"""
)

private val MODULE_INTERFACE_REGEX = Regex(
    """moduleInterface\s*(?:=\s*"|\.set\s*\(\s*")([^"]+)"""
)

private fun findBlockStart(text: String, blockName: String): Int? {
    val pattern = Regex("""(?:^|[\s;])${Regex.escape(blockName)}\s*\{""", RegexOption.MULTILINE)
    return pattern.find(text)?.range?.last
}

private fun findMatchingBrace(text: String, openBrace: Int): Int? {
    var depth = 0
    var i = openBrace
    while (i < text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return i }
        }
        i++
    }
    return null
}
