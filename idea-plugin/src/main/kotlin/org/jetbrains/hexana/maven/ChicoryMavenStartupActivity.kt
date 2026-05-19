package org.jetbrains.hexana.maven

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.hexana.javaLang.ChicoryAotRegistry
import org.jetbrains.hexana.javaLang.ConfigSource
import org.jetbrains.idea.maven.project.MavenProjectsManager

private val LOG = logger<ChicoryMavenStartupActivity>()

/**
 * Populates [ChicoryAotRegistry] from the already-resolved Maven project model
 * at project-open time, so the registry isn't empty until the user manually
 * triggers a Maven re-sync.
 *
 * The IDE persists the resolved Maven model across restarts. The post-sync
 * [ChicoryMavenConfigurator] only fires on actual sync events — not on cold
 * restart — so without this activity, every IDE relaunch leaves the registry
 * empty until the user reimports.
 *
 * Best-effort: if Maven hasn't loaded its model yet by the time we run
 * (cold first import), [MavenProjectsManager.projects] is empty and the
 * post-sync configurator will fire later anyway.
 */
internal class ChicoryMavenStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val mavenProjects = MavenProjectsManager.getInstance(project).projects
        if (mavenProjects.isEmpty()) {
            LOG.warn("[chicory-debug] maven startup: no projects loaded yet — afterImport will populate")
            return
        }
        LOG.warn("[chicory-debug] maven startup: ${mavenProjects.size} projects already loaded, extracting now")
        val collected = extractChicoryConfigs(mavenProjects)
        ChicoryAotRegistry.getInstance(project).setConfigs(ConfigSource.MAVEN, collected)
    }
}