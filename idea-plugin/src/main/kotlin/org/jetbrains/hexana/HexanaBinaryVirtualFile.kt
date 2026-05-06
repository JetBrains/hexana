package org.jetbrains.hexana

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer

class HexanaBinaryVirtualFile(
    name: String,
    val buffer: CommonByteBuffer,
    val baseOffset: Int = 0,
    val parentFile: VirtualFile? = null,
) : LightVirtualFile(name) {

    companion object {
        val BUFFER_KEY = Key.create<CommonByteBuffer>("hexana.in_memory_buffer")
        val BASE_OFFSET_KEY = Key.create<Int>("hexana.base_offset")
        val PARENT_FILE_KEY = Key.create<VirtualFile>("hexana.parent_file")
    }

    init {
        fileType = WasmFileType.INSTANCE
        putUserData(BUFFER_KEY, buffer)
        putUserData(BASE_OFFSET_KEY, baseOffset)
        if (parentFile != null) {
            putUserData(PARENT_FILE_KEY, parentFile)
        }
    }
}