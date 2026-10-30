// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.coll.LongList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortsTest {

    @Test
    void lowerBoundFindsTheFirstElementAtOrAboveTheValue() {
        final List<Long> keys = List.of(10L, 20L, 20L, 30L);
        assertEquals(0, Sorts.lowerBound(keys, Long::longValue, 5));
        assertEquals(0, Sorts.lowerBound(keys, Long::longValue, 10));
        assertEquals(1, Sorts.lowerBound(keys, Long::longValue, 15));
        assertEquals(1, Sorts.lowerBound(keys, Long::longValue, 20));
        assertEquals(3, Sorts.lowerBound(keys, Long::longValue, 21));
        assertEquals(4, Sorts.lowerBound(keys, Long::longValue, 31));
        assertEquals(0, Sorts.lowerBound(List.of(), Long::longValue, 0));
    }

    @Test
    void maxLength() {
        assertEquals(0, Sorts.maxDuration(List.of(), Long::longValue));
        assertEquals(30, Sorts.maxDuration(List.of(10L, 30L, 20L), Long::longValue));
    }

    @Test
    void orderIsAscendingAndKeepsEqualKeysInIndexOrder() {
        final Random random = new Random(7);
        for (int n = 0; n < 70; n++) {
            final LongList keys = new LongList(Math.max(1, n));
            for (int i = 0; i < n; i++) {
                keys.add(random.nextInt(5) - 2L);
            }
            final int[] order = Sorts.order(keys);
            assertEquals(n, order.length);
            final boolean[] seen = new boolean[n];
            for (int k = 0; k < n; k++) {
                seen[order[k]] = true;
                if (k > 0) {
                    final long before = keys.getQuick(order[k - 1]);
                    final long at = keys.getQuick(order[k]);
                    assertTrue(before < at || before == at && order[k - 1] < order[k], "n=" + n + " k=" + k);
                }
            }
            for (int i = 0; i < n; i++) {
                assertTrue(seen[i], "n=" + n + " missing " + i);
            }
        }
    }
}
