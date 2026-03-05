package org.jetbrains.hexana.wit.psi.impl;

import com.intellij.openapi.project.Project;
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
import org.jetbrains.hexana.wit.psi.WitUsePath;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class WitHandleManipulator extends AbstractElementManipulator<WitHandle> {
    @Override
    public @Nullable WitHandle handleContentChange(@NonNull WitHandle element, @NotNull TextRange range, String newContent) throws IncorrectOperationException {
        WitPsiFile file = createDummyFile(element.getProject(), "package foo; type X = " + newContent + ";");
        return (WitHandle) SyntaxTraverser.psiTraverser(file).filterTypes(type -> type == WitElementTypes.HANDLE).bfsTraversal().single();
    }

    public static @NonNull WitPsiFile createDummyFile(@NonNull Project project, String text) {
        PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(project);
        return (WitPsiFile) psiFileFactory.createFileFromText("dummy.wit", WitFileType.INSTANCE, text);
    }
}

