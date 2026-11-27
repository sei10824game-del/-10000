package com.rlclones.ai.util;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RevisionListTest {
    @Test
    void publicMutationPathsInvalidateDerivedState() {
        RevisionList<String> list = new RevisionList<>();
        long revision = list.revision();
        list.addAll(List.of("a","b","c","d"));
        assertTrue(list.revision() > revision);
        revision = list.revision();
        Collections.swap(list,0,1);
        assertEquals(List.of("b","a","c","d"),list);
        assertTrue(list.revision() > revision);
        revision = list.revision();
        var iterator = list.listIterator();
        assertEquals("b",iterator.next());
        iterator.set("B");
        assertTrue(list.revision() > revision);
        revision = list.revision();
        iterator.remove();
        assertTrue(list.revision() > revision);
        revision = list.revision();
        list.subList(0,2).clear();
        assertEquals(List.of("d"),list);
        assertTrue(list.revision() > revision);
        revision = list.revision();
        list.removeIf("d"::equals);
        assertTrue(list.isEmpty());
        assertTrue(list.revision() > revision);
        list.add("e");
        revision = list.revision();
        list.clear();
        assertTrue(list.isEmpty());
        assertTrue(list.revision() > revision);
    }
}
