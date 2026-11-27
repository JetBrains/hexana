package org.jetbrains.hexana.shared

/** A TCP socket a process listens on, with wildcard addresses mapped to loopback. */
data class ListeningEndpoint(val host: String, val port: Int)

/**
 * Finds the TCP ports a process listens on, for attach targets whose debug
 * endpoint uses an OS-chosen port ([WasmAttachTarget.needsPortDiscovery]).
 * Hosts run the commands or read the files; parsing is shared:
 *
 * - macOS (and Linux with lsof): `lsof` with [lsofArguments], parsed by [parseLsof].
 * - Linux: `/proc/net/tcp` and `/proc/net/tcp6` filtered by the socket inodes
 *   of `/proc/<pid>/fd/` links ([socketInode]), parsed by [parseProcNetTcp].
 *   Needs no tool, only read access to the process's fd directory.
 * - Windows: `netstat` with [NETSTAT_ARGUMENTS], parsed by [parseNetstat].
 */
object ListeningPorts {

    fun lsofArguments(pid: Int): List<String> =
        listOf("-nP", "-a", "-p", pid.toString(), "-iTCP", "-sTCP:LISTEN", "-Fn")

    /** Parses `lsof -Fn` field output: one `n<address>:<port>` line per socket. */
    fun parseLsof(output: String): List<ListeningEndpoint> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("n") }
            .mapNotNull { line ->
                val address = line.substring(1).substringBefore("->")
                val portIndex = address.lastIndexOf(':')
                if (portIndex <= 0) return@mapNotNull null
                val port = address.substring(portIndex + 1).toIntOrNull() ?: return@mapNotNull null
                endpoint(address.substring(0, portIndex).removePrefix("[").removeSuffix("]"), port)
            }
            .distinct()
            .toList()

    /** The socket inode of a `/proc/<pid>/fd/<n>` link target (`socket:[12345]`); null for other files. */
    fun socketInode(linkTarget: String): String? =
        SOCKET_LINK.matchEntire(linkTarget.trim())?.groupValues?.get(1)

    /**
     * Parses `/proc/net/tcp` or `/proc/net/tcp6` content, keeping listening
     * sockets (state `0A`) whose inode is in [socketInodes].
     */
    fun parseProcNetTcp(content: String, socketInodes: Set<String>): List<ListeningEndpoint> =
        content.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val fields = line.trim().split(WHITESPACE)
                if (fields.size < 10 || fields[3] != TCP_LISTEN_STATE || fields[9] !in socketInodes) {
                    return@mapNotNull null
                }
                val (hexHost, hexPort) = fields[1].split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val port = hexPort.toIntOrNull(16) ?: return@mapNotNull null
                val host = decodeProcNetHost(hexHost) ?: return@mapNotNull null
                endpoint(host, port)
            }
            .distinct()
            .toList()

    val NETSTAT_ARGUMENTS: List<String> = listOf("-ano")

    /**
     * Parses `netstat -ano` output, keeping the listening TCP sockets of [pid].
     * A listening socket is recognized by its `0.0.0.0:0` / `[::]:0` foreign
     * address rather than by the state column, which Windows localizes.
     */
    fun parseNetstat(output: String, pid: Int): List<ListeningEndpoint> =
        output.lineSequence()
            .mapNotNull { line ->
                val fields = line.trim().split(WHITESPACE)
                if (fields.size < 5 || !fields[0].equals("TCP", ignoreCase = true)) return@mapNotNull null
                if (fields.last().toIntOrNull() != pid) return@mapNotNull null
                if (fields[2] != "0.0.0.0:0" && fields[2] != "[::]:0") return@mapNotNull null
                val local = fields[1]
                val portIndex = local.lastIndexOf(':')
                if (portIndex <= 0) return@mapNotNull null
                val port = local.substring(portIndex + 1).toIntOrNull() ?: return@mapNotNull null
                endpoint(local.substring(0, portIndex).removePrefix("[").removeSuffix("]"), port)
            }
            .distinct()
            .toList()

    private fun endpoint(rawHost: String, port: Int): ListeningEndpoint? {
        if (port !in 1..65535) return null
        val host = when (rawHost.substringBefore('%')) {
            "", "*", "0.0.0.0", "::", "0:0:0:0:0:0:0:0" -> LOOPBACK
            else -> rawHost.substringBefore('%')
        }
        return ListeningEndpoint(host, port)
    }

    /**
     * Decodes a `/proc/net/tcp*` address: IPv4 is one little-endian 32-bit
     * word, IPv6 four of them. IPv4-mapped IPv6 addresses become IPv4.
     */
    private fun decodeProcNetHost(hex: String): String? {
        val words = hex.chunked(8).map { word ->
            if (word.length != 8) return null
            (0 until 4).map { word.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }.reversed()
        }
        val bytes = words.flatten()
        return when (bytes.size) {
            4 -> bytes.joinToString(".")
            16 -> {
                val isV4Mapped = bytes.subList(0, 10).all { it == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF
                if (isV4Mapped) {
                    bytes.subList(12, 16).joinToString(".")
                } else {
                    (0 until 8).joinToString(":") { group ->
                        ((bytes[group * 2] shl 8) or bytes[group * 2 + 1]).toString(16)
                    }.let { if (it == "0:0:0:0:0:0:0:1") "::1" else it }
                }
            }
            else -> null
        }
    }

    private val SOCKET_LINK = Regex("""socket:\[(\d+)\]""")
    private val WHITESPACE = Regex("""\s+""")
    private const val TCP_LISTEN_STATE = "0A"
    private const val LOOPBACK = "127.0.0.1"
}
