package org.jetbrains.hexana.wit.psi;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class WitLexer extends LexerBase {

    private final static Map<String, WitToken> keywords = createKeywordsMap();

    private CharSequence myBuffer;
    private int myOffset;
    private int myEndOffset;
    private int myState;

    private IElementType myToken;
    private int myTokenStart;
    private int myTokenEnd;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        myBuffer = buffer;
        myOffset = startOffset;
        myEndOffset = endOffset;
        myState = initialState;

        advance();
    }

    @Override
    public void advance() {
        if (myOffset >= myEndOffset) {
            myToken = null;
            return;
        }
        char c = myBuffer.charAt(myOffset);
        switch (c) {
            case '\t', ' ' -> readSpace();
            case '\r', '\n' -> readToken1(TokenType.WHITE_SPACE);
            case '/' -> {
                if (!readComment()) {
                    readToken1(WitElementTypes.SLASH);
                }
            }
            case '.' -> readToken1(WitElementTypes.DOT);
            case ',' -> readToken1(WitElementTypes.COMMA);
            case ':' -> readToken1(WitElementTypes.COLON);
            case ';' -> readToken1(WitElementTypes.SEMI);
            case '<' -> readToken1(WitElementTypes.LESS_THAN);
            case '>' -> readToken1(WitElementTypes.GREATER_THAN);
            case '{' -> readToken1(WitElementTypes.OPEN_BRACE);
            case '}' -> readToken1(WitElementTypes.CLOSE_BRACE);
            case '(' -> readToken1(WitElementTypes.OPEN_PAREN);
            case ')' -> readToken1(WitElementTypes.CLOSE_PAREN);
            case '=' -> readToken1(WitElementTypes.EQUALS);
            case '@' -> readToken1(WitElementTypes.AT);
            case '_' -> readToken1(WitElementTypes.UNDERSCORE);
            case '-' -> {
                if (!readArrow()) {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }
            default -> {
                if (isDigit(c)) {
                    readNumber();
                }
                else if (c == '%' || 'a' <= c && c <= 'z' || 'A' <= c && c <= 'Z') {
                    readIdentifier();
                    if ('a' <= c && c <= 'z') {
                        IElementType keyword = keywords.get(myBuffer.subSequence(myTokenStart, myOffset).toString());
                        if (keyword != null) {
                            myToken = keyword;
                        }
                    }
                }
                else {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }

        }
    }


    private void readSpace() {
        myTokenStart = myOffset;
        myOffset++;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (c != ' ' && c != '\t') {
                break;
            }
            myOffset++;
        }
        myTokenEnd = myOffset;
        myToken = TokenType.WHITE_SPACE;
    }


    private boolean readComment() {
        char next = getChar(1);
        if (next == '/') {
            readLineComment();
            return true;
        }
        if (next == '*') {
            readBlockComment();
            return true;
        }
        return false;
    }


    private void readLineComment() {
        myTokenStart = myOffset;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (c == '\n' || c == '\r') {
                break;
            }
            myOffset++;
        }
        myTokenEnd = myOffset;
        myToken = WitElementTypes.LINE_COMMENT;
    }


    private void readBlockComment() {
        myTokenStart = myOffset;
        myOffset += 2;
        int level = 1;
        while (myOffset < myEndOffset && level > 0) {
            char c = myBuffer.charAt(myOffset);
            if (c == '*' && getChar(1) == '/') {
                level--;
                myOffset += 2;
            } else if (c == '/' && getChar(1) == '*') {
                level++;
                myOffset += 2;
            } else {
                myOffset++;
            }
        }
        myTokenEnd = myOffset;
        myToken = WitElementTypes.BLOCK_COMMENT;
    }


    private boolean readArrow() {
        if (getChar(1) == '>') {
            myTokenStart = myOffset;
            myOffset += 2;
            myTokenEnd = myOffset;
            myToken = WitElementTypes.ARROW;
            return true;
        }
        return false;
    }


    private void readIdentifier() {
        myTokenStart = myOffset;
        myOffset++;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (c == '-') {
                char next = getChar(1);
                if (isValidIdChar(next)) {
                    myOffset+=2;
                }
                else {
                    break;
                }
            }
            else if (isValidIdChar(c)) {
                myOffset++;
            }
            else {
                break;
            }
        }
        myTokenEnd = myOffset;
        myToken = WitElementTypes.IDENTIFIER;
    }

    private void readNumber() {
        myTokenStart = myOffset;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (isDigit(c)) {
                myOffset++;
            }
            else {
                break;
            }
        }
        myTokenEnd = myOffset;
        myToken = WitElementTypes.NUMBER;
    }

    private boolean isValidIdChar(char c) {
        return 'a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || isDigit(c);
    }

    private static boolean isDigit(char c) {
        return '0' <= c && c <= '9';
    }

    private void readToken1(@NotNull IElementType token) {
        myTokenStart = myOffset;
        myOffset++;
        myTokenEnd = myOffset;
        myToken = token;
    }

    private char getChar(int lookahead) {
        int offset = myOffset + lookahead;
        if (offset < myEndOffset) {
            return myBuffer.charAt(offset);
        }
        return 0;
    }

    @Override
    public int getState() {
        return myState;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return myToken;
    }

    @Override
    public int getTokenStart() {
        return myTokenStart;
    }

    @Override
    public int getTokenEnd() {
        return myTokenEnd;
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return myBuffer;
    }

    @Override
    public int getBufferEnd() {
        return myEndOffset;
    }


    private static Map<String, WitToken> createKeywordsMap() {
        Map<String, WitToken> result = new HashMap<>();
        for (IElementType token : WitElements.KEYWORDS) {
            result.put(((WitToken)token).getName(), (WitToken)token);
        }
        return result;
    }
}
