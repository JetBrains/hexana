package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.hexana.wit.psi.WitElementTypes;
import org.jetbrains.hexana.wit.psi.WitIncludeNamesItem;
import org.jetbrains.hexana.wit.psi.WitPsiFile;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitIncludeNamesItemManipulator extends AbstractElementManipulator<WitIncludeNamesItem> {
    @Override
    public @Nullable WitIncludeNamesItem handleContentChange(@NonNull WitIncludeNamesItem element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        WitPsiFile file = WitHandleManipulator.createDummyFile(element.getProject(), "package foo:bar; world x { include bar with { " + newContent + " as " + element.getLastChild().getText() +" } }");
        WitIncludeNamesItem item = (WitIncludeNamesItem) SyntaxTraverser.psiTraverser(file).filterTypes(type -> type == WitElementTypes.INCLUDE_NAMES_ITEM).bfsTraversal().single();
        return (WitIncludeNamesItem) element.replace(item);
    }
}
