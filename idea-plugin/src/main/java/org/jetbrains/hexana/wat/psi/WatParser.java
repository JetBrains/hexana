package org.jetbrains.hexana.wat.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class WatParser implements PsiParser {

    private final static Map<IElementType, IElementType> tokenMap = new HashMap<>();

    static {
        tokenMap.put(WatToken.MODULE, WatElementType.MODULE);
        tokenMap.put(WatToken.TYPE, WatElementType.TYPE);
        tokenMap.put(WatToken.IMPORT, WatElementType.IMPORT);
        tokenMap.put(WatToken.EXPORT, WatElementType.EXPORT);
        tokenMap.put(WatToken.GLOBAL, WatElementType.GLOBAL);
        tokenMap.put(WatToken.MEMORY, WatElementType.MEMORY);
        tokenMap.put(WatToken.DATA, WatElementType.DATA);
        tokenMap.put(WatToken.TABLE, WatElementType.TABLE);
        tokenMap.put(WatToken.ELEM, WatElementType.ELEM);
        tokenMap.put(WatToken.TAG, WatElementType.TAG);
        tokenMap.put(WatToken.FUNC, WatElementType.FUNC);
        tokenMap.put(WatToken.CALL, WatElementType.CALL);
        tokenMap.put(WatToken.CALL_INDIRECT, WatElementType.CALL_INDIRECT);
        tokenMap.put(WatToken.GLOBAL_SET, WatElementType.GLOBAL_SET);
        tokenMap.put(WatToken.GLOBAL_GET, WatElementType.GLOBAL_GET);
        tokenMap.put(WatToken.THROW, WatElementType.THROW);
        tokenMap.put(WatToken.CATCH, WatElementType.CATCH);
        tokenMap.put(WatToken.LOCAL, WatElementType.LOCAL);
        tokenMap.put(WatToken.PARAM, WatElementType.PARAM);
        tokenMap.put(WatToken.BLOCK, WatElementType.BLOCK);
        tokenMap.put(WatToken.LOOP, WatElementType.LOOP);
        tokenMap.put(WatToken.IF, WatElementType.IF);
    }

    private static class Expr {
        final PsiBuilder.Marker marker;
        final IElementType type;
        private Expr(PsiBuilder.Marker marker, IElementType type) {
            this.marker = marker;
            this.type = type;
        }

        void done() {
            marker.done(type);
        }
    }

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder b) {
        PsiBuilder.Marker rootMark = b.mark();

        ArrayList<Expr> exprStack = new ArrayList<>();
        while (true) {
            IElementType token = b.getTokenType();
            if (token == null) {
                if (!exprStack.isEmpty()) {
                    b.error("Unexpected end of file");
                    while (!exprStack.isEmpty()) {
                        Expr expr = exprStack.removeLast();
                        expr.done();
                    }
                }
                break;
            }
            if (token == WatToken.OPEN_PAREN) {
                PsiBuilder.Marker m = b.mark();
                IElementType next = b.lookAhead(1);
                IElementType exprType = next != null ? tokenMap.getOrDefault(next, WatElementType.SEXPR) : WatElementType.SEXPR;
                exprStack.add(new Expr(m, exprType));
                b.advanceLexer();
            } else if (token == WatToken.CLOSE_PAREN) {
                if (exprStack.isEmpty()) {
                    b.error("Unexpected token");
                    b.advanceLexer();
                } else {
                    Expr expr = exprStack.removeLast();
                    b.advanceLexer();
                    expr.done();
                }
            } else if (token == WatToken.IDENTIFIER) {
                PsiBuilder.Marker m = b.mark();
                b.advanceLexer();
                m.done(WatElementType.IDENTIFIER);
            } else if (token == WatToken.NUMBER) {
                PsiBuilder.Marker m = b.mark();
                b.advanceLexer();
                m.done(WatElementType.NUMBER);
            } else {
                b.advanceLexer();
            }
        }

        rootMark.done(root);
        return b.getTreeBuilt();
    }
}
