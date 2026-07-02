package org.jetbrains.hexana.mcp

import java.nio.file.Files
import java.nio.file.Path

class AllowedFilePolicy private constructor(
    private val allowAllFiles: Boolean,
    private val allowedRoots: List<Path>,
) {
    val allowsAllFiles: Boolean
        get() = allowAllFiles

    fun resolveAllowedRegularFile(rawPath: String, purpose: String): Path {
        if (rawPath.isBlank()) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Path must not be blank")
        }

        val requestedPath = try {
            Path.of(rawPath)
        } catch (_: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$purpose path is invalid"
            )
        }
        if (!Files.exists(requestedPath) || !Files.isRegularFile(requestedPath)) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.FILE_NOT_FOUND, "File not found")
        }

        val realPath = try {
            requestedPath.toRealPath()
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to resolve $purpose path",
                error
            )
        }
        if (allowAllFiles || allowedRoots.any { realPath.startsWith(it) }) {
            return realPath
        }

        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "$purpose path is outside allowed roots. Configure an allowed root that contains the file."
        )
    }

    fun resolveAllowedDirectory(rawPath: String, purpose: String): Path {
        if (rawPath.isBlank()) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Path must not be blank")
        }

        val requestedPath = try {
            Path.of(rawPath)
        } catch (_: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INVALID_INPUT,
                "$purpose path is invalid"
            )
        }
        if (!Files.exists(requestedPath) || !Files.isDirectory(requestedPath)) {
            throw HexanaAnalysisException(HexanaAnalysisErrorCode.FILE_NOT_FOUND, "Directory not found")
        }

        val realPath = try {
            requestedPath.toRealPath()
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to resolve $purpose path",
                error
            )
        }
        if (allowAllFiles || allowedRoots.any { realPath.startsWith(it) }) {
            return realPath
        }

        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "$purpose path is outside allowed roots. Configure an allowed root that contains the directory."
        )
    }

    companion object {
        val allowAll: AllowedFilePolicy = AllowedFilePolicy(allowAllFiles = true, allowedRoots = emptyList())

        fun requiringAllowedRoots(roots: List<String>): AllowedFilePolicy {
            val resolvedRoots = roots
                .filter { it.isNotBlank() }
                .map { rawRoot ->
                    val root = try {
                        Path.of(rawRoot)
                    } catch (_: Exception) {
                        throw HexanaAnalysisException(
                            HexanaAnalysisErrorCode.INVALID_INPUT,
                            "Allowed root path is invalid: $rawRoot"
                        )
                    }
                    if (!Files.exists(root) || !Files.isDirectory(root)) {
                        throw HexanaAnalysisException(
                            HexanaAnalysisErrorCode.INVALID_INPUT,
                            "Allowed root does not exist or is not a directory: $rawRoot"
                        )
                    }
                    try {
                        root.toRealPath()
                    } catch (error: Exception) {
                        throw HexanaAnalysisException(
                            HexanaAnalysisErrorCode.INTERNAL_ERROR,
                            "Failed to resolve allowed root: $rawRoot",
                            error
                        )
                    }
                }
                .distinct()

            return AllowedFilePolicy(allowAllFiles = false, allowedRoots = resolvedRoots)
        }
    }
}
