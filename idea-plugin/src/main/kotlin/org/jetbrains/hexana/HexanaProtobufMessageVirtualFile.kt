package org.jetbrains.hexana

import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * In-memory virtual file for the "Open As Protobuf Message" action: it wraps an opaque
 * file's bytes and forces them to be decoded by [ProtobufMessageBinaryProvider], regardless
 * of the source file's real type (e.g. `.bin`, `.pb`).
 *
 * The buffer is published through [HexanaBinaryVirtualFile.BUFFER_KEY] user data so that
 * [HexanaFileEditor.resolveProvider] picks it up as its in-memory `commonBuffer` — the same
 * mechanism [HexanaBinaryVirtualFile] uses. Its [fileType] is [ProtobufMessageFileType], so
 * `resolveProvider` branches on that type to build the raw-wire provider.
 */
class HexanaProtobufMessageVirtualFile(
    name: String,
    val buffer: CommonByteBuffer,
) : LightVirtualFile(name) {
    init {
        fileType = ProtobufMessageFileType.INSTANCE
        putUserData(HexanaBinaryVirtualFile.BUFFER_KEY, buffer)
    }
}
