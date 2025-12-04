package org.jetbrains.hexana.wat.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
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
        IElementType nodeType = node.getElementType();
        if (nodeType == WatElementType.IDENTIFIER) {
            return new WatPsiIdentifierElement(node);
        }
        if (nodeType == WatElementType.NUMBER) {
            return new WatPsiNumberElement(node);
        }
        if (nodeType == WatElementType.FUNC) {
            return new WatFuncElement(node);
        }
        if (nodeType == WatElementType.FUNC_BODY) {
            return new WatFuncBodyElement(node);
        }
        if (nodeType == WatElementType.TABLE) {
            return new WatTableElement(node);
        }
        if (nodeType == WatElementType.TYPE) {
            return new WatTypeElement(node);
        }
        if (nodeType == WatElementType.GLOBAL) {
            return new WatGlobalElement(node);
        }
        if (nodeType == WatElementType.TAG) {
            return new WatTagElement(node);
        }
        if (nodeType == WatElementType.DATA) {
            return new WatDataElement(node);
        }
        if (nodeType == WatElementType.LOCAL) {
            return new WatLocalElement(node);
        }
        if (nodeType == WatElementType.PARAM) {
            return new WatParamElement(node);
        }
        if (nodeType == WatElementType.BLOCK) {
            return new WatBlockElement(node);
        }
        if (nodeType == WatElementType.LOOP) {
            return new WatLoopElement(node);
        }
        if (nodeType == WatElementType.IF) {
            return new WatIfElement(node);
        }
        if (nodeType == WatElementType.MODULE) {
            return new WatModuleElement(node);
        }
        if (nodeType == WatElementType.IMPORT) {
            return new WatImportElement(node);
        }
        return new WatPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new WatPsiFile(viewProvider);
    }
}
