package org.jetbrains.hexana.mcp;

import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;
import com.intellij.openapi.extensions.Extensions

@Suppress("UnstableApiUsage")
class McpServerCustomizer: ActionConfigurationCustomizer {
    override fun customize(): ActionConfigurationCustomizer.CustomizeStrategy {
        val area = Extensions.getRootArea()
        area.getExtensionPoint<McpToolset>("com.intellij.mcpServer.mcpToolset").registerExtension(HexanaToolset())
        return super.customize();
    }
}
