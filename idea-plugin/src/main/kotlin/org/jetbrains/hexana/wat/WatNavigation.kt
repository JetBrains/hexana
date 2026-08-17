package org.jetbrains.hexana.wat

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.LighterASTNode
import com.intellij.lang.impl.PsiBuilderImpl
import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.diff.FlyweightCapableTreeStructure
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import org.jetbrains.hexana.wat.psi.*

class WatNavigation {
    companion object {
        /**
         * @param navigateIfFilesMatch move the caret even when the WAT view already shows
         *   [watFile]. Callers that only want to *redirect* the platform's own navigation leave
         *   this off, so that an unhandled case falls back to the default behaviour; callers that
         *   replace it entirely have to turn it on.
         */
        fun navigateInWatEditor(watFile: VirtualFile, offset: Int, navigateIfFilesMatch: Boolean = false): Boolean {
            val fileset = watFile.getUserData(WatFileset.KEY)
            if (fileset != null) {
                return fileset.navigateToFile(watFile, offset, navigateIfFilesMatch)
            }
            // Only a module too large for one document gets a fileset; an ordinary WAT view has a
            // single file and a single editor, already showing it. There is nothing to switch, so
            // only a caller that replaces the platform's navigation wants the caret moved.
            if (!navigateIfFilesMatch) {
                return false
            }
            val lightFile = watFile as? LightWatFile ?: return false
            val editor = WatViewEditors.liveEditor(lightFile) ?: return false
            WatViewEditors.moveCaret(editor, offset)
            return true
        }
    }
}


class LightWatFile(
    val originWasmFile: VirtualFile,
    watFileName: String,
    text: CharSequence,
): LightVirtualFile(watFileName, WatFileType.INSTANCE, text), BackedVirtualFile {
    override fun getOriginFile(): VirtualFile {
        return originWasmFile
    }
}

/** A navigation that is waiting for a WAT view to exist; see [WatViewEditors.requestNavigation]. */
private data class PendingWatNavigation(val watFileName: String, val offset: Int)

/**
 * The editor each WAT view is showing, so navigation can land *in* the view rather than open its
 * in-memory document as an editor of its own.
 *
 * Keyed by the binary the view belongs to, not by the view's own document: closing and reopening the
 * Hexana editor rebuilds the WAT view from scratch, with a fresh [LightWatFile], so anything holding
 * the old document -- a usage in the Find tool window, say -- could never reach the new editor.
 * A [WatFileset] additionally keeps its own editor, because it swaps chunks through one.
 */
object WatViewEditors {

    private val EDITOR = Key.create<Editor>("hexana.wat.view.editor")
    private val PENDING = Key.create<PendingWatNavigation>("hexana.wat.view.pendingNavigation")

    /** Records the editor a WAT view has just created for [watFile], honouring any pending request. */
    fun register(watFile: LightWatFile, editor: Editor) {
        val origin = watFile.originWasmFile
        origin.putUserData(EDITOR, editor)
        val pending = origin.getUserData(PENDING) ?: return
        origin.putUserData(PENDING, null)
        // Deferred: the view is still being built, and an editor cannot scroll before it has a size.
        ApplicationManager.getApplication().invokeLater {
            applyPending(watFile, editor, pending)
        }
    }

    /** The live editor of the view showing [watFile]'s binary, or null when there is none. */
    fun liveEditor(watFile: LightWatFile): Editor? {
        return watFile.originWasmFile.getUserData(EDITOR)?.takeIf { !it.isDisposed }
    }

    /**
     * Remembers where to navigate once the view for [watFile]'s binary has an editor again.
     *
     * Reopening the Hexana editor reconverts the binary asynchronously, so at the moment navigation
     * is asked for there is nothing to move a caret in yet. The view applies this from [register].
     */
    fun requestNavigation(watFile: LightWatFile, offset: Int) {
        watFile.originWasmFile.putUserData(PENDING, PendingWatNavigation(watFile.name, offset))
    }

    fun clearPending(watFile: LightWatFile) {
        watFile.originWasmFile.putUserData(PENDING, null)
    }

    fun moveCaret(editor: Editor, offset: Int) {
        if (editor.isDisposed || offset > editor.document.textLength) {
            return
        }
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun applyPending(shownFile: LightWatFile, editor: Editor, pending: PendingWatNavigation) {
        if (shownFile.name == pending.watFileName) {
            moveCaret(editor, pending.offset)
            return
        }
        // A split module: the request names a different chunk of the rebuilt fileset.
        val fileset = shownFile.getUserData(WatFileset.KEY) ?: return
        val chunk = fileset.files.firstOrNull { it.name == pending.watFileName } ?: return
        fileset.navigateToFile(chunk, pending.offset, navigateIfFilesMatch = true)
    }
}

data class FileIndexLocation(val fileIndex: Int, val fileOffset: Int)

class FileOffset(val file: VirtualFile, val offset: Int)

class WatElementIndex {
    // name -> element index
    val byName = Object2IntOpenHashMap<String>().apply {
        defaultReturnValue(-1)
    }
    // element index -> file index
    val fileIndexes = IntArrayList()
    // element index -> file offset
    val fileOffsets = IntArrayList()

    fun add(fileIndex: Int, offset: Int, name: String?) {
        val elementIndex = fileIndexes.size
        fileIndexes.add(fileIndex)
        fileOffsets.add(offset)
        if (name != null) {
            byName.put(name, elementIndex)
        }
    }

    fun getByIndex(index: Int): FileIndexLocation? {
        if (index in fileIndexes.indices) {
            return FileIndexLocation(fileIndexes.getInt(index), fileOffsets.getInt(index))
        }
        return null
    }

    fun getByName(name: String?): FileIndexLocation? {
        name ?: return null
        val index = byName.getInt(name)
        return getByIndex(index)
    }
}

class WatIndex {
    val byElementType = HashMap<IElementType, WatElementIndex>()

    fun add(fileIndex: Int, offset: Int, elementType: IElementType, name: String?) {
        byElementType.getOrPut(elementType) {
            WatElementIndex()
        }.add(fileIndex, offset, name)
    }

    fun getByIndex(elementType: IElementType, index: Int): FileIndexLocation? {
        val elementTypeIndex = byElementType[elementType] ?: return null
        return elementTypeIndex.getByIndex(index)
    }

    fun getByName(elementType: IElementType, name: String?): FileIndexLocation? {
        val elementTypeIndex = byElementType[elementType] ?: return null
        return elementTypeIndex.getByName(name)
    }
}


class WatFileset(
    val files: List<LightWatFile>,
    var fileIndex: Int = 0,
    var fileOffset: Int = 0,
    private val watIndex: WatIndex,
    val onFileChange: (WatFileset) -> Unit
) {

    companion object {
        val KEY = Key.create<WatFileset>("hexana_wat_fileset")
    }

    var editor: Editor? = null

    fun install() {
        for (f in files) {
            f.putUserData(KEY, this)
        }
    }

    fun navigateToFile(file: VirtualFile, offset: Int, navigateIfFilesMatch: Boolean = false): Boolean {
        // A fileset outlives the view that built it -- a usage can still hold one after the Hexana
        // editor was closed -- so its editor may be disposed by now.
        val currentEditor = editor?.takeIf { !it.isDisposed } ?: return false
        if (currentEditor.virtualFile == file) {
            if (navigateIfFilesMatch) {
                currentEditor.caretModel.moveToOffset(offset)
                currentEditor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
                fileOffset = offset
                return true
            }
            else {
                return false
            }
        }
        val index = files.indexOf(file)
        if (index == -1) {
            return false
        }
        fileIndex = index
        fileOffset = offset
        onFileChange(this)
        return true
    }

    val currentFile: LightWatFile
        get() = files[fileIndex]

    fun getByIndex(elementType: IElementType, index: Int): FileOffset? {
        val (file, offset) = watIndex.getByIndex(elementType, index) ?: return null
        if (file in files.indices) {
            return FileOffset(files[file], offset)
        }
        return null
    }

    fun getByName(elementType: IElementType, name: String?): FileOffset? {
        val (file, offset) = watIndex.getByName(elementType, name) ?: return null
        if (file in files.indices) {
            return FileOffset(files[file], offset)
        }
        return null
    }
}


fun createWatFileset(
    wasmFile: VirtualFile,
    watText: String,
    onWatFileChange: (WatFileset) -> Unit
): WatFileset {
    val watIndex = WatIndex()
    val watFiles = getWatFiles(wasmFile, watText, watIndex)
    val fileset = WatFileset(watFiles, fileIndex = watFiles.lastIndex,  watIndex = watIndex, onFileChange = onWatFileChange)
    fileset.install()
    return fileset
}

val MODULE_LEVEL_NODES = TokenSet.create(
    WatElementType.TYPE,
    WatElementType.IMPORT,
    WatElementType.EXPORT,
    WatElementType.GLOBAL,
    WatElementType.MEMORY,
    WatElementType.DATA,
    WatElementType.TABLE,
    WatElementType.ELEM,
    WatElementType.TAG,
    WatElementType.FUNC,
    WatToken.LINE_COMMENT,
    WatToken.BLOCK_COMMENT,
)

private fun getWatFiles(
    wasmFile: VirtualFile,
    watText: String,
    watIndex: WatIndex
): List<LightWatFile> {
    val baseFileName = wasmFile.name.removeSuffix(".wasm")

    val splitThreshold = 1024 * 1024
    if (watText.length < splitThreshold) {
        return listOf(
            LightWatFile(wasmFile, "${baseFileName}.wat", watText)
        )
    }
    val lexer = WatLexer()
    val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(WatLanguage)
    val project = ProjectManager.getInstance().defaultProject
    val psiBuilder = PsiBuilderImpl(project, null, parserDefinition, lexer, null, watText, null, null)
    val parser = WatParser()
    parser.doParse(WatFileElementType, psiBuilder)
    val tree = psiBuilder.lightTree
    val module = tree.getChildren(tree.root).filter { it.tokenType == WatElementType.MODULE }.singleOrNull()
    val moduleChildren = if (module != null) {
        tree.getChildren(module)
    } else {
        emptySequence()
    }

    if (moduleChildren.count() < 3) {
        return listOf(
            LightWatFile(wasmFile, "${baseFileName}.wat", watText)
        )
    }
    val fileContents = arrayListOf<StringBuilder>()
    var fileNum = 1
    var currentContent = StringBuilder("(module (;chunk $fileNum;)\n")
    fileContents.add(currentContent)
    for (node in moduleChildren) {
        if (!MODULE_LEVEL_NODES.contains(node.tokenType)) {
            continue
        }
        if (currentContent.length > splitThreshold) {
            currentContent.append(")\n")
            fileNum++
            currentContent = StringBuilder("(module (;chunk $fileNum;)\n")
            fileContents.add(currentContent)
        }
        if (!WatToken.COMMENTS_SET.contains(node.tokenType)) {
            val fileIndex = fileContents.size - 1
            val offset = currentContent.length
            val nameElement = tree.getChildren(node).firstOrNull { it.tokenType == WatElementType.IDENTIFIER }
            watIndex.add(fileIndex, offset, node.tokenType, nameElement?.toString())
        }
        currentContent.append(watText.substring(node.startOffset, node.endOffset))
        currentContent.append("\n")
    }
    currentContent.append(")\n")

    val result = fileContents.mapIndexed { index, content ->
        LightWatFile(wasmFile, "${baseFileName}_${index}.wat", content)
    }
    return result
}


fun FlyweightCapableTreeStructure<LighterASTNode>.getChildren(node: LighterASTNode): Sequence<LighterASTNode> {
    val childrenRef = Ref<Array<LighterASTNode>>()
    val count = getChildren(node, childrenRef)
    if (childrenRef.isNull) {
        return emptySequence()
    }
    val children = childrenRef.get()
    return sequenceOf(*children).take(count)
}
