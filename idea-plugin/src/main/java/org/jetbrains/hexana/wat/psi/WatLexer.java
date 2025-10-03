package org.jetbrains.hexana.wat.psi;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WatLexer extends LexerBase {

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
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readNumber();
            case '\t', ' ' -> readToken1(WatToken.WHITESPACE);
            case '\r', '\n' -> readToken1(WatToken.NEWLINE);
            case ';' -> {
                if (!readLineComment()) {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }
            case '(' -> {
                if (!readBlockComment()) {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }
            case '"' -> readString();
            default -> {
                myTokenStart = myOffset;
                myToken = TokenType.BAD_CHARACTER;
                myOffset++;
                myTokenEnd = myOffset;
            }

        }
    }

    private void readNumber() {
        myTokenStart = myOffset++;
        boolean hex = readChar('x');
        readNum(hex);
        if (readChar('.')) {
            readNum(hex);
        }
        if (hex && readChar('p', 'P') || !hex && readChar('e', 'E')) {
            myOffset++;
            if (readChar('-', '+')) {
                myOffset++;
            }
            readNum(true); // allow hex even though it is invalid, this can be shown as an inspection
        }
        myTokenEnd = myOffset;
        myToken = WatToken.NUMBER;
    }


    private void readNum(boolean hex) {
        while (myOffset < myEndOffset) {
            if (readDigit(hex)) {
                myOffset++;
            } else if (getChar() == '_' && isDigit(getChar(1), hex)) {
                myOffset += 2;
            } else {
                break;
            }
        }
    }


    private boolean readDigit(boolean hex) {
        char c = getChar();
        return isDigit(c, hex);
    }


    private boolean isDigit(char c, boolean hex) {
        return '0' <= c && c <= '9' ||
                hex && ('a' <= c && c <= 'f' || 'A' <= c && c <= 'F');
    }


    private boolean readLineComment() {
        if (getChar(1) != ';') {
            return false;
        }
        myTokenStart = myOffset;
        myOffset += 2;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (c == '\n' || c == '\r') {
                break;
            }
            myOffset++;
        }
        myTokenEnd = myOffset;
        myToken = WatToken.LINE_COMMENT;
        return true;
    }


    private boolean readBlockComment() {
        if (getChar(1) != ';') {
            return false;
        }
        myTokenStart = myOffset;
        myOffset += 2;
        int level = 1;
        while (myOffset < myEndOffset && level > 0) {
            char c = myBuffer.charAt(myOffset);
            if (c == ';' && getChar(1) == ')') {
                level--;
                myOffset += 2;
            } else if (c == '(' && getChar(1) == ';') {
                level++;
                myOffset += 2;
            } else {
                myOffset++;
            }
        }
        myTokenEnd = myOffset;
        myToken = WatToken.BLOCK_COMMENT;
        return true;
    }


    private void readString() {
        myTokenStart = myOffset;
        myOffset++;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (c < 21) {
                break;
            } else if (c == '"') {
                myOffset++;
                break;
            } else if (c == '\\') {
                // Accept everything after backslash.
                // An inspection can report invalid sequences if needed.
                myOffset += 2;
            } else {
                myOffset++;
            }
        }
        myTokenEnd = myOffset;
        myToken = WatToken.STRING;
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

    private char getChar() {
        if (myOffset < myEndOffset) {
            return myBuffer.charAt(myOffset);
        }
        return 0;
    }


    private boolean readChar(char c1, char c2) {
        if (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if (c == c1 || c == c2) {
                myOffset++;
                return true;
            }
        }
        return false;
    }

    private boolean readChar(char c) {
        if (myOffset < myEndOffset && myBuffer.charAt(myOffset) == c) {
            myOffset++;
            return true;
        }
        return false;
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
