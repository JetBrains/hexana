package org.jetbrains.hexana.wat.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StringMap<T> {

    int mySize;
    int myMask;
    long[] myHashes;
    int[] myIntervals;
    char[] myChars;
    int myCharsIndex;
    T[] myValues;

    public StringMap(int sizePow2, @NotNull List<Entry<T>> entries) {
        assert(0 <= sizePow2 && sizePow2 < 32);
        int size = 1 << sizePow2;
        if (size < entries.size()) {
            throw new IllegalArgumentException("Size is too small");
        }
        mySize = size;
        myMask = size - 1;

        myHashes = new long[size];
        myIntervals = new int[size];
        //noinspection unchecked
        myValues = (T[]) new Object[size];
        int charsLen = entries.stream().map(it -> it.key.length()).reduce(0, Integer::sum);
        myChars = new char[charsLen];

        Set<String> uniqueKeys = new HashSet<>();
        for (Entry<T> entry : entries) {
            if (uniqueKeys.add(entry.key)) {
                put(entry.key, entry.keyHash, entry.value);
            } else {
                throw new IllegalArgumentException("Duplicate key: " + entry.key);
            }

        }
    }

    public @Nullable T get(CharSequence key, long keyHash, int start, int len) {
        int index = (int) (keyHash & myMask);
        for (int i = 0; i < mySize; i++) {
            long hash = myHashes[index];
            if (keyHash == hash) {
                int interval = myIntervals[index];
                int intervalLen = interval & 0xff;
                int intervalOffset = interval >> 8;
                if (len == intervalLen) {
                    boolean match = true;
                    for (int j = 0; j < len; j++) {
                        if (myChars[intervalOffset + j] != key.charAt(start + j)) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        return myValues[index];
                    }
                }
            }
            index = (index + 1) & myMask;
        }
        return null;
    }

    private void put(@NotNull String key, long keyHash, T value) {
        if (key.length() >= 256) {
            throw new IllegalStateException("Key is too long: \"" + key +"\"");
        }
        if (keyHash == 0) {
            throw new IllegalStateException("Zero hash");
        }

        int index = (int) (keyHash & myMask);
        int i = 0;
        for (; i < mySize; i++) {
            long hash = myHashes[index];
            if (hash == 0) {
                myHashes[index] = keyHash;
                int charsOffset = myCharsIndex;
                for (int j = 0; j < key.length(); j++) {
                    myChars[myCharsIndex++] = key.charAt(j);
                }
                myIntervals[index] = charsOffset << 8 | key.length();
                myValues[index] = value;
                break;
            }
            index = (index + 1) & myMask;
        }
        if (i == mySize) {
            throw new IllegalStateException("Put failed");
        }
    }


    public static class Entry<T> {
        public String key;
        public long keyHash;
        public T value;

        public Entry(String key, long keyHash, T value) {
            this.key = key;
            this.keyHash = keyHash;
            this.value = value;
        }
    }
}
