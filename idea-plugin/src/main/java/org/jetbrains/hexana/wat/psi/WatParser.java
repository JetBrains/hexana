package org.jetbrains.hexana.wat.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class WatParser implements PsiParser {

    private final static Map<IElementType, IElementType> tokenMap = new HashMap<>();
    private final static Map<IElementType, IElementType> uexpr1 = new HashMap<>();

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
        tokenMap.put(WatElementType.FUNC_BODY, WatElementType.FUNC_BODY);

        uexpr1.put(WatToken.LOCAL_GET, WatElementType.UEXPR);
        uexpr1.put(WatToken.LOCAL_SET, WatElementType.UEXPR);
        uexpr1.put(WatToken.LOCAL_TEE, WatElementType.UEXPR);
        uexpr1.put(WatToken.CALL, WatElementType.CALL);
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

        void done(boolean collapse) {
            if (collapse) {
                marker.collapse(type);
            } else {
                marker.done(type);
            }
        }

        @Override
        public String toString() {
            return type.toString();
        }
    }

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder b) {
        doParse(root, b);
        return b.getTreeBuilt();
    }

    public void doParse(@NotNull IElementType root, @NotNull PsiBuilder b) {
        PsiBuilder.Marker rootMark = b.mark();
        boolean collapseLazy = root instanceof WatFileElementType;
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

                if (next == WatToken.FUNC) {
                    b.advanceLexer(); // func
                    // Keep the function name/index outside the body to be able to check it
                    // during resolve without expanding the body.
                    idOrNum(b, b.getTokenType());
                    IElementType nextToken = b.getTokenType();
                    if (nextToken != null && nextToken != WatToken.CLOSE_PAREN) {
                        exprStack.add(new Expr(b.mark(), WatElementType.FUNC_BODY));
                        if (collapseLazy) {
                            // fast-forward to the close parenthesis of the function
                            IElementType t = b.getTokenType();
                            int openParenCount = 1;
                            while (t != null) {
                                if (t == WatToken.OPEN_PAREN) {
                                    openParenCount++;
                                } else if (t == WatToken.CLOSE_PAREN) {
                                    openParenCount--;
                                    if (openParenCount == 0) {
                                        break;
                                    }
                                }
                                b.advanceLexer();
                                t = b.getTokenType();
                            }
                        }
                    }
                }
                else if (next != WatToken.OPEN_PAREN && next != WatToken.CLOSE_PAREN) {
                    advanceLexer(b);
                }
            } else if (token == WatToken.CLOSE_PAREN) {
                if (exprStack.isEmpty()) {
                    b.error("Unexpected token");
                    b.advanceLexer();
                } else {
                    Expr expr = exprStack.removeLast();
                    if (expr.type == WatElementType.FUNC_BODY) {
                        expr.done(collapseLazy);
                        expr = exprStack.removeLast();
                    }
                    b.advanceLexer();
                    expr.done();
                }
            } else {
                IElementType unfoldedElementType = uexpr1.get(token);
                if (unfoldedElementType != null) {
                    PsiBuilder.Marker m = b.mark();
                    b.advanceLexer();
                    idOrNum(b, b.getTokenType());
                    m.done(unfoldedElementType);
                }
                else {
                    advanceLexer(b);
                }
            }
        }

        rootMark.done(root);
    }

    private static void advanceLexer(@NonNull PsiBuilder b) {
        if (!idOrNum(b, b.getTokenType())) {
            b.advanceLexer();
        }
    }

    private static boolean idOrNum(@NonNull PsiBuilder b, @Nullable IElementType token) {
        IElementType elementType;
        if (token == WatToken.IDENTIFIER) {
            elementType = WatElementType.IDENTIFIER;
        } else if (token == WatToken.NUMBER) {
            elementType = WatElementType.NUMBER;
        } else {
            return false;
        }
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        m.done(elementType);
        return true;
    }
}
