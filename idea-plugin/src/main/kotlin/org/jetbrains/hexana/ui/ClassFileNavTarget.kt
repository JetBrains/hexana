package org.jetbrains.hexana.ui

import com.intellij.openapi.util.Key

/**
 * Navigation target handed to [ClassFileEditorView] when another view (today: the
 * JIT inline-frame click in `JitFileEditorView`) wants the bytecode editor to
 * scroll to a specific method and optionally a specific BCI inside it.
 *
 * Producers stash an instance in [CLASS_FILE_NAV_TARGET_KEY] on the
 * [com.intellij.openapi.vfs.VirtualFile] before opening, or push it into the
 * editor's `MutableStateFlow` if the editor is already open.
 */
data class ClassFileNavTarget(
    /** Method name as written in the class file's constant pool, e.g. `decode`, `<init>`. */
    val methodName: String,
    /** JVM method descriptor, e.g. `(Lfoo/Bar;I)V`. Matched byte-for-byte against `Code` rows. */
    val methodDescriptor: String,
    /** Optional bytecode index to scroll to within the matched method. */
    val bci: Int? = null,
)

val CLASS_FILE_NAV_TARGET_KEY: Key<ClassFileNavTarget> = Key.create("hexana.classFile.navTarget")

