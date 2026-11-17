package org.jetbrains.hexana.mcp.vscode.server

fun main(args: Array<String>) {
    org.jetbrains.hexana.mcp.server.main(normalizeVsCodeServerArgs(args))
}

internal fun normalizeVsCodeServerArgs(args: Array<String>): Array<String> {
    val transportIndex = args.indexOf("--transport")
    if (transportIndex == -1) {
        return args
    }

    require(transportIndex + 1 < args.size) {
        "`--transport` requires one of: stdio, http"
    }

    val transport = args[transportIndex + 1]
    require(transport == "stdio" || transport == "http") {
        "Unsupported `--transport`: $transport. Expected one of: stdio, http"
    }

    return buildList {
        add(transport)
        args.forEachIndexed { index, arg ->
            if (index != transportIndex && index != transportIndex + 1) {
                add(arg)
            }
        }
    }.toTypedArray()
}
