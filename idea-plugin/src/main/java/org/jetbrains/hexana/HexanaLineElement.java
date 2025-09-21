package org.jetbrains.hexana;

import javax.swing.text.AttributeSet;
import javax.swing.text.Document;
import javax.swing.text.Element;


public class HexanaLineElement implements Element {
    private Document doc;
    private Element parent;
    private int startOffset;
    private int endOffset;

    HexanaLineElement(Document doc,
                      Element parent,
                      int startOffset,
                      int endOffset) {
        this.doc = doc;
        this.parent = parent;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    @Override
    public Document getDocument() {
        return doc;
    }

    @Override
    public Element getParentElement() {
        return parent;
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public AttributeSet getAttributes() {
        return null;
    }

    @Override
    public int getStartOffset() {
        return startOffset;
    }

    @Override
    public int getEndOffset() {
        return endOffset;
    }

    @Override
    public int getElementIndex(int offset) {
        return -1;
    }

    @Override
    public int getElementCount() {
        return 0;
    }

    @Override
    public Element getElement(int index) {
        return null;
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

}
