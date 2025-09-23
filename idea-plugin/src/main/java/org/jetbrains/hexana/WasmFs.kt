package org.jetbrains.hexana

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.ArchiveHandler
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem
import com.intellij.openapi.vfs.newvfs.VfsImplUtil
import com.intellij.openapi.vfs.newvfs.impl.FsRoot
import fleet.util.safeAs
import org.jetbrains.annotations.NonNls
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.sections.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.let


class WasmFs : ArchiveFileSystem() {
    companion object {
        const val PROTOCOL: @NonNls String = "wasm"
        const val SEPARATOR: @NonNls String = "!/"
    }

    override fun getProtocol(): @NonNls String {
        return PROTOCOL
    }

    override fun composeRootPath(localPath: String): String {
        return localPath + SEPARATOR
    }

    override fun extractLocalPath(rootPath: String): String {
        return rootPath.removeSuffix(SEPARATOR)
    }

    override fun extractRootPath(composedPath: String): String {
        return WasmFsUtil.extractRootPath(composedPath)
    }

    override fun isCorrectFileType(local: VirtualFile): Boolean {
        val fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(local.nameSequence)
        return fileType === WasmFileType.INSTANCE
    }

    public override fun getHandler(entryFile: VirtualFile): ArchiveHandler {
        return VfsImplUtil.getHandler(this, entryFile) { localPath ->
            WasmArchiveHandler(entryFile, localPath)
        }
    }

    override fun findFileByPathIfCached(path: String): VirtualFile? {
        return null
    }

    override fun findFileByPath(path: String): VirtualFile? {
        return VfsImplUtil.findFileByPath(this, path)
    }

    override fun refreshAndFindFileByPath(path: String): VirtualFile? {
        refresh(false)
        return findFileByPath(path)
    }

    override fun refresh(asynchronous: Boolean) {
    }
}


class WasmArchiveHandler(
    val wasmRoot: VirtualFile,
    localPath: String
) : ArchiveHandler(localPath) {

    public override fun getEntriesMap(): Map<String, EntryInfo> {
        return super.getEntriesMap()
    }

    public override fun getEntryInfo(relativePath: String): EntryInfo? {
        return super.getEntryInfo(relativePath)
    }

    override fun createEntriesMap(): Map<String, EntryInfo> {
        val localFile = VfsUtil.findFile(path, true) ?: return emptyMap()

        val parser = WasmParser()
        val content = try {
            localFile.contentsToByteArray()
        } catch (_: Exception) {
            return emptyMap()
        }
        val module = parser.parse(CommonByteBuffer(ByteBuffer.wrap(content))) ?: return emptyMap()

        val sections = listOfNotNull(
            module.customSection,
            module.typesSection,
            module.importSection,
            module.functionSection,
            module.tableSection,
            module.memorySection,
            module.globalSection,
            module.exportSection,
            module.startSection,
            module.elementSection,
            module.codeSection,
            module.dataSection,
            module.dataCountSection,
            module.exceptionSection,
        ).sortedBy { it.offset }

        val result = mutableMapOf<String, EntryInfo>()
        val timestamp = localFile.timeStamp
        val root = WasmEntryInfo("", true, 0, content.size.toLong(), timestamp, null)
        result[""] = root
        for (section in sections) {
            result[section.name] =
                WasmEntryInfo(section.name, false, section.offset.toLong(), section.size.toLong(), timestamp, root)
        }
        return result
    }

    override fun contentsToByteArray(relativePath: String): ByteArray {
        return ByteArray(0)
    }


    val WasmSection.name: String
        get() {
            return when (this) {
                is WasmSectionCustom -> "custom"
                is WasmSectionTypes -> "types"
                is WasmSectionImport -> "import"
                is WasmSectionFunctions -> "functions"
                is WasmSectionTable -> "table"
                is WasmSectionMemory -> "memory"
                is WasmSectionGlobal -> "global"
                is WasmSectionExport -> "export"
                is WasmSectionStart -> "start"
                is WasmSectionElement -> "element"
                is WasmSectionCode -> "code"
                is WasmSectionData -> "data"
                is WasmSectionDataCount -> "data-count"
                is WasmSectionException -> "exception"
                else -> "unknown"
            }
        }
}


// EntryInfo with offset
class WasmEntryInfo(
    shortName: CharSequence,
    isDirectory: Boolean,
    val offset: Long,
    length: Long,
    timestamp: Long,
    parent: ArchiveHandler.EntryInfo?
) : ArchiveHandler.EntryInfo(shortName, isDirectory, length, timestamp, parent)


class WasmEntryFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (file.url.startsWith(WasmFs.PROTOCOL)) {
            return WasmFileType.INSTANCE
        }
        return null
    }
}


object WasmFsUtil {

    fun getUrl(localPath: String): String {
        return "${WasmFs.PROTOCOL}://${localPath}${WasmFs.SEPARATOR}"
    }

    fun extractRootPath(composedPath: String): String {
        val separatorIdx = composedPath.indexOf(WasmFs.SEPARATOR)
        return composedPath.take(separatorIdx + WasmFs.SEPARATOR.length)
    }

    fun getLocalFile(wasmEntryFile: VirtualFile): VirtualFile? {
        val fileUrl = wasmEntryFile.url
        val scheme = "${WasmFs.PROTOCOL}://"
        if (fileUrl.startsWith(scheme)) {
            val separatorIdx = fileUrl.lastIndexOf(WasmFs.SEPARATOR)
            if (separatorIdx >= 0) {
                val localPath = fileUrl.take(separatorIdx).drop(scheme.length)
                val rootFile = VfsUtil.findFile(Path.of(localPath), true)
                return rootFile
            }
        }
        return null
    }

    fun getRange(wasmEntryFile: VirtualFile): LongRange? {
        val url = wasmEntryFile.url
        val rootUrl = extractRootPath(url)
        val relativePath = url.drop(rootUrl.length)
        val rootFile = VirtualFileManager.getInstance().findFileByUrl(rootUrl) ?: return null
        val handler = rootFile.safeAs<FsRoot>()?.fileSystem.safeAs<WasmFs>()?.getHandler(rootFile) as? WasmArchiveHandler
        val info = handler?.getEntryInfo(relativePath) as? WasmEntryInfo
        return info?.let {
            return it.offset..<it.offset + it.length
        }
    }
}

