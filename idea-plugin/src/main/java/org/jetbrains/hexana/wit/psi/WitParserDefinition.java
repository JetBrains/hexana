package org.jetbrains.hexana.wit.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public class WitParserDefinition  implements ParserDefinition {
    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new WitLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new WitParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return WitFileElementType.INSTANCE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.WHITE_SPACE;
    }

    @NotNull
    @Override
    public TokenSet getCommentTokens() {
        return WitElements.COMMENTS_SET;
    }

    @NotNull
    @Override
    public TokenSet getStringLiteralElements() {
        return TokenSet.create();
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        return new WitPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new WitPsiFile(viewProvider);
    }
}
