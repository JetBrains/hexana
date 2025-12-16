package org.jetbrains.hexana.wit.psi;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WitLexer extends LexerBase {

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
            case '\r', '\n' -> readToken1(WitToken.NEWLINE);
            case '/' -> {
                if (!readComment()) {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }
            case '.' -> readToken1(WitToken.DOT);
            case ',' -> readToken1(WitToken.COMMA);
            case ':' -> readToken1(WitToken.COLON);
            case ';' -> readToken1(WitToken.SEMI);
            case '<' -> readToken1(WitToken.LESS_THAN);
            case '>' -> readToken1(WitToken.GREATER_THAN);
            case '{' -> readToken1(WitToken.OPEN_BRACE);
            case '}' -> readToken1(WitToken.CLOSE_BRACE);
            case '(' -> readToken1(WitToken.OPEN_PAREN);
            case ')' -> readToken1(WitToken.CLOSE_PAREN);
            case '=' -> readToken1(WitToken.EQUALS);
            case '@' -> readToken1(WitToken.AT);
            case '_' -> readToken1(WitToken.UNDERSCORE);
            case '-' -> {
                if (!readArrow()) {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }
            default -> {
                if (c == '%' || 'a' <= c && c <= 'z' || 'A' <= c && c <= 'Z') {
                    readIdentifier();
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
        myToken = WitToken.WHITESPACE;
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
        myToken = WitToken.LINE_COMMENT;
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
        myToken = WitToken.BLOCK_COMMENT;
    }


    private boolean readArrow() {
        if (getChar(1) == '>') {
            myTokenStart = myOffset;
            myOffset += 2;
            myTokenEnd = myOffset;
            myToken = WitToken.ARROW;
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
        myToken = WitToken.IDENTIFIER;
    }

    private boolean isValidIdChar(char c) {
        return 'a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || '0' <= c && c <= '9';
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
}
