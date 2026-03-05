package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import com.intellij.psi.ElementManipulator;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.hexana.wit.WitFileType;
import org.jetbrains.hexana.wit.psi.WitElementTypes;
import org.jetbrains.hexana.wit.psi.WitHandle;
import org.jetbrains.hexana.wit.psi.WitPsiFile;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitHandleManipulator extends AbstractElementManipulator<WitHandle> {
    @Override
    public @Nullable WitHandle handleContentChange(@NonNull WitHandle element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(element.getProject());
        WitPsiFile file = (WitPsiFile) psiFileFactory.createFileFromText("dummy.wit", WitFileType.INSTANCE, "package foo; type X = " + newContent + ";");
        return (WitHandle) SyntaxTraverser.psiTraverser(file).filterTypes(type -> type == WitElementTypes.HANDLE).bfsTraversal().single();
    }
}
