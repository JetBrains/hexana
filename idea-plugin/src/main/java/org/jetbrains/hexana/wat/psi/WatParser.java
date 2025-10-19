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
        tokenMap.put(WatToken.OPEN_PAREN, WatElementType.SEXPR); // ()
    }

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder b) {
        PsiBuilder.Marker rootMark = b.mark();

        ArrayList<PsiBuilder.Marker> markerStack = new ArrayList<>();
        ArrayList<IElementType> firstElementStack = new ArrayList<>();
        IElementType prevToken = null;
        while (true) {
            IElementType token = b.getTokenType();
            if (token == null) {
                if (!markerStack.isEmpty()) {
                    b.error("Unexpected end of file");
                    while (!markerStack.isEmpty()) {
                        PsiBuilder.Marker m = markerStack.removeLast();
                        IElementType firstElem = firstElementStack.removeLast();
                        IElementType psiElementType = tokenMap.getOrDefault(firstElem, WatElementType.SEXPR);
                        m.done(psiElementType);
                    }
                }
                break;
            }
            if (token == WatToken.OPEN_PAREN) {
                PsiBuilder.Marker m = b.mark();
                markerStack.add(m);
                firstElementStack.add(token); // replaced with the real first element once we get it
            } else if (token == WatToken.CLOSE_PAREN) {
                if (markerStack.isEmpty()) {
                    b.error("Unexpected token");
                    b.advanceLexer();
                } else {
                    PsiBuilder.Marker m = markerStack.removeLast();
                    IElementType firstElem = firstElementStack.removeLast();
                    IElementType psiElementType = tokenMap.getOrDefault(firstElem, WatElementType.SEXPR);
                    b.advanceLexer();
                    m.done(psiElementType);
                }
                prevToken = token;
                continue;
            }

            if (prevToken == WatToken.OPEN_PAREN) {
                firstElementStack.set(firstElementStack.size() - 1, token);
            }

            prevToken = token;
            b.advanceLexer();
        }

        rootMark.done(root);
        return b.getTreeBuilt();
    }
}
