tasks.register("jib") {
    group = "containerization"
    description = "Builds and pushes the standalone MCP server container image via :mcp:server:jib."
    dependsOn(":mcp:server:jib")
}
