package org.jetbrains.hexana.wat;

import org.jetbrains.hexana.wat.psi.StringMap;
import org.junit.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StringMapTest {

    @Test
    public void testEmpty() {
        StringMap<String> map = new StringMap<>(0, emptyList());
        assertNull(map.get("k", "k".hashCode(), 0, 1));
    }

    @Test
    public void testSome() {
        StringMap<String> map = new StringMap<>(1, List.of(
                new StringMap.Entry<>("key1", 1, "val1"),
                new StringMap.Entry<>("key2", 1, "val2")
        ));
        assertEquals("val1", map.get("key1", 1, 0, "key1".length()));
        assertEquals("val2", map.get("key2", 1, 0, "key2".length()));
        assertNull(map.get("key3", 1, 0, "key3".length()));
        assertNull(map.get("key3", 1, 0, "key3".length()));
        assertNull(map.get("key3", 1, 0, "key3".length()));
    }
}
