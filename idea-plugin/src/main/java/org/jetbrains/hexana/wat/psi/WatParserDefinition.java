package org.jetbrains.hexana.wat.psi;

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

public class WatParserDefinition implements ParserDefinition {
    @NotNull
    @Override
    public Lexer createLexer(Project project) {
        return new WatLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new WatParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return WatFileElementType.INSTANCE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return WatToken.WHITESPACES_SET;
    }

    @NotNull
    @Override
    public TokenSet getCommentTokens() {
        return WatToken.COMMENTS_SET;
    }

    @NotNull
    @Override
    public TokenSet getStringLiteralElements() {
        return TokenSet.create();
    }

    @NotNull
    @Override
    public PsiElement createElement(ASTNode node) {
        if (node.getElementType() == WatElementType.IDENTIFIER) {
            return new WatPsiIdentifierElement(node);
        }
        if (node.getElementType() == WatElementType.FUNC) {
            return new WatFuncElement(node);
        }
        if (node.getElementType() == WatElementType.TABLE) {
            return new WatTableElement(node);
        }
        return new WatPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new WatPsiFile(viewProvider);
    }
}
