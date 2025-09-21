package org.jetbrains.hexana;

import javax.swing.text.AttributeSet;
import javax.swing.text.Document;
import javax.swing.text.Element;

class HexanaRootElement implements Element {

    private HexanaDocument myDoc;

    HexanaRootElement(HexanaDocument doc) {
        this.myDoc = doc;
    }

    @Override
    public Document getDocument() {
        return myDoc;
    }

    @Override
    public Element getParentElement() {
        return null;
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
        return 0;
    }

    @Override
    public int getEndOffset() {
        return myDoc.getLength();
    }

    @Override
    public int getElementIndex(int offset) {
        return offset / myDoc.lineLength;
    }

    @Override
    public int getElementCount() {
        return myDoc.getLength() / myDoc.lineLength;
    }

    @Override
    public Element getElement(int index) {
        int start = index * myDoc.lineLength;
        int end = start + myDoc.lineLength;
        return new HexanaLineElement(myDoc, this, start, end);
    }

    @Override
    public boolean isLeaf() {
        return false;
    }
}

