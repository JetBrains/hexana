package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.hexana.wit.psi.WitElementTypes;
import org.jetbrains.hexana.wit.psi.WitPsiFile;
import org.jetbrains.hexana.wit.psi.WitUsePath;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitUsePathManipulator extends AbstractElementManipulator<WitUsePath> {
    @Override
    public @Nullable WitUsePath handleContentChange(@NonNull WitUsePath element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        WitPsiFile file = WitHandleManipulator.createDummyFile(element.getProject(), "package foo:bar; use " + newContent + ";");
        WitUsePath path = (WitUsePath) SyntaxTraverser.psiTraverser(file).filterTypes(type -> type == WitElementTypes.USE_PATH).bfsTraversal().single();
        return (WitUsePath)element.replace(path);
    }
}
