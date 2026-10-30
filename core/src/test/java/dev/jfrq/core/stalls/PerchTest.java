// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.coll.LongList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerchTest {

    static LongList of(final long... values) {
        final LongList out = new LongList();
        for (final long v : values) {
            out.add(v);
        }
        return out;
    }

    /** {@code n} waits ending 10 apart, from 10; the lock is {@code 1} plus the number of {@code moves} already passed. */
    static LongList[] turns(final int n, final long... moves) {
        final LongList locks = new LongList();
        final LongList ends = new LongList();
        for (int i = 1; i <= n; i++) {
            final long end = i * 10L;
            long lock = 1;
            for (final long m : moves) {
                if (m < end) {
                    lock++;
                }
            }
            locks.add(lock);
            ends.add(end);
        }
        return new LongList[]{locks, ends};
    }

    @Test
    void aLockThatChangesOnlyAtRarePausesMoved() {
        // 100 waits over 990, pauses at 255 and 505: each change is 10 long, and two pauses in
        // 990 meet one by chance at odds of 1 in 50; both at 1 in 2 450.
        final LongList[] t = turns(100, 255, 505);
        assertTrue(Perch.moved(t[0], t[1], of(255, 256, 505, 506)));
        assertFalse(Perch.moved(t[0], t[1], of(255, 256)), "the second change has no pause");
        assertFalse(Perch.moved(t[0], t[1], of(100, 101, 505, 506)), "a pause before the first change explains nothing");
        assertFalse(Perch.moved(t[0], t[1], of()));
    }

    @Test
    void pausesSoFrequentThatEveryChangeMeetsOneProveNothing() {
        // The same two changes, with a pause every 20: every stretch of 10 meets one at even odds.
        final LongList[] t = turns(100, 255, 505);
        final LongList every20 = new LongList();
        for (long p = 5; p < 1_000; p += 20) {
            every20.add(p);
            every20.add(p + 1);
        }
        every20.add(255);
        every20.add(256);
        assertFalse(Perch.moved(t[0], t[1], sorted(every20)));
        // And one change is not enough on its own at 1 in 50.
        final LongList[] once = turns(100, 255);
        assertFalse(Perch.moved(once[0], once[1], of(255, 256, 505, 506)));
    }

    @Test
    void aPauseCountsWhenItOverlapsTheChange() {
        // The change is (250, 260]; a pause running over either end still covers part of it.
        final LongList[] t = turns(100, 255, 505);
        assertTrue(Perch.moved(t[0], t[1], of(245, 251, 505, 506)));
        assertTrue(Perch.moved(t[0], t[1], of(260, 265, 505, 506)));
        // One that ends as the change begins, or starts after it, does not.
        assertFalse(Perch.moved(t[0], t[1], of(245, 250, 505, 506)));
        assertFalse(Perch.moved(t[0], t[1], of(261, 265, 505, 506)));
    }

    @Test
    void oneLockIsNotAMove() {
        assertFalse(Perch.moved(of(1, 1, 1), of(10, 20, 30), of(15, 16)));
        assertFalse(Perch.moved(of(), of(), of(15, 16)));
    }

    @Test
    void endsOutOfOrderAreNotOneThreadsTurns() {
        // One thread cannot wait twice at once: out-of-order ends mean the input is not one thread's.
        final LongList[] t = turns(100, 255, 505);
        t[1].setQuick(50, 5);
        assertFalse(Perch.moved(t[0], t[1], of(255, 256, 505, 506)));
    }

    /** Pairs sorted by start. */
    private static LongList sorted(final LongList pairs) {
        final int n = pairs.size() / 2;
        final long[][] p = new long[n][];
        for (int i = 0; i < n; i++) {
            p[i] = new long[]{pairs.getQuick(2 * i), pairs.getQuick(2 * i + 1)};
        }
        Arrays.sort(p, (a, b) -> Long.compare(a[0], b[0]));
        final LongList out = new LongList();
        for (final long[] q : p) {
            out.add(q[0]);
            out.add(q[1]);
        }
        return out;
    }
}
