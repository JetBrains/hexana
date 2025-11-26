package org.jetbrains.hexana

import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.PersistentFSConstants
import com.intellij.openapi.vfs.limits.ExtensionSizeLimitInfo
import com.intellij.openapi.vfs.limits.FileSizeLimit

class WasmFileSizeChecker : FileSizeLimit {
    override val acceptableExtensions: List<String>
        get() = listOf("wasm")

    override fun getLimits(): ExtensionSizeLimitInfo {
        val limit = 50 * 1024 * 1024
        return ExtensionSizeLimitInfo(
            content = maxOf(limit, FileSizeLimit.getDefaultContentLoadLimit()),
            intellijSense = maxOf(limit, PersistentFSConstants.getMaxIntellisenseFileSize()),
            preview = maxOf(limit, FileUtilRt.LARGE_FILE_PREVIEW_SIZE),
        )
    }
}