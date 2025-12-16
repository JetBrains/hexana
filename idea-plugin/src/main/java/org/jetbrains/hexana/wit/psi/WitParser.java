package org.jetbrains.hexana.wit.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public class WitParser implements PsiParser {
    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder b) {
        PsiBuilder.Marker rootMark = b.mark();
        while (true) {
            IElementType token = b.getTokenType();
            if (token == null) {
                break;
            }
            b.advanceLexer();
        }
        rootMark.done(root);
        return b.getTreeBuilt();
    }
}
