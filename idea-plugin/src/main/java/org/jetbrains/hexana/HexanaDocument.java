package org.jetbrains.hexana;


import kotlin.ranges.IntRange;

import javax.swing.event.DocumentListener;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.*;

public class HexanaDocument implements Document {

    public static char[] hex = new char[]{'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};

    int bytesPerLine = 16;
    int charPerByte = 3;
    int lineLength = bytesPerLine * charPerByte;

    private final byte[] myBuf;
    private final Element myElement = new HexanaRootElement(this);
    private final int myTextLength;

    // index in the line -> next boundary byte offset for this index
    private final int[] myByteOffset;
    private final int[] myNextBoundary;
    private final int[] myByteStartCharOffsetInLine;

    public HexanaDocument(byte[] buf) {
        this.myBuf = buf;
        myByteOffset = new int[bytesPerLine * charPerByte];
        myByteStartCharOffsetInLine = new int[bytesPerLine];
        myNextBoundary = new int[bytesPerLine * charPerByte];
        for (int i = 0; i < bytesPerLine; i++) {
            myByteOffset[charPerByte*i] = i;
            myByteOffset[charPerByte*i+1] = i;
            myByteOffset[charPerByte*i+2] = i;

            myByteStartCharOffsetInLine[i] = charPerByte*i;

            myNextBoundary[charPerByte*i] = i; // byte boundary
            myNextBoundary[charPerByte*i+1] = i+1;
            myNextBoundary[charPerByte*i+2] = i+1;
        }

        int lineCount = (buf.length + bytesPerLine - 1) / bytesPerLine;
        this.myTextLength = lineCount * lineLength;
    }

    public int getByteTextOffset(int byteOffset) {
        int line = byteOffset / bytesPerLine;
        return line * lineLength + (byteOffset % bytesPerLine) * charPerByte;
    }

    public IntRange byteRangeToTextRange(IntRange byteRange) {
        return new IntRange(
                getByteTextOffset(byteRange.getFirst()),
                getByteTextOffset(byteRange.getLast() + 1 /*cover the last byte*/));
    }

    @Override
    public int getLength() {
        return myTextLength;
    }

    @Override
    public String getText(int offset, int length) throws BadLocationException {
        Segment s = new Segment();
        getText(offset, length, s);
        return new String(s.array, 0, s.count);
    }

    @Override
    public void getText(int offset, int length, Segment txt) throws BadLocationException {
        if (offset >= myTextLength || length == 0) {
            txt.array = new char[0];
            txt.offset = 0;
            txt.count = 0;
            return;
        }

        length = Math.min(length, myTextLength - offset);
        txt.array = new char[length];
        txt.offset = 0;
        txt.count = length;

        // bb means byteBoundary

        int startLine = offset / lineLength;
        int charOffsetInStartLine = offset - (startLine * lineLength);
        int bbStart = startLine * bytesPerLine + myNextBoundary[charOffsetInStartLine];

        int endLine = (offset + length) / lineLength;
        int charOffsetInEndLine = (offset + length) - (endLine * lineLength);
        int bbEndWant = endLine * bytesPerLine + myByteOffset[charOffsetInEndLine];
        int bbEnd = Math.min(bbEndWant, myBuf.length);

        int dstIdx = 0;
        // offset..bbStart
        if (myByteOffset[charOffsetInStartLine] < myNextBoundary[charOffsetInStartLine]) {
            int offsetInByte = charOffsetInStartLine - myByteOffset[charOffsetInStartLine] * charPerByte;
            int byteOff = startLine * bytesPerLine + myByteOffset[charOffsetInStartLine];
            if (offsetInByte == 1) {
                byte b = myBuf[byteOff];
                char c = hex[b & 0xf];
                txt.array[dstIdx++] = c;
            }
            if (dstIdx < length) {
                boolean lastByteOnLine = byteOff % bytesPerLine == 0xf;
                txt.array[dstIdx++] = lastByteOnLine ? '\n' : ' ';
            }
        }

        // bbStart..bbEnd
        for (int i = bbStart; i < bbEnd; i++) {
            byte b = myBuf[i];
            char c = hex[(b >> 4) & 0xf];
            txt.array[dstIdx++] = c;
            c = hex[b & 0xf];
            txt.array[dstIdx++] = c;

            boolean lastByteOnLine = (i) % bytesPerLine == 0xf;
            txt.array[dstIdx++] = lastByteOnLine ? '\n' : ' ';
        }

        // bbEnd..(offset+length)
        if (bbEndWant < myBuf.length) {
            int charsToPrint = offset + length - bbEndWant * charPerByte;
            if (charsToPrint > 0) {
                byte lastByte = myBuf[bbEndWant];
                if (dstIdx < length) {
                    char c = hex[(lastByte >> 4) & 0xf];
                    txt.array[dstIdx++] = c;
                    charsToPrint--;
                }
                if (dstIdx < length && charsToPrint > 0) {
                    char c = c = hex[lastByte & 0xf];
                    txt.array[dstIdx++] = c;
                }
            }
        } else {
            // we are beyond the last byte, fill with spaces
            for (int i = bbEnd; ; i++) {
                if (dstIdx < length) {
                    txt.array[dstIdx++] = ' ';
                } else {
                    break;
                }
                if (dstIdx < length) {
                    txt.array[dstIdx++] = ' ';
                } else {
                    break;
                }
                if (dstIdx < length) {
                    txt.array[dstIdx++] = i % bytesPerLine == 0xf ? '\n' : ' ';
                } else {
                    break;
                }
            }
        }
    }

    @Override
    public Position getStartPosition() {
        return new Position() {
            @Override
            public int getOffset() {
                return 0;
            }
        };
    }

    @Override
    public Position getEndPosition() {
        return new Position() {
            @Override
            public int getOffset() {
                return myTextLength;
            }
        };
    }

    @Override
    public Position createPosition(int offs) throws BadLocationException {
        return new Position() {
            @Override
            public int getOffset() {
                return offs;
            }
        };
    }

    @Override
    public Element[] getRootElements() {
        return new Element[]{getDefaultRootElement()};
    }

    @Override
    public Element getDefaultRootElement() {
        return myElement;
    }

    @Override
    public void render(Runnable r) {
        r.run();
    }

    @Override
    public void addDocumentListener(DocumentListener listener) {

    }

    @Override
    public void removeDocumentListener(DocumentListener listener) {

    }

    @Override
    public void addUndoableEditListener(UndoableEditListener listener) {

    }

    @Override
    public void removeUndoableEditListener(UndoableEditListener listener) {

    }

    @Override
    public Object getProperty(Object key) {
        return null;
    }

    @Override
    public void putProperty(Object key, Object value) {

    }

    @Override
    public void remove(int offs, int len) throws BadLocationException {

    }

    @Override
    public void insertString(int offset, String str, AttributeSet a) throws BadLocationException {

    }
}
