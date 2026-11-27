package com.rlclones.ai.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.RandomAccess;

/** A mutable list whose revision changes on every content edit, including iterator/sublist edits. */
public final class RevisionList<E> extends AbstractList<E> implements RandomAccess {
    private final ArrayList<E> values = new ArrayList<>();
    private long revision;

    public long revision() { return revision; }
    @Override public int size() { return values.size(); }
    @Override public E get(int index) { return values.get(index); }
    @Override public E set(int index, E value) {
        E previous = values.set(index, value);
        revision++;
        return previous;
    }
    @Override public void add(int index, E value) {
        values.add(index, value);
        modCount++;
        revision++;
    }
    @Override public E remove(int index) {
        E previous = values.remove(index);
        modCount++;
        revision++;
        return previous;
    }
    @Override protected void removeRange(int fromIndex, int toIndex) {
        values.subList(fromIndex, toIndex).clear();
        if (fromIndex != toIndex) {
            modCount++;
            revision++;
        }
    }
}
