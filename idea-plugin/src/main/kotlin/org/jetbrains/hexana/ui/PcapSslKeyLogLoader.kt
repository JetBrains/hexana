package org.jetbrains.hexana.ui

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.pcap.SslKeyLog
import org.jetbrains.hexana.providers.PcapBinaryProvider

/**
 * Well-known sibling file names for auto-detecting SSL key log files,
 * in the order they are tried.
 */
internal val SSL_KEY_LOG_SIBLING_NAMES = listOf(
    "sslkeylog.txt",
    "sslkeylog.log",
    "ssl-key.log",
    "keylog.txt",
    "premaster.txt",
)

/**
 * Picks the SSL key log candidate out of [childNames] for a capture whose
 * name without extension is [pcapBaseName]. Well-known names win in the
 * order they appear in [SSL_KEY_LOG_SIBLING_NAMES]; otherwise a sibling
 * named `<base>.keylog` or `<base>.sslkeylog` matches. Comparison is
 * case-insensitive. Returns the matching entry of [childNames] as spelled
 * there, or `null` when nothing matches.
 *
 * Kept separate from [autoDetectSslKeyLog] so the naming rules can be
 * tested without a VFS.
 */
internal fun siblingKeyLogCandidate(childNames: List<String>, pcapBaseName: String): String? {
    val byLowercase = childNames.associateBy { it.lowercase() }
    for (name in SSL_KEY_LOG_SIBLING_NAMES) {
        byLowercase[name]?.let { return it }
    }
    val base = pcapBaseName.lowercase()
    return byLowercase["$base.keylog"] ?: byLowercase["$base.sslkeylog"]
}

/**
 * Attempts to find an SSL key log file next to [pcapFile], using the
 * naming rules of [siblingKeyLogCandidate]. Directories are skipped.
 */
internal fun autoDetectSslKeyLog(pcapFile: VirtualFile): VirtualFile? {
    val parent = pcapFile.parent ?: return null
    val files = parent.children.orEmpty().filter { it.isValid && !it.isDirectory }
    val name = siblingKeyLogCandidate(files.map { it.name }, pcapFile.nameWithoutExtension)
        ?: return null
    return files.firstOrNull { it.name == name }
}

/**
 * Returns `true` when [content] looks like a PEM-encoded private key
 * (RSA, EC, PKCS#8, etc.) rather than an NSS Key Log file.
 */
internal fun looksLikePrivateKey(content: String): Boolean {
    val trimmed = content.trimStart()
    return trimmed.startsWith("-----BEGIN") && trimmed.contains("PRIVATE KEY-----")
}

/**
 * Looks for a key log next to [captureFile] and, if one parses to a
 * non-empty [SslKeyLog], installs it on [provider]. Returns `true` when a
 * key log was installed.
 *
 * [captureFile] is passed in rather than read off the provider: captures
 * resolve to a plain [PcapBinaryProvider], never to a
 * `BinaryVirtualFileProvider`, so there is no `provider.file` to ask for —
 * asking for it anyway is how this used to silently never fire.
 *
 * Blocking: reads the VFS and the key file. Call from a background
 * dispatcher, and before the packet tabs are built so the "Packets" tab is
 * decrypted on first render rather than rebuilt afterwards.
 */
internal fun autoDetectAndLoadSslKeyLog(
    provider: PcapBinaryProvider,
    captureFile: VirtualFile?,
): Boolean {
    if (provider.sslKeyLog.isNotEmpty) return false
    val sourceFile = captureFile?.takeIf { it.isValid } ?: return false
    val keyFile = autoDetectSslKeyLog(sourceFile) ?: return false
    return try {
        val content = keyFile.contentsToByteArray().decodeToString()
        val keyLog = SslKeyLog.parse(content)
        if (!keyLog.isNotEmpty) return false
        provider.sslKeyLog = keyLog
        HexanaLog.LOG.info(
            "Auto-detected SSL key log: ${keyFile.name} (${keyLog.sessionCount} sessions)",
        )
        true
    } catch (t: Throwable) {
        HexanaLog.LOG.warn("Failed to load auto-detected SSL key log: ${keyFile.name}", t)
        false
    }
}
