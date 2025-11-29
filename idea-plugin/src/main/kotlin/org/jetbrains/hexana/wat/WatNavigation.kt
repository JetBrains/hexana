package org.jetbrains.hexana.wat

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.LighterASTNode
import com.intellij.lang.impl.PsiBuilderImpl
import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.diff.FlyweightCapableTreeStructure
import com.jetbrains.rd.util.CopyOnWriteArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import org.jetbrains.hexana.wat.psi.WatElementType
import org.jetbrains.hexana.wat.psi.WatFileElementType
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatParser
import org.jetbrains.hexana.wat.psi.WatToken
import kotlin.sequences.emptySequence

class WatNavigation {
    companion object {
        fun navigateInWatEditor(watFile: VirtualFile, offset: Int): Boolean {
            val fileset = watFile.getUserData(WatFileset.KEY)
            if (fileset?.navigateToFile(watFile, offset) == true) {
                return true
            }
            return false
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

data class PreviewLocation(val fileIndex: Int, val fileOffset: Int)

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

    fun getByIndex(index: Int): PreviewLocation? {
        if (index in fileIndexes.indices) {
            return PreviewLocation(fileIndexes.getInt(index), fileOffsets.getInt(index))
        }
        return null
    }

    fun getByName(name: String?): PreviewLocation? {
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

    fun getByIndex(elementType: IElementType, index: Int): PreviewLocation? {
        val elementTypeIndex = byElementType[elementType] ?: return null
        return elementTypeIndex.getByIndex(index)
    }

    fun getByName(elementType: IElementType, name: String?): PreviewLocation? {
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

    private val closedEditors = CopyOnWriteArrayList<Editor>()

    var editor: Editor? = null
        set(value) {
            field?.let {
                closedEditors.add(it)
            }
            field = value
        }

    fun install() {
        for (f in files) {
            f.putUserData(KEY, this)
        }
    }

    fun releaseClosedEditors() {
        closedEditors.forEach {
            EditorFactory.getInstance().releaseEditor(it)
        }
        closedEditors.clear()
    }

    fun navigateToFile(file: VirtualFile, offset: Int): Boolean {
        val currentEditor = editor ?: return false
        if (currentEditor.virtualFile == file) {
            return false
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
    val preview = WatFileset(watFiles, fileIndex = 0,  watIndex = watIndex, onFileChange = onWatFileChange)
    preview.install()
    return preview
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
