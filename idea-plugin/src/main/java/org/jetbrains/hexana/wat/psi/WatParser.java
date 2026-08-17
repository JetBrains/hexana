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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WatParser implements PsiParser {

    private final static Map<IElementType, IElementType> tokenMap = new HashMap<>();
    /**
     * Element types for the sort keyword that follows {@code core} in a component. The core sorts
     * get the same element types as their core-module counterparts; whether an element belongs to a
     * core or a component index space is read back off the {@code core} keyword itself.
     */
    private final static Map<IElementType, IElementType> coreTokenMap = new HashMap<>();
    private final static Map<IElementType, IElementType> uexpr1 = new HashMap<>();
    private final static Map<IElementType, IElementType> blocks = new HashMap<>();
    /** Unfolded instructions followed by a variable number of ids/indices. */
    private final static Set<IElementType> uexprN = new HashSet<>();

    static {
        tokenMap.put(WatToken.MODULE, WatElementType.MODULE);
        tokenMap.put(WatToken.COMPONENT, WatElementType.COMPONENT);
        tokenMap.put(WatToken.TYPE, WatElementType.TYPE);
        tokenMap.put(WatToken.IMPORT, WatElementType.IMPORT);
        tokenMap.put(WatToken.INSTANCE, WatElementType.INSTANCE);
        tokenMap.put(WatToken.ALIAS, WatElementType.ALIAS);
        tokenMap.put(WatToken.VALUE, WatElementType.VALUE);
        tokenMap.put(WatToken.ANNOTATION_ID, WatElementType.ANNOTATION);
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
        tokenMap.put(WatToken.THEN, WatElementType.THEN);
        tokenMap.put(WatToken.ELSE, WatElementType.ELSE);
        tokenMap.put(WatToken.TRY_TABLE, WatElementType.TRY_TABLE);
        tokenMap.put(WatToken.TRY, WatElementType.TRY);
        tokenMap.put(WatToken.DO, WatElementType.DO);
        tokenMap.put(WatElementType.FUNC_BODY, WatElementType.FUNC_BODY);

        coreTokenMap.put(WatToken.MODULE, WatElementType.MODULE);
        coreTokenMap.put(WatToken.INSTANCE, WatElementType.INSTANCE);
        coreTokenMap.put(WatToken.TYPE, WatElementType.TYPE);
        coreTokenMap.put(WatToken.FUNC, WatElementType.FUNC);
        coreTokenMap.put(WatToken.TABLE, WatElementType.TABLE);
        coreTokenMap.put(WatToken.MEMORY, WatElementType.MEMORY);
        coreTokenMap.put(WatToken.GLOBAL, WatElementType.GLOBAL);
        coreTokenMap.put(WatToken.TAG, WatElementType.TAG);

        uexpr1.put(WatToken.LOCAL_GET, WatElementType.UEXPR);
        uexpr1.put(WatToken.LOCAL_SET, WatElementType.UEXPR);
        uexpr1.put(WatToken.LOCAL_TEE, WatElementType.UEXPR);
        uexpr1.put(WatToken.CALL, WatElementType.CALL);
        uexpr1.put(WatToken.GLOBAL_GET, WatElementType.GLOBAL_GET);
        uexpr1.put(WatToken.GLOBAL_SET, WatElementType.GLOBAL_SET);
        uexpr1.put(WatToken.THROW, WatElementType.THROW);
        uexpr1.put(WatToken.CATCH, WatElementType.CATCH);
        uexpr1.put(WatToken.BR, WatElementType.UEXPR);
        uexpr1.put(WatToken.BR_IF, WatElementType.UEXPR);
        uexpr1.put(WatToken.BR_ON_NULL, WatElementType.UEXPR);
        uexpr1.put(WatToken.BR_ON_NON_NULL, WatElementType.UEXPR);
        // Legacy exception handling: `rethrow <label>`.
        uexpr1.put(WatToken.RETHROW, WatElementType.UEXPR);
        // Stack switching: `suspend <tag>`, `cont.new <type>`, `resume <type>`.
        uexpr1.put(WatToken.SUSPEND, WatElementType.UEXPR);
        uexpr1.put(WatToken.CONT_NEW, WatElementType.UEXPR);
        uexpr1.put(WatToken.RESUME, WatElementType.UEXPR);
        uexpr1.put(WatToken.RESUME_THROW_REF, WatElementType.UEXPR);

        blocks.put(WatToken.BLOCK, WatElementType.BLOCK);
        blocks.put(WatToken.LOOP, WatElementType.LOOP);
        blocks.put(WatToken.IF, WatElementType.IF);
        // `try_table` and legacy `try` are block instructions terminated by `end`, so they must
        // push a marker; otherwise their `end` closes the enclosing block instead.
        blocks.put(WatToken.TRY_TABLE, WatElementType.TRY_TABLE);
        blocks.put(WatToken.TRY, WatElementType.TRY);

        // Instructions taking two indices, e.g. `cont.bind $ct1 $ct2`.
        uexprN.add(WatToken.BR_TABLE);
        uexprN.add(WatToken.CONT_BIND);
        uexprN.add(WatToken.SWITCH);
        uexprN.add(WatToken.RESUME_THROW);
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
        ArrayList<Expr> unfoldedBlockStack = new ArrayList<>();
        ArrayList<Integer> unfoldedBlockStackSizes = new ArrayList<>();
        while (true) {
            IElementType token = b.getTokenType();
            if (token == null) {
                if (!exprStack.isEmpty() || !unfoldedBlockStack.isEmpty()) {
                    b.error("Unexpected end of file");
                    while (!unfoldedBlockStack.isEmpty()) {
                        unfoldedBlockStack.removeLast().done();
                    }
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
                IElementType exprType = elementTypeFor(next, next == WatToken.CORE ? b.lookAhead(2) : null);
                exprStack.add(new Expr(m, exprType));
                unfoldedBlockStackSizes.add(unfoldedBlockStack.size());
                b.advanceLexer();

                if (next == WatToken.FUNC) {
                    b.advanceLexer(); // func
                    // Keep the function name/index outside the body to be able to check it
                    // during resolve without expanding the body.
                    idOrNum(b, b.getTokenType());
                    IElementType nextToken = b.getTokenType();
                    if (nextToken != null && nextToken != WatToken.CLOSE_PAREN) {
                        exprStack.add(new Expr(b.mark(), WatElementType.FUNC_BODY));
                        unfoldedBlockStackSizes.add(unfoldedBlockStack.size());
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
                    // Close any unfolded block markers that were opened within this s-expression
                    int savedSize = unfoldedBlockStackSizes.removeLast();
                    while (unfoldedBlockStack.size() > savedSize) {
                        unfoldedBlockStack.removeLast().done();
                    }
                    if (expr.type == WatElementType.FUNC_BODY) {
                        expr.done(collapseLazy);
                        expr = exprStack.removeLast();
                        savedSize = unfoldedBlockStackSizes.removeLast();
                        while (unfoldedBlockStack.size() > savedSize) {
                            unfoldedBlockStack.removeLast().done();
                        }
                    }
                    b.advanceLexer();
                    expr.done();
                }
            } else if (token == WatToken.ELSE) {
                if (getLastTypeOrNull(unfoldedBlockStack) == WatElementType.THEN) {
                    unfoldedBlockStack.removeLast().done();
                }
                unfoldedBlockStack.add(new Expr(b.mark(), WatElementType.ELSE));
                b.advanceLexer();
            } else if (token == WatToken.END) {
                if (unfoldedBlockStack.isEmpty()) {
                    b.advanceLexer();
                } else {
                    Expr expr = unfoldedBlockStack.removeLast();
                    b.advanceLexer();
                    expr.done();
                    if ((expr.type == WatElementType.THEN || expr.type == WatElementType.ELSE) && getLastTypeOrNull(unfoldedBlockStack) == WatElementType.IF) {
                        unfoldedBlockStack.removeLast().done();
                    }
                }
            } else {
                IElementType unfoldedElementType = uexpr1.get(token);
                if (unfoldedElementType != null) {
                    PsiBuilder.Marker m = b.mark();
                    b.advanceLexer();
                    idOrNum(b, b.getTokenType());
                    m.done(unfoldedElementType);
                }
                else if (uexprN.contains(token)) {
                    PsiBuilder.Marker m = b.mark();
                    b.advanceLexer();
                    // Greedy, which is safe: the token after the operands is always `(`, `)` or a
                    // keyword, and idOrNum() reports false for all of those.
                    while (true) {
                        if (!idOrNum(b, b.getTokenType())) {
                            break;
                        }
                    }
                    m.done(WatElementType.UEXPR);
                }
                else if (token == WatToken.DELEGATE) {
                    // `delegate <label>` terminates a legacy unfolded `try` in place of `end`.
                    PsiBuilder.Marker m = b.mark();
                    b.advanceLexer();
                    idOrNum(b, b.getTokenType());
                    m.done(WatElementType.UEXPR);
                    if (getLastTypeOrNull(unfoldedBlockStack) == WatElementType.TRY) {
                        unfoldedBlockStack.removeLast().done();
                    }
                }
                else if (token == WatToken.BR_ON_CAST || token == WatToken.BR_ON_CAST_FAIL) {
                    PsiBuilder.Marker m = b.mark();
                    b.advanceLexer();
                    idOrNum(b, b.getTokenType());
                    readRefType(b);
                    readRefType(b);
                    m.done(WatElementType.UEXPR);
                }
                else {
                    IElementType blockElementType = blocks.get(token);
                    if (blockElementType != null) {
                        unfoldedBlockStack.add(new Expr(b.mark(), blockElementType));
                        b.advanceLexer();
                        if (token == WatToken.IF) {
                            idOrNum(b, b.getTokenType());
                            readResult(b);
                            unfoldedBlockStack.add(new Expr(b.mark(), WatElementType.THEN));
                        }
                    } else {
                        advanceLexer(b);
                    }
                }
            }
        }

        rootMark.done(root);
    }

    /**
     * @param head the token right after the open parenthesis
     * @param coreSort the sort keyword after {@code core}, or null when {@code head} is not
     *   {@code core}
     */
    private static @NotNull IElementType elementTypeFor(@Nullable IElementType head, @Nullable IElementType coreSort) {
        if (head == WatToken.CORE) {
            return coreSort != null ? coreTokenMap.getOrDefault(coreSort, WatElementType.SEXPR) : WatElementType.SEXPR;
        }
        return head != null ? tokenMap.getOrDefault(head, WatElementType.SEXPR) : WatElementType.SEXPR;
    }

    private static @Nullable IElementType getLastTypeOrNull(@NotNull ArrayList<Expr> list) {
        return list.isEmpty() ? null : list.getLast().type;
    }

    private static void readResult(@NonNull PsiBuilder b) {
        if (b.getTokenType() == WatToken.OPEN_PAREN && b.lookAhead(1) == WatToken.RESULT) {
            readBalancedSExpr(b);
        }
    }

    private static void readRefType(@NotNull PsiBuilder b) {
        IElementType token = b.getTokenType();
        if (token == WatToken.ANYREF || token == WatToken.EQREF ||
                token == WatToken.I31REF || token == WatToken.STRUCTREF ||
                token == WatToken.ARRAYREF || token == WatToken.FUNCREF ||
                token == WatToken.EXNREF || token == WatToken.EXTERNREF ||
                token == WatToken.NULLREF || token == WatToken.NULLFUNCREF ||
                token == WatToken.NULLEXNREF || token == WatToken.NULLEXTERNREF) {
            b.advanceLexer();
            return;
        }
        if (token == WatToken.OPEN_PAREN && b.lookAhead(1) == WatToken.REF) {
            readBalancedSExpr(b);
        }
    }

    private static void readBalancedSExpr(@NonNull PsiBuilder b) {
        PsiBuilder.Marker mark = b.mark();
        b.advanceLexer(); // (
        int openParenCount = 1;
        while (openParenCount > 0) {
            IElementType token = b.getTokenType();
            if (token == null) {
                break;
            }
            else if (token == WatToken.OPEN_PAREN) {
                openParenCount++;
            }
            else if (token == WatToken.CLOSE_PAREN) {
                openParenCount--;
            }
            advanceLexer(b);
        }
        mark.done(WatElementType.SEXPR);
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
