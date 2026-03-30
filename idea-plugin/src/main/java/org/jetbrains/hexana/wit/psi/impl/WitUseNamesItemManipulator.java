package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.hexana.wit.psi.WitElementTypes;
import org.jetbrains.hexana.wit.psi.WitIncludeNamesItem;
import org.jetbrains.hexana.wit.psi.WitPsiFile;
import org.jetbrains.hexana.wit.psi.WitUseNamesItem;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitUseNamesItemManipulator extends AbstractElementManipulator<WitUseNamesItem> {
    @Override
    public @Nullable WitUseNamesItem handleContentChange(@NonNull WitUseNamesItem element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        WitPsiFile file = WitHandleManipulator.createDummyFile(element.getProject(), "package foo:bar; world x { use bar.{ " + newContent + " } };");
        WitUseNamesItem item = (WitUseNamesItem) SyntaxTraverser.psiTraverser(file).filterTypes(type -> type == WitElementTypes.USE_NAMES_ITEM).bfsTraversal().single();
        return (WitUseNamesItem) element.replace(item);
    }
}

