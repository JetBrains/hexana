package org.jetbrains.hexana.wat.psi;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class WatLexer extends LexerBase {

    private static final long initialHash = -3750763034362895579L;
    private final static boolean[] validIdChar = new boolean[127];
    private final static StringMap<WatToken> keywords = createKeywordsMap();
    static {
        for (int i = '0'; i <= '9'; i++) {
            validIdChar[i] = true;
        }
        for (int i = 'a'; i <= 'z'; i++) {
            validIdChar[i] = true;
        }
        for (int i = 'A'; i <= 'Z'; i++) {
            validIdChar[i] = true;
        }
        validIdChar['!'] = true;
        validIdChar['#'] = true;
        validIdChar['$'] = true;
        validIdChar['%'] = true;
        validIdChar['&'] = true;
        validIdChar['\''] = true;
        validIdChar['*'] = true;
        validIdChar['+'] = true;
        validIdChar['-'] = true;
        validIdChar['.'] = true;
        validIdChar['/'] = true;
        validIdChar[':'] = true;
        validIdChar['<'] = true;
        validIdChar['>'] = true;
        validIdChar['?'] = true;
        validIdChar['@'] = true;
        validIdChar['\\'] = true;
        validIdChar['^'] = true;
        validIdChar['_'] = true;
        validIdChar['`'] = true;
        validIdChar['|'] = true;
        validIdChar['~'] = true;
    }

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
            case '\t', ' ' -> readSpace();
            case '\r', '\n' -> readToken1(WatToken.NEWLINE);
            case ';' -> {
                if (!readLineComment()) {
                    readToken1(TokenType.BAD_CHARACTER);
                }
            }
            case '(' -> {
                if (!readBlockComment()) {
                    readToken1(WatToken.OPEN_PAREN);
                }
            }
            case ')' -> readToken1(WatToken.CLOSE_PAREN);
            case '-' -> readToken1(WatToken.MINUS);
            case '+' -> readToken1(WatToken.PLUS);
            case '=' -> readToken1(WatToken.EQUALS);
            case '"' -> readString();
            case '$' -> readIdentifier(WatToken.IDENTIFIER);
            case '@' -> readIdentifier(WatToken.ANNOTATION_ID);
            default -> {
                if ('a' <= c && c <= 'z') {
                    readKeyword();
                } else {
                    myTokenStart = myOffset;
                    myToken = TokenType.BAD_CHARACTER;
                    myOffset++;
                    myTokenEnd = myOffset;
                }
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
            // readChar already consumes the character it matched, so neither the exponent marker
            // nor the sign may be skipped again here -- doing so made a malformed literal such as
            // "1e)" swallow the close parenthesis and break brace matching while typing.
            readChar('-', '+');
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


    private void readIdentifier(@NotNull WatToken token) {
        myTokenStart = myOffset;
        myOffset++;
        if (getChar() == '"') {
            int tokenStart = myTokenStart;
            readString();
            myTokenStart = tokenStart;
        } else {
            readIdChars(true);
        }
        myTokenEnd = myOffset;
        myToken = token;
    }


    private void readKeyword() {
        myTokenStart = myOffset;
        long hash = readIdChars(false);
        myTokenEnd = myOffset;
        WatToken token = keywords.get(myBuffer, hash, myTokenStart, myTokenEnd - myTokenStart);
        if (token != null) {
            myToken = token;
        } else {
            // Only on a keyword-map miss, so the common path stays a single hash lookup.
            myToken = isFloatLiteral() ? WatToken.NUMBER : WatToken.UNKNOWN;
        }
    }


    /**
     * Whether the token just read by {@link #readKeyword()} is one of the textual float literals
     * {@code inf}, {@code nan}, {@code nan:0x<hex>}, {@code nan:canonical} or
     * {@code nan:arithmetic}. These are numbers rather than keywords in the text format, but they
     * begin with a letter, so they reach the keyword path. {@code :}, {@code x} and hex digits are
     * all valid identifier characters, so the whole literal has already been consumed as one token.
     *
     * <p>Without this they would be reported as {@link WatToken#UNKNOWN} and painted as bad
     * characters -- which is what {@code wasmprinter} output does contain, for example
     * {@code f32.const nan:0x400000}.
     */
    private boolean isFloatLiteral() {
        if (matches("inf")) {
            return true;
        }
        if (!startsWith("nan")) {
            return false;
        }
        int len = myTokenEnd - myTokenStart;
        if (len == 3) {
            return true; // bare "nan"
        }
        if (myBuffer.charAt(myTokenStart + 3) != ':') {
            return false;
        }
        return matches("nan:canonical") || matches("nan:arithmetic") || isNanPayload();
    }


    /** Matches {@code nan:0x<hexdigits>} with at least one hex digit. */
    private boolean isNanPayload() {
        if (!startsWith("nan:0x")) {
            return false;
        }
        int start = myTokenStart + "nan:0x".length();
        if (start >= myTokenEnd) {
            return false;
        }
        for (int i = start; i < myTokenEnd; i++) {
            char c = myBuffer.charAt(i);
            if (!isDigit(c, true) && c != '_') {
                return false;
            }
        }
        return true;
    }


    private boolean matches(@NotNull String text) {
        return myTokenEnd - myTokenStart == text.length() && startsWith(text);
    }


    private boolean startsWith(@NotNull String prefix) {
        if (myTokenEnd - myTokenStart < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (myBuffer.charAt(myTokenStart + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }


    private long readIdChars(boolean includeEq) {
        long hash = initialHash;
        while (myOffset < myEndOffset) {
            char c = myBuffer.charAt(myOffset);
            if ((c >= validIdChar.length || !validIdChar[c]) && (!includeEq || c != '=')) {
                break;
            }
            hash = hashChar(hash, c);
            myOffset++;
        }
        return hash;
    }


    private void readToken1(@NotNull IElementType token) {
        myTokenStart = myOffset;
        myOffset++;
        myTokenEnd = myOffset;
        myToken = token;
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
        myToken = WatToken.WHITESPACE;
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


    private static long hashChar(long hash, char c) {
        long result = hash ^ c;
        result *= 105127;
        return result;
    }


    private static long hashString(String s) {
        long result = initialHash;
        for (int i = 0; i < s.length(); i++) {
            result = hashChar(result, s.charAt(i));
        }
        return result;
    }

    private static StringMap<WatToken> createKeywordsMap() {
        // WatToken.ALL_KEYWORDS is the single source of truth: to teach the lexer a new keyword,
        // declare it there. sizePow2 = 11 gives 2048 slots; StringMap uses open addressing with
        // linear probing, so bump this once the keyword count approaches ~1400.
        WatToken[] allKeywords = WatToken.ALL_KEYWORDS;
        List<StringMap.Entry<WatToken>> entries = new ArrayList<>(allKeywords.length);
        for (WatToken keyword : allKeywords) {
            entries.add(createTokenEntry(keyword));
        }
        return new StringMap<>(11, entries);
    }

    private static StringMap.Entry<WatToken> createTokenEntry(@NotNull WatToken token) {
        String tokenText = token.getName();
        return new StringMap.Entry<>(token.getName(), hashString(tokenText), token);
    }
}
