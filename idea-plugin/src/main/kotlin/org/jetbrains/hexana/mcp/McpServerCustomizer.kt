package org.jetbrains.hexana.mcp;

import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;
import com.intellij.openapi.extensions.Extensions

@Suppress("UnstableApiUsage")
class McpServerCustomizer: ActionConfigurationCustomizer {
    override fun customize(): ActionConfigurationCustomizer.CustomizeStrategy {
        val area = Extensions.getRootArea()
        area.getExtensionPoint<McpToolsProvider>("com.intellij.mcpServer.mcpToolsProvider").registerExtension(HexanaToolset())
        return super.customize();
    }
}
