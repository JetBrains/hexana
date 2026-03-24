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
        myToken = token != null ? token : WatToken.UNKNOWN;
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
        List<StringMap.Entry<WatToken>> entries = new ArrayList<>();
        entries.add(createTokenEntry(WatToken.ACTIVE));
        entries.add(createTokenEntry(WatToken.ALIAS));
        entries.add(createTokenEntry(WatToken.ALIGN));
        entries.add(createTokenEntry(WatToken.ANY));
        entries.add(createTokenEntry(WatToken.ANY_CONVERT_EXTERN));
        entries.add(createTokenEntry(WatToken.ANYREF));
        entries.add(createTokenEntry(WatToken.ARRAY));
        entries.add(createTokenEntry(WatToken.ARRAY_COPY));
        entries.add(createTokenEntry(WatToken.ARRAY_FILL));
        entries.add(createTokenEntry(WatToken.ARRAY_GET));
        entries.add(createTokenEntry(WatToken.ARRAY_GET_S));
        entries.add(createTokenEntry(WatToken.ARRAY_GET_U));
        entries.add(createTokenEntry(WatToken.ARRAY_INIT_DATA));
        entries.add(createTokenEntry(WatToken.ARRAY_INIT_ELEM));
        entries.add(createTokenEntry(WatToken.ARRAY_LEN));
        entries.add(createTokenEntry(WatToken.ARRAY_NEW));
        entries.add(createTokenEntry(WatToken.ARRAY_NEW_DATA));
        entries.add(createTokenEntry(WatToken.ARRAY_NEW_DEFAULT));
        entries.add(createTokenEntry(WatToken.ARRAY_NEW_ELEM));
        entries.add(createTokenEntry(WatToken.ARRAY_NEW_FIXED));
        entries.add(createTokenEntry(WatToken.ARRAY_SET));
        entries.add(createTokenEntry(WatToken.ARRAYREF));
        entries.add(createTokenEntry(WatToken.BLOCK));
        entries.add(createTokenEntry(WatToken.BR));
        entries.add(createTokenEntry(WatToken.BR_IF));
        entries.add(createTokenEntry(WatToken.BR_ON_CAST));
        entries.add(createTokenEntry(WatToken.BR_ON_CAST_FAIL));
        entries.add(createTokenEntry(WatToken.BR_ON_NON_NULL));
        entries.add(createTokenEntry(WatToken.BR_ON_NULL));
        entries.add(createTokenEntry(WatToken.BR_TABLE));
        entries.add(createTokenEntry(WatToken.CALL));
        entries.add(createTokenEntry(WatToken.CALL_INDIRECT));
        entries.add(createTokenEntry(WatToken.CALL_REF));
        entries.add(createTokenEntry(WatToken.CANON));
        entries.add(createTokenEntry(WatToken.CATCH));
        entries.add(createTokenEntry(WatToken.CATCH_ALL));
        entries.add(createTokenEntry(WatToken.CATCH_ALL_REF));
        entries.add(createTokenEntry(WatToken.CATCH_REF));
        entries.add(createTokenEntry(WatToken.CORE));
        entries.add(createTokenEntry(WatToken.COMPONENT));
        entries.add(createTokenEntry(WatToken.INSTANCE));
        entries.add(createTokenEntry(WatToken.INSTANTIATE));
        entries.add(createTokenEntry(WatToken.DATA));
        entries.add(createTokenEntry(WatToken.DATA_DROP));
        entries.add(createTokenEntry(WatToken.DECLARE));
        entries.add(createTokenEntry(WatToken.DROP));
        entries.add(createTokenEntry(WatToken.ELEM));
        entries.add(createTokenEntry(WatToken.ELEM_DROP));
        entries.add(createTokenEntry(WatToken.ELSE));
        entries.add(createTokenEntry(WatToken.END));
        entries.add(createTokenEntry(WatToken.EQ));
        entries.add(createTokenEntry(WatToken.EQREF));
        entries.add(createTokenEntry(WatToken.EXN));
        entries.add(createTokenEntry(WatToken.EXNREF));
        entries.add(createTokenEntry(WatToken.EXPORT));
        entries.add(createTokenEntry(WatToken.EXTERN));
        entries.add(createTokenEntry(WatToken.EXTERN_CONVERT_ANY));
        entries.add(createTokenEntry(WatToken.EXTERNREF));
        entries.add(createTokenEntry(WatToken.F32));
        entries.add(createTokenEntry(WatToken.F32_ABS));
        entries.add(createTokenEntry(WatToken.F32_ADD));
        entries.add(createTokenEntry(WatToken.F32_CEIL));
        entries.add(createTokenEntry(WatToken.F32_CONST));
        entries.add(createTokenEntry(WatToken.F32_CONVERT_I32_S));
        entries.add(createTokenEntry(WatToken.F32_CONVERT_I32_U));
        entries.add(createTokenEntry(WatToken.F32_CONVERT_I64_S));
        entries.add(createTokenEntry(WatToken.F32_CONVERT_I64_U));
        entries.add(createTokenEntry(WatToken.F32_COPYSIGN));
        entries.add(createTokenEntry(WatToken.F32_DEMOTE_F64));
        entries.add(createTokenEntry(WatToken.F32_DIV));
        entries.add(createTokenEntry(WatToken.F32_EQ));
        entries.add(createTokenEntry(WatToken.F32_FLOOR));
        entries.add(createTokenEntry(WatToken.F32_GE));
        entries.add(createTokenEntry(WatToken.F32_GT));
        entries.add(createTokenEntry(WatToken.F32_LE));
        entries.add(createTokenEntry(WatToken.F32_LOAD));
        entries.add(createTokenEntry(WatToken.F32_LT));
        entries.add(createTokenEntry(WatToken.F32_MAX));
        entries.add(createTokenEntry(WatToken.F32_MIN));
        entries.add(createTokenEntry(WatToken.F32_MUL));
        entries.add(createTokenEntry(WatToken.F32_NE));
        entries.add(createTokenEntry(WatToken.F32_NEAREST));
        entries.add(createTokenEntry(WatToken.F32_NEG));
        entries.add(createTokenEntry(WatToken.F32_REINTERPRET_I32));
        entries.add(createTokenEntry(WatToken.F32_SQRT));
        entries.add(createTokenEntry(WatToken.F32_STORE));
        entries.add(createTokenEntry(WatToken.F32_SUB));
        entries.add(createTokenEntry(WatToken.F32_TRUNC));
        entries.add(createTokenEntry(WatToken.F32X4_ABS));
        entries.add(createTokenEntry(WatToken.F32X4_ADD));
        entries.add(createTokenEntry(WatToken.F32X4_CEIL));
        entries.add(createTokenEntry(WatToken.F32X4_CONVERT_S_I32X4));
        entries.add(createTokenEntry(WatToken.F32X4_CONVERT_U_I32X4));
        entries.add(createTokenEntry(WatToken.F32X4_DEMOTE_ZERO_F64X2));
        entries.add(createTokenEntry(WatToken.F32X4_DIV));
        entries.add(createTokenEntry(WatToken.F32X4_EQ));
        entries.add(createTokenEntry(WatToken.F32X4_EXTRACT_LANE));
        entries.add(createTokenEntry(WatToken.F32X4_FLOOR));
        entries.add(createTokenEntry(WatToken.F32X4_GE));
        entries.add(createTokenEntry(WatToken.F32X4_GT));
        entries.add(createTokenEntry(WatToken.F32X4_LE));
        entries.add(createTokenEntry(WatToken.F32X4_LT));
        entries.add(createTokenEntry(WatToken.F32X4_MAX));
        entries.add(createTokenEntry(WatToken.F32X4_MIN));
        entries.add(createTokenEntry(WatToken.F32X4_MUL));
        entries.add(createTokenEntry(WatToken.F32X4_NE));
        entries.add(createTokenEntry(WatToken.F32X4_NEAREST));
        entries.add(createTokenEntry(WatToken.F32X4_NEG));
        entries.add(createTokenEntry(WatToken.F32X4_PMAX));
        entries.add(createTokenEntry(WatToken.F32X4_PMIN));
        entries.add(createTokenEntry(WatToken.F32X4_RELAXED_MADD));
        entries.add(createTokenEntry(WatToken.F32X4_RELAXED_MAX));
        entries.add(createTokenEntry(WatToken.F32X4_RELAXED_MIN));
        entries.add(createTokenEntry(WatToken.F32X4_RELAXED_NMADD));
        entries.add(createTokenEntry(WatToken.F32X4_REPLACE_LANE));
        entries.add(createTokenEntry(WatToken.F32X4_SPLAT));
        entries.add(createTokenEntry(WatToken.F32X4_SQRT));
        entries.add(createTokenEntry(WatToken.F32X4_SUB));
        entries.add(createTokenEntry(WatToken.F32X4_TRUNC));
        entries.add(createTokenEntry(WatToken.F64));
        entries.add(createTokenEntry(WatToken.F64_ABS));
        entries.add(createTokenEntry(WatToken.F64_ADD));
        entries.add(createTokenEntry(WatToken.F64_CEIL));
        entries.add(createTokenEntry(WatToken.F64_CONST));
        entries.add(createTokenEntry(WatToken.F64_CONVERT_I32_S));
        entries.add(createTokenEntry(WatToken.F64_CONVERT_I32_U));
        entries.add(createTokenEntry(WatToken.F64_CONVERT_I64_S));
        entries.add(createTokenEntry(WatToken.F64_CONVERT_I64_U));
        entries.add(createTokenEntry(WatToken.F64_COPYSIGN));
        entries.add(createTokenEntry(WatToken.F64_DIV));
        entries.add(createTokenEntry(WatToken.F64_EQ));
        entries.add(createTokenEntry(WatToken.F64_FLOOR));
        entries.add(createTokenEntry(WatToken.F64_GE));
        entries.add(createTokenEntry(WatToken.F64_GT));
        entries.add(createTokenEntry(WatToken.F64_LE));
        entries.add(createTokenEntry(WatToken.F64_LOAD));
        entries.add(createTokenEntry(WatToken.F64_LT));
        entries.add(createTokenEntry(WatToken.F64_MAX));
        entries.add(createTokenEntry(WatToken.F64_MIN));
        entries.add(createTokenEntry(WatToken.F64_MUL));
        entries.add(createTokenEntry(WatToken.F64_NE));
        entries.add(createTokenEntry(WatToken.F64_NEAREST));
        entries.add(createTokenEntry(WatToken.F64_NEG));
        entries.add(createTokenEntry(WatToken.F64_PROMOTE_F32));
        entries.add(createTokenEntry(WatToken.F64_REINTERPRET_I64));
        entries.add(createTokenEntry(WatToken.F64_SQRT));
        entries.add(createTokenEntry(WatToken.F64_STORE));
        entries.add(createTokenEntry(WatToken.F64_SUB));
        entries.add(createTokenEntry(WatToken.F64_TRUNC));
        entries.add(createTokenEntry(WatToken.F64X2_ABS));
        entries.add(createTokenEntry(WatToken.F64X2_ADD));
        entries.add(createTokenEntry(WatToken.F64X2_CEIL));
        entries.add(createTokenEntry(WatToken.F64X2_CONVERT_LOW_S_I32X4));
        entries.add(createTokenEntry(WatToken.F64X2_CONVERT_LOW_U_I32X4));
        entries.add(createTokenEntry(WatToken.F64X2_DIV));
        entries.add(createTokenEntry(WatToken.F64X2_EQ));
        entries.add(createTokenEntry(WatToken.F64X2_EXTRACT_LANE));
        entries.add(createTokenEntry(WatToken.F64X2_FLOOR));
        entries.add(createTokenEntry(WatToken.F64X2_GE));
        entries.add(createTokenEntry(WatToken.F64X2_GT));
        entries.add(createTokenEntry(WatToken.F64X2_LE));
        entries.add(createTokenEntry(WatToken.F64X2_LT));
        entries.add(createTokenEntry(WatToken.F64X2_MAX));
        entries.add(createTokenEntry(WatToken.F64X2_MIN));
        entries.add(createTokenEntry(WatToken.F64X2_MUL));
        entries.add(createTokenEntry(WatToken.F64X2_NE));
        entries.add(createTokenEntry(WatToken.F64X2_NEAREST));
        entries.add(createTokenEntry(WatToken.F64X2_NEG));
        entries.add(createTokenEntry(WatToken.F64X2_PMAX));
        entries.add(createTokenEntry(WatToken.F64X2_PMIN));
        entries.add(createTokenEntry(WatToken.F64X2_PROMOTE_LOW_F32X4));
        entries.add(createTokenEntry(WatToken.F64X2_RELAXED_MADD));
        entries.add(createTokenEntry(WatToken.F64X2_RELAXED_MAX));
        entries.add(createTokenEntry(WatToken.F64X2_RELAXED_MIN));
        entries.add(createTokenEntry(WatToken.F64X2_RELAXED_NMADD));
        entries.add(createTokenEntry(WatToken.F64X2_REPLACE_LANE));
        entries.add(createTokenEntry(WatToken.F64X2_SPLAT));
        entries.add(createTokenEntry(WatToken.F64X2_SQRT));
        entries.add(createTokenEntry(WatToken.F64X2_SUB));
        entries.add(createTokenEntry(WatToken.F64X2_TRUNC));
        entries.add(createTokenEntry(WatToken.FIELD));
        entries.add(createTokenEntry(WatToken.FINAL));
        entries.add(createTokenEntry(WatToken.FUNC));
        entries.add(createTokenEntry(WatToken.FUNCREF));
        entries.add(createTokenEntry(WatToken.GLOBAL));
        entries.add(createTokenEntry(WatToken.GLOBAL_GET));
        entries.add(createTokenEntry(WatToken.GLOBAL_SET));
        entries.add(createTokenEntry(WatToken.I16));
        entries.add(createTokenEntry(WatToken.I16X8_ABS));
        entries.add(createTokenEntry(WatToken.I16X8_ADD));
        entries.add(createTokenEntry(WatToken.I16X8_ADD_SAT_S));
        entries.add(createTokenEntry(WatToken.I16X8_ADD_SAT_U));
        entries.add(createTokenEntry(WatToken.I16X8_ALL_TRUE));
        entries.add(createTokenEntry(WatToken.I16X8_AVGR_U));
        entries.add(createTokenEntry(WatToken.I16X8_BITMASK));
        entries.add(createTokenEntry(WatToken.I16X8_EQ));
        entries.add(createTokenEntry(WatToken.I16X8_EXTADD_PAIRWISE_I8X16_S));
        entries.add(createTokenEntry(WatToken.I16X8_EXTADD_PAIRWISE_I8X16_U));
        entries.add(createTokenEntry(WatToken.I16X8_EXTEND_HIGH_S_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTEND_HIGH_U_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTEND_LOW_S_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTEND_LOW_U_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTMUL_HIGH_S_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTMUL_HIGH_U_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTMUL_LOW_S_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTMUL_LOW_U_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_EXTRACT_LANE_S));
        entries.add(createTokenEntry(WatToken.I16X8_EXTRACT_LANE_U));
        entries.add(createTokenEntry(WatToken.I16X8_GE_S));
        entries.add(createTokenEntry(WatToken.I16X8_GE_U));
        entries.add(createTokenEntry(WatToken.I16X8_GT_S));
        entries.add(createTokenEntry(WatToken.I16X8_GT_U));
        entries.add(createTokenEntry(WatToken.I16X8_LE_S));
        entries.add(createTokenEntry(WatToken.I16X8_LE_U));
        entries.add(createTokenEntry(WatToken.I16X8_LT_S));
        entries.add(createTokenEntry(WatToken.I16X8_LT_U));
        entries.add(createTokenEntry(WatToken.I16X8_MAX_S));
        entries.add(createTokenEntry(WatToken.I16X8_MAX_U));
        entries.add(createTokenEntry(WatToken.I16X8_MIN_S));
        entries.add(createTokenEntry(WatToken.I16X8_MIN_U));
        entries.add(createTokenEntry(WatToken.I16X8_MUL));
        entries.add(createTokenEntry(WatToken.I16X8_NARROW_I32X4_S));
        entries.add(createTokenEntry(WatToken.I16X8_NARROW_I32X4_U));
        entries.add(createTokenEntry(WatToken.I16X8_NE));
        entries.add(createTokenEntry(WatToken.I16X8_NEG));
        entries.add(createTokenEntry(WatToken.I16X8_Q15MULR_SAT_S));
        entries.add(createTokenEntry(WatToken.I16X8_RELAXED_DOT_I8X16_I7X16_ADD_S));
        entries.add(createTokenEntry(WatToken.I16X8_RELAXED_DOT_I8X16_I7X16_S));
        entries.add(createTokenEntry(WatToken.I16X8_RELAXED_DOT_S_I8X16));
        entries.add(createTokenEntry(WatToken.I16X8_RELAXED_LANESELECT));
        entries.add(createTokenEntry(WatToken.I16X8_RELAXED_Q15MULR_S));
        entries.add(createTokenEntry(WatToken.I16X8_RELAXED_SWIZZLE));
        entries.add(createTokenEntry(WatToken.I16X8_REPLACE_LANE));
        entries.add(createTokenEntry(WatToken.I16X8_SHL));
        entries.add(createTokenEntry(WatToken.I16X8_SHR_S));
        entries.add(createTokenEntry(WatToken.I16X8_SHR_U));
        entries.add(createTokenEntry(WatToken.I16X8_SPLAT));
        entries.add(createTokenEntry(WatToken.I16X8_SUB));
        entries.add(createTokenEntry(WatToken.I16X8_SUB_SAT_S));
        entries.add(createTokenEntry(WatToken.I16X8_SUB_SAT_U));
        entries.add(createTokenEntry(WatToken.I31));
        entries.add(createTokenEntry(WatToken.I31_GET_S));
        entries.add(createTokenEntry(WatToken.I31_GET_U));
        entries.add(createTokenEntry(WatToken.I31REF));
        entries.add(createTokenEntry(WatToken.I32));
        entries.add(createTokenEntry(WatToken.I32_ADD));
        entries.add(createTokenEntry(WatToken.I32_AND));
        entries.add(createTokenEntry(WatToken.I32_CLZ));
        entries.add(createTokenEntry(WatToken.I32_CONST));
        entries.add(createTokenEntry(WatToken.I32_CTZ));
        entries.add(createTokenEntry(WatToken.I32_DIV_S));
        entries.add(createTokenEntry(WatToken.I32_DIV_U));
        entries.add(createTokenEntry(WatToken.I32_EQ));
        entries.add(createTokenEntry(WatToken.I32_EQZ));
        entries.add(createTokenEntry(WatToken.I32_EXTEND16_S));
        entries.add(createTokenEntry(WatToken.I32_EXTEND8_S));
        entries.add(createTokenEntry(WatToken.I32_GE_S));
        entries.add(createTokenEntry(WatToken.I32_GE_U));
        entries.add(createTokenEntry(WatToken.I32_GT_S));
        entries.add(createTokenEntry(WatToken.I32_GT_U));
        entries.add(createTokenEntry(WatToken.I32_LE_S));
        entries.add(createTokenEntry(WatToken.I32_LE_U));
        entries.add(createTokenEntry(WatToken.I32_LOAD));
        entries.add(createTokenEntry(WatToken.I32_LOAD16_S));
        entries.add(createTokenEntry(WatToken.I32_LOAD16_U));
        entries.add(createTokenEntry(WatToken.I32_LOAD8_S));
        entries.add(createTokenEntry(WatToken.I32_LOAD8_U));
        entries.add(createTokenEntry(WatToken.I32_LT_S));
        entries.add(createTokenEntry(WatToken.I32_LT_U));
        entries.add(createTokenEntry(WatToken.I32_MUL));
        entries.add(createTokenEntry(WatToken.I32_NE));
        entries.add(createTokenEntry(WatToken.I32_OR));
        entries.add(createTokenEntry(WatToken.I32_POPCNT));
        entries.add(createTokenEntry(WatToken.I32_REINTERPRET_F32));
        entries.add(createTokenEntry(WatToken.I32_REM_S));
        entries.add(createTokenEntry(WatToken.I32_REM_U));
        entries.add(createTokenEntry(WatToken.I32_ROTL));
        entries.add(createTokenEntry(WatToken.I32_ROTR));
        entries.add(createTokenEntry(WatToken.I32_SHL));
        entries.add(createTokenEntry(WatToken.I32_SHR_S));
        entries.add(createTokenEntry(WatToken.I32_SHR_U));
        entries.add(createTokenEntry(WatToken.I32_STORE));
        entries.add(createTokenEntry(WatToken.I32_STORE16));
        entries.add(createTokenEntry(WatToken.I32_STORE8));
        entries.add(createTokenEntry(WatToken.I32_SUB));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_F32_S));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_F32_U));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_F64_S));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_F64_U));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_SAT_F32_S));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_SAT_F32_U));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_SAT_F64_S));
        entries.add(createTokenEntry(WatToken.I32_TRUNC_SAT_F64_U));
        entries.add(createTokenEntry(WatToken.I32_WRAP_I64));
        entries.add(createTokenEntry(WatToken.I32_XOR));
        entries.add(createTokenEntry(WatToken.I32X4_ABS));
        entries.add(createTokenEntry(WatToken.I32X4_ADD));
        entries.add(createTokenEntry(WatToken.I32X4_ALL_TRUE));
        entries.add(createTokenEntry(WatToken.I32X4_BITMASK));
        entries.add(createTokenEntry(WatToken.I32X4_DOT_S_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EQ));
        entries.add(createTokenEntry(WatToken.I32X4_EXTADD_PAIRWISE_I16X8_S));
        entries.add(createTokenEntry(WatToken.I32X4_EXTADD_PAIRWISE_I16X8_U));
        entries.add(createTokenEntry(WatToken.I32X4_EXTEND_HIGH_S_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTEND_HIGH_U_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTEND_LOW_S_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTEND_LOW_U_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTMUL_HIGH_S_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTMUL_HIGH_U_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTMUL_LOW_S_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTMUL_LOW_U_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_EXTRACT_LANE));
        entries.add(createTokenEntry(WatToken.I32X4_GE_S));
        entries.add(createTokenEntry(WatToken.I32X4_GE_U));
        entries.add(createTokenEntry(WatToken.I32X4_GT_S));
        entries.add(createTokenEntry(WatToken.I32X4_GT_U));
        entries.add(createTokenEntry(WatToken.I32X4_LE_S));
        entries.add(createTokenEntry(WatToken.I32X4_LE_U));
        entries.add(createTokenEntry(WatToken.I32X4_LT_S));
        entries.add(createTokenEntry(WatToken.I32X4_LT_U));
        entries.add(createTokenEntry(WatToken.I32X4_MAX_S));
        entries.add(createTokenEntry(WatToken.I32X4_MAX_U));
        entries.add(createTokenEntry(WatToken.I32X4_MIN_S));
        entries.add(createTokenEntry(WatToken.I32X4_MIN_U));
        entries.add(createTokenEntry(WatToken.I32X4_MUL));
        entries.add(createTokenEntry(WatToken.I32X4_NE));
        entries.add(createTokenEntry(WatToken.I32X4_NEG));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_DOT_ADD_S_I16X8));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_LANESELECT));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_F32X4_S));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_F32X4_S_ZERO));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_F32X4_U));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_F32X4_U_ZERO));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_S_F32X4));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_S_ZERO_F64X2));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_U_F32X4));
        entries.add(createTokenEntry(WatToken.I32X4_RELAXED_TRUNC_U_ZERO_F64X2));
        entries.add(createTokenEntry(WatToken.I32X4_REPLACE_LANE));
        entries.add(createTokenEntry(WatToken.I32X4_SHL));
        entries.add(createTokenEntry(WatToken.I32X4_SHR_S));
        entries.add(createTokenEntry(WatToken.I32X4_SHR_U));
        entries.add(createTokenEntry(WatToken.I32X4_SPLAT));
        entries.add(createTokenEntry(WatToken.I32X4_SUB));
        entries.add(createTokenEntry(WatToken.I32X4_TRUNC_SAT_F32X4_S));
        entries.add(createTokenEntry(WatToken.I32X4_TRUNC_SAT_F32X4_U));
        entries.add(createTokenEntry(WatToken.I32X4_TRUNC_SAT_S_ZERO_F64X2));
        entries.add(createTokenEntry(WatToken.I32X4_TRUNC_SAT_U_ZERO_F64X2));
        entries.add(createTokenEntry(WatToken.I64));
        entries.add(createTokenEntry(WatToken.I64_ADD));
        entries.add(createTokenEntry(WatToken.I64_AND));
        entries.add(createTokenEntry(WatToken.I64_CLZ));
        entries.add(createTokenEntry(WatToken.I64_CONST));
        entries.add(createTokenEntry(WatToken.I64_CTZ));
        entries.add(createTokenEntry(WatToken.I64_DIV_S));
        entries.add(createTokenEntry(WatToken.I64_DIV_U));
        entries.add(createTokenEntry(WatToken.I64_EQ));
        entries.add(createTokenEntry(WatToken.I64_EQZ));
        entries.add(createTokenEntry(WatToken.I64_EXTEND16_S));
        entries.add(createTokenEntry(WatToken.I64_EXTEND32_S));
        entries.add(createTokenEntry(WatToken.I64_EXTEND8_S));
        entries.add(createTokenEntry(WatToken.I64_EXTEND_I32_S));
        entries.add(createTokenEntry(WatToken.I64_EXTEND_I32_U));
        entries.add(createTokenEntry(WatToken.I64_GE_S));
        entries.add(createTokenEntry(WatToken.I64_GE_U));
        entries.add(createTokenEntry(WatToken.I64_GT_S));
        entries.add(createTokenEntry(WatToken.I64_GT_U));
        entries.add(createTokenEntry(WatToken.I64_LE_S));
        entries.add(createTokenEntry(WatToken.I64_LE_U));
        entries.add(createTokenEntry(WatToken.I64_LOAD));
        entries.add(createTokenEntry(WatToken.I64_LOAD16_S));
        entries.add(createTokenEntry(WatToken.I64_LOAD16_U));
        entries.add(createTokenEntry(WatToken.I64_LOAD32_S));
        entries.add(createTokenEntry(WatToken.I64_LOAD32_U));
        entries.add(createTokenEntry(WatToken.I64_LOAD8_S));
        entries.add(createTokenEntry(WatToken.I64_LOAD8_U));
        entries.add(createTokenEntry(WatToken.I64_LT_S));
        entries.add(createTokenEntry(WatToken.I64_LT_U));
        entries.add(createTokenEntry(WatToken.I64_MUL));
        entries.add(createTokenEntry(WatToken.I64_NE));
        entries.add(createTokenEntry(WatToken.I64_OR));
        entries.add(createTokenEntry(WatToken.I64_POPCNT));
        entries.add(createTokenEntry(WatToken.I64_REINTERPRET_F64));
        entries.add(createTokenEntry(WatToken.I64_REM_S));
        entries.add(createTokenEntry(WatToken.I64_REM_U));
        entries.add(createTokenEntry(WatToken.I64_ROTL));
        entries.add(createTokenEntry(WatToken.I64_ROTR));
        entries.add(createTokenEntry(WatToken.I64_SHL));
        entries.add(createTokenEntry(WatToken.I64_SHR_S));
        entries.add(createTokenEntry(WatToken.I64_SHR_U));
        entries.add(createTokenEntry(WatToken.I64_STORE));
        entries.add(createTokenEntry(WatToken.I64_STORE16));
        entries.add(createTokenEntry(WatToken.I64_STORE32));
        entries.add(createTokenEntry(WatToken.I64_STORE8));
        entries.add(createTokenEntry(WatToken.I64_SUB));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_F32_S));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_F32_U));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_F64_S));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_F64_U));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_SAT_F32_S));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_SAT_F32_U));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_SAT_F64_S));
        entries.add(createTokenEntry(WatToken.I64_TRUNC_SAT_F64_U));
        entries.add(createTokenEntry(WatToken.I64_XOR));
        entries.add(createTokenEntry(WatToken.I64X2_ABS));
        entries.add(createTokenEntry(WatToken.I64X2_ADD));
        entries.add(createTokenEntry(WatToken.I64X2_ALL_TRUE));
        entries.add(createTokenEntry(WatToken.I64X2_BITMASK));
        entries.add(createTokenEntry(WatToken.I64X2_EQ));
        entries.add(createTokenEntry(WatToken.I64X2_EXTEND_HIGH_S_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTEND_HIGH_U_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTEND_LOW_S_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTEND_LOW_U_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTMUL_HIGH_S_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTMUL_HIGH_U_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTMUL_LOW_S_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTMUL_LOW_U_I32X4));
        entries.add(createTokenEntry(WatToken.I64X2_EXTRACT_LANE));
        entries.add(createTokenEntry(WatToken.I64X2_GE_S));
        entries.add(createTokenEntry(WatToken.I64X2_GT_S));
        entries.add(createTokenEntry(WatToken.I64X2_LE_S));
        entries.add(createTokenEntry(WatToken.I64X2_LT_S));
        entries.add(createTokenEntry(WatToken.I64X2_MUL));
        entries.add(createTokenEntry(WatToken.I64X2_NE));
        entries.add(createTokenEntry(WatToken.I64X2_NEG));
        entries.add(createTokenEntry(WatToken.I64X2_RELAXED_LANESELECT));
        entries.add(createTokenEntry(WatToken.I64X2_REPLACE_LANE));
        entries.add(createTokenEntry(WatToken.I64X2_SHL));
        entries.add(createTokenEntry(WatToken.I64X2_SHR_S));
        entries.add(createTokenEntry(WatToken.I64X2_SHR_U));
        entries.add(createTokenEntry(WatToken.I64X2_SPLAT));
        entries.add(createTokenEntry(WatToken.I64X2_SUB));
        entries.add(createTokenEntry(WatToken.I8));
        entries.add(createTokenEntry(WatToken.I8X16_ABS));
        entries.add(createTokenEntry(WatToken.I8X16_ADD));
        entries.add(createTokenEntry(WatToken.I8X16_ADD_SAT_S));
        entries.add(createTokenEntry(WatToken.I8X16_ADD_SAT_U));
        entries.add(createTokenEntry(WatToken.I8X16_ALL_TRUE));
        entries.add(createTokenEntry(WatToken.I8X16_AVGR_U));
        entries.add(createTokenEntry(WatToken.I8X16_BITMASK));
        entries.add(createTokenEntry(WatToken.I8X16_EQ));
        entries.add(createTokenEntry(WatToken.I8X16_EXTRACT_LANE_S));
        entries.add(createTokenEntry(WatToken.I8X16_EXTRACT_LANE_U));
        entries.add(createTokenEntry(WatToken.I8X16_GE_S));
        entries.add(createTokenEntry(WatToken.I8X16_GE_U));
        entries.add(createTokenEntry(WatToken.I8X16_GT_S));
        entries.add(createTokenEntry(WatToken.I8X16_GT_U));
        entries.add(createTokenEntry(WatToken.I8X16_LE_S));
        entries.add(createTokenEntry(WatToken.I8X16_LE_U));
        entries.add(createTokenEntry(WatToken.I8X16_LT_S));
        entries.add(createTokenEntry(WatToken.I8X16_LT_U));
        entries.add(createTokenEntry(WatToken.I8X16_MAX_S));
        entries.add(createTokenEntry(WatToken.I8X16_MAX_U));
        entries.add(createTokenEntry(WatToken.I8X16_MIN_S));
        entries.add(createTokenEntry(WatToken.I8X16_MIN_U));
        entries.add(createTokenEntry(WatToken.I8X16_NARROW_I16X8_S));
        entries.add(createTokenEntry(WatToken.I8X16_NARROW_I16X8_U));
        entries.add(createTokenEntry(WatToken.I8X16_NE));
        entries.add(createTokenEntry(WatToken.I8X16_NEG));
        entries.add(createTokenEntry(WatToken.I8X16_POPCNT));
        entries.add(createTokenEntry(WatToken.I8X16_RELAXED_LANESELECT));
        entries.add(createTokenEntry(WatToken.I8X16_RELAXED_SWIZZLE));
        entries.add(createTokenEntry(WatToken.I8X16_REPLACE_LANE));
        entries.add(createTokenEntry(WatToken.I8X16_SHL));
        entries.add(createTokenEntry(WatToken.I8X16_SHR_S));
        entries.add(createTokenEntry(WatToken.I8X16_SHR_U));
        entries.add(createTokenEntry(WatToken.I8X16_SHUFFLE));
        entries.add(createTokenEntry(WatToken.I8X16_SPLAT));
        entries.add(createTokenEntry(WatToken.I8X16_SUB));
        entries.add(createTokenEntry(WatToken.I8X16_SUB_SAT_S));
        entries.add(createTokenEntry(WatToken.I8X16_SUB_SAT_U));
        entries.add(createTokenEntry(WatToken.I8X16_SWIZZLE));
        entries.add(createTokenEntry(WatToken.IF));
        entries.add(createTokenEntry(WatToken.IMPORT));
        entries.add(createTokenEntry(WatToken.ITEM));
        entries.add(createTokenEntry(WatToken.LIFT));
        entries.add(createTokenEntry(WatToken.LOCAL));
        entries.add(createTokenEntry(WatToken.LOCAL_GET));
        entries.add(createTokenEntry(WatToken.LOCAL_SET));
        entries.add(createTokenEntry(WatToken.LOCAL_TEE));
        entries.add(createTokenEntry(WatToken.LOOP));
        entries.add(createTokenEntry(WatToken.MEMORY));
        entries.add(createTokenEntry(WatToken.MEMORY_COPY));
        entries.add(createTokenEntry(WatToken.MEMORY_FILL));
        entries.add(createTokenEntry(WatToken.MEMORY_GROW));
        entries.add(createTokenEntry(WatToken.MEMORY_INIT));
        entries.add(createTokenEntry(WatToken.MEMORY_SIZE));
        entries.add(createTokenEntry(WatToken.MODULE));
        entries.add(createTokenEntry(WatToken.MUT));
        entries.add(createTokenEntry(WatToken.NOEXN));
        entries.add(createTokenEntry(WatToken.NOEXTERN));
        entries.add(createTokenEntry(WatToken.NOFUNC));
        entries.add(createTokenEntry(WatToken.NONE));
        entries.add(createTokenEntry(WatToken.NOP));
        entries.add(createTokenEntry(WatToken.NULL));
        entries.add(createTokenEntry(WatToken.NULLEXNREF));
        entries.add(createTokenEntry(WatToken.NULLEXTERNREF));
        entries.add(createTokenEntry(WatToken.NULLFUNCREF));
        entries.add(createTokenEntry(WatToken.NULLREF));
        entries.add(createTokenEntry(WatToken.OFFSET));
        entries.add(createTokenEntry(WatToken.PARAM));
        entries.add(createTokenEntry(WatToken.PASSIVE));
        entries.add(createTokenEntry(WatToken.REC));
        entries.add(createTokenEntry(WatToken.REF));
        entries.add(createTokenEntry(WatToken.REF_AS_NON_NULL));
        entries.add(createTokenEntry(WatToken.REF_CAST));
        entries.add(createTokenEntry(WatToken.REF_EQ));
        entries.add(createTokenEntry(WatToken.REF_FUNC));
        entries.add(createTokenEntry(WatToken.REF_I31));
        entries.add(createTokenEntry(WatToken.REF_IS_NULL));
        entries.add(createTokenEntry(WatToken.REF_NULL));
        entries.add(createTokenEntry(WatToken.REF_TEST));
        entries.add(createTokenEntry(WatToken.RESULT));
        entries.add(createTokenEntry(WatToken.RETURN));
        entries.add(createTokenEntry(WatToken.RETURN_CALL));
        entries.add(createTokenEntry(WatToken.RETURN_CALL_INDIRECT));
        entries.add(createTokenEntry(WatToken.RETURN_CALL_REF));
        entries.add(createTokenEntry(WatToken.SELECT));
        entries.add(createTokenEntry(WatToken.START));
        entries.add(createTokenEntry(WatToken.STRUCT));
        entries.add(createTokenEntry(WatToken.STRUCT_GET));
        entries.add(createTokenEntry(WatToken.STRUCT_GET_S));
        entries.add(createTokenEntry(WatToken.STRUCT_GET_U));
        entries.add(createTokenEntry(WatToken.STRUCT_NEW));
        entries.add(createTokenEntry(WatToken.STRUCT_NEW_DEFAULT));
        entries.add(createTokenEntry(WatToken.STRUCT_SET));
        entries.add(createTokenEntry(WatToken.STRUCTREF));
        entries.add(createTokenEntry(WatToken.SUB));
        entries.add(createTokenEntry(WatToken.TABLE));
        entries.add(createTokenEntry(WatToken.TABLE_COPY));
        entries.add(createTokenEntry(WatToken.TABLE_FILL));
        entries.add(createTokenEntry(WatToken.TABLE_GET));
        entries.add(createTokenEntry(WatToken.TABLE_GROW));
        entries.add(createTokenEntry(WatToken.TABLE_INIT));
        entries.add(createTokenEntry(WatToken.TABLE_SET));
        entries.add(createTokenEntry(WatToken.TABLE_SIZE));
        entries.add(createTokenEntry(WatToken.TAG));
        entries.add(createTokenEntry(WatToken.THEN));
        entries.add(createTokenEntry(WatToken.THROW));
        entries.add(createTokenEntry(WatToken.THROW_REF));
        entries.add(createTokenEntry(WatToken.TRY_TABLE));
        entries.add(createTokenEntry(WatToken.TYPE));
        entries.add(createTokenEntry(WatToken.UNREACHABLE));
        entries.add(createTokenEntry(WatToken.U32));
        entries.add(createTokenEntry(WatToken.V128));
        entries.add(createTokenEntry(WatToken.V128_AND));
        entries.add(createTokenEntry(WatToken.V128_ANDNOT));
        entries.add(createTokenEntry(WatToken.V128_ANY_TRUE));
        entries.add(createTokenEntry(WatToken.V128_BITSELECT));
        entries.add(createTokenEntry(WatToken.V128_CONST));
        entries.add(createTokenEntry(WatToken.V128_LOAD));
        entries.add(createTokenEntry(WatToken.V128_LOAD16_LANE));
        entries.add(createTokenEntry(WatToken.V128_LOAD16_SPLAT));
        entries.add(createTokenEntry(WatToken.V128_LOAD16X4_S));
        entries.add(createTokenEntry(WatToken.V128_LOAD16X4_U));
        entries.add(createTokenEntry(WatToken.V128_LOAD32_LANE));
        entries.add(createTokenEntry(WatToken.V128_LOAD32_SPLAT));
        entries.add(createTokenEntry(WatToken.V128_LOAD32_ZERO));
        entries.add(createTokenEntry(WatToken.V128_LOAD32X2_S));
        entries.add(createTokenEntry(WatToken.V128_LOAD32X2_U));
        entries.add(createTokenEntry(WatToken.V128_LOAD64_LANE));
        entries.add(createTokenEntry(WatToken.V128_LOAD64_SPLAT));
        entries.add(createTokenEntry(WatToken.V128_LOAD64_ZERO));
        entries.add(createTokenEntry(WatToken.V128_LOAD8_LANE));
        entries.add(createTokenEntry(WatToken.V128_LOAD8_SPLAT));
        entries.add(createTokenEntry(WatToken.V128_LOAD8X8_S));
        entries.add(createTokenEntry(WatToken.V128_LOAD8X8_U));
        entries.add(createTokenEntry(WatToken.V128_NOT));
        entries.add(createTokenEntry(WatToken.V128_OR));
        entries.add(createTokenEntry(WatToken.V128_STORE));
        entries.add(createTokenEntry(WatToken.V128_STORE16_LANE));
        entries.add(createTokenEntry(WatToken.V128_STORE32_LANE));
        entries.add(createTokenEntry(WatToken.V128_STORE64_LANE));
        entries.add(createTokenEntry(WatToken.V128_STORE8_LANE));
        entries.add(createTokenEntry(WatToken.V128_XOR));
        entries.add(createTokenEntry(WatToken.WITH));

        return new StringMap<>(11, entries);
    }

    private static StringMap.Entry<WatToken> createTokenEntry(@NotNull WatToken token) {
        String tokenText = token.getName();
        return new StringMap.Entry<>(token.getName(), hashString(tokenText), token);
    }
}
