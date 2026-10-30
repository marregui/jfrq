// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.coll;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListsTest {

    @Test
    void objListGrowsSortsAndClears() {
        final ObjList<String> list = new ObjList<>(1);
        assertTrue(list.isEmpty());
        assertFalse(list.notEmpty());
        for (int i = 0; i < 100; i++) {
            list.add("s" + (99 - i));
        }
        assertEquals(100, list.size());
        assertEquals("s99", list.getQuick(0));
        assertEquals("s0", list.getLast());
        assertEquals("s98", list.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(100));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        list.sort(Comparator.naturalOrder());
        assertEquals("s0", list.getQuick(0));
        assertEquals("s99", list.getLast());
        list.setQuick(0, "first");
        assertEquals("first", list.get(0));
        final List<String> snapshot = list.toList();
        assertEquals(100, snapshot.size());
        assertEquals("first", snapshot.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("x"));
        final ObjList<String> other = new ObjList<>();
        other.add("a");
        other.addAll(list);
        assertEquals(101, other.size());
        assertEquals("first", other.get(1));
        assertTrue(other.toString().startsWith("[a, first"));
        list.clear();
        assertTrue(list.isEmpty());
        assertThrows(IndexOutOfBoundsException.class, list::getLast);
        assertEquals(List.of(), list.toList());
    }

    @Test
    void longListGrowsSortsAndSearches() {
        final LongList list = new LongList(1);
        assertTrue(list.isEmpty());
        assertEquals(-1, list.getLast());
        assertEquals(7, new LongList(4, 7).getLast());
        for (long v = 50; v > 0; v--) {
            list.add(v * 10);
        }
        assertEquals(50, list.size());
        assertTrue(list.notEmpty());
        assertEquals(500, list.getQuick(0));
        assertEquals(10, list.getLast());
        assertEquals(490, list.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(50));
        list.sort();
        assertEquals(10, list.getQuick(0));
        assertEquals(500, list.getLast());
        assertEquals(0, list.lowerBound(5));
        assertEquals(0, list.lowerBound(10));
        assertEquals(1, list.lowerBound(11));
        assertEquals(49, list.lowerBound(500));
        assertEquals(50, list.lowerBound(501));
        list.setQuick(0, 1);
        assertEquals(1, list.get(0));
        list.extendAndSet(60, 42);
        assertEquals(61, list.size());
        assertEquals(42, list.get(60));
        list.setPos(3);
        assertEquals(3, list.size());
        assertEquals("[1, 20, 30]", list.toString());
        list.clear();
        assertTrue(list.isEmpty());
        assertEquals(0, new LongList().lowerBound(0));
    }

    @Test
    void nulls() {
        assertEquals(Nulls.LONG_NULL, Nulls.intToLong(Nulls.INT_NULL));
        assertEquals(5L, Nulls.intToLong(5));
        assertTrue(Double.isNaN(Nulls.DOUBLE_NULL));
    }
}
