package org.jetbrains.hexana.maven

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VfsUtil
import org.jetbrains.idea.maven.importing.MavenAfterImportConfigurator
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.hexana.javaLang.ChicoryAotConfig
import org.jetbrains.hexana.javaLang.ChicoryAotRegistry
import org.jetbrains.hexana.javaLang.ConfigSource
import org.jdom.Element

private val LOG = logger<ChicoryMavenConfigurator>()

internal const val CHICORY_GROUP_ID = "com.dylibso.chicory"
internal const val CHICORY_ARTIFACT_ID = "chicory-compiler-maven-plugin"

/**
 * Extract every [ChicoryAotConfig] declared by `chicory-compiler-maven-plugin`
 * across [mavenProjects]. Shared by [ChicoryMavenConfigurator] (post-sync hook)
 * and [ChicoryMavenStartupActivity] (project-open hook), so both fire-time and
 * cold-start populate the registry from the same source of truth.
 */
internal fun extractChicoryConfigs(mavenProjects: Collection<MavenProject>): List<ChicoryAotConfig> {
    val collected = mutableListOf<ChicoryAotConfig>()
    var modulesWithPlugin = 0
    for (mp in mavenProjects) {
        val plugin = mp.findPlugin(CHICORY_GROUP_ID, CHICORY_ARTIFACT_ID) ?: continue
        modulesWithPlugin++
        val baseDirVf = mp.directoryFile ?: continue
        val baseDir = VfsUtil.pathToUrl(baseDirVf.path)
        collected += extractConfigsForPlugin(plugin, baseDir)
    }
    LOG.warn("[chicory-debug] extractChicoryConfigs: scanned ${mavenProjects.size} modules, $modulesWithPlugin had chicory plugin, total=${collected.size}")
    return collected
}

private fun extractConfigsForPlugin(plugin: MavenPlugin, baseDir: String): List<ChicoryAotConfig> {
    val results = mutableListOf<ChicoryAotConfig>()
    plugin.configurationElement?.let { cfg ->
        extractFromElement(cfg, baseDir)?.let { results += it }
    }
    for (execution in plugin.executions) {
        execution.configurationElement?.let { cfg ->
            extractFromElement(cfg, baseDir)?.let { results += it }
        }
    }
    return results
}

private fun extractFromElement(config: Element, baseDir: String): ChicoryAotConfig? {
    val wasmFile = config.getChildTextTrim("wasmFile") ?: return null
    val moduleInterface = config.getChildTextTrim("moduleInterface") ?: return null
    if (wasmFile.isBlank() || moduleInterface.isBlank()) return null
    return ChicoryAotConfig(wasmFile, moduleInterface, baseDir)
}

/**
 * Maven import configurator that discovers Chicory AOT compiler-plugin entries
 * and populates [ChicoryAotRegistry] after each Maven sync.
 *
 * Fires via the `importing.afterImportConfigurator` extension point
 * (registered in `META-INF/org.jetbrains.hexana.maven.xml`), which is called
 * once the full resolved Maven model is available — property interpolation has
 * already been applied by the time [afterImport] is invoked.
 *
 * ### Discovery strategy
 *
 * For each Maven module in the project tree we:
 * 1. Locate `chicory-compiler-maven-plugin` via [MavenProject.findPlugin].
 * 2. Extract `<wasmFile>` and `<moduleInterface>` from both the plugin-level
 *    `<configuration>` element and every per-execution `<configuration>`.
 * 3. Resolve relative paths against the pom's directory (VFS URL).
 *
 * The `baseDir` stored in each [ChicoryAotConfig] is the VFS URL of the module's
 * root directory, so [ChicoryAotRegistry.findMatch] can call
 * `VirtualFile.findFileByRelativePath` at query time.
 */
class ChicoryMavenConfigurator : MavenAfterImportConfigurator {

    override fun afterImport(context: MavenAfterImportConfigurator.Context) {
        val project = context.project
        val mavenProjects = context.mavenProjectsWithModules.map { it.mavenProject }.toList()
        LOG.warn("[chicory-debug] maven afterImport: handling ${mavenProjects.size} modules")
        val collected = extractChicoryConfigs(mavenProjects)
        ChicoryAotRegistry.getInstance(project).setConfigs(ConfigSource.MAVEN, collected)
    }
}
