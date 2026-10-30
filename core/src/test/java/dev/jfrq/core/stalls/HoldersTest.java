// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The holder walk-back on hand-built monitor waits; times in milliseconds. */
class HoldersTest {

    static final long MS = 1_000_000L;
    static final ThreadRef VICTIM = new ThreadRef(1, "event-loop-1");
    static final ThreadRef BRIEF = new ThreadRef(2, "worker-1");
    static final ThreadRef BULK = new ThreadRef(3, "housekeeper");
    static final ThreadRef OTHER = new ThreadRef(4, "worker-2");
    static final String LOCK = "dev.app.Registry@1";

    static Block wait(final long fromMs, final long toMs, final ThreadRef owner) {
        return new Block(new Interval(fromMs * MS, toMs * MS), BlockKind.MONITOR, LOCK, Stack.EMPTY, owner);
    }

    /** Pairs of (thread, wait). */
    static Holders<Block> holders(final Object... threadsAndWaits) {
        final Holders<Block> h = new Holders<>(StallCollector.MONITOR_WAITS);
        for (int i = 0; i < threadsAndWaits.length; i += 2) {
            h.add((ThreadRef) threadsAndWaits[i], (Block) threadsAndWaits[i + 1]);
        }
        h.seal();
        return h;
    }

    @Test
    void aBriefIntermediaryIsWalkedThroughToTheBulkHolder() {
        // worker-1 waited behind the housekeeper too, got the lock at 95 ms and handed it to the
        // victim at 100 ms: 5 ms of a 100 ms wait.
        final Block victim = wait(0, 100, BRIEF);
        final Block resolved = holders(BRIEF, wait(0, 95, BULK)).resolve(VICTIM, victim);
        assertEquals(BULK, resolved.owner());
        assertEquals(List.of(BRIEF), resolved.via());
    }

    @Test
    void theRecordedOwnerThatHeldMostOfTheWaitIsTheHolder() {
        // The housekeeper held the lock for the first half millisecond of the wait and handed it
        // to worker-1, which kept it for the remaining 299.5 ms.
        final Block victim = new Block(new Interval(MS / 2, 300 * MS), BlockKind.MONITOR, LOCK, Stack.EMPTY, BRIEF);
        final Block resolved = holders(BRIEF, wait(0, 1, BULK)).resolve(VICTIM, victim);
        assertEquals(BRIEF, resolved.owner());
        assertEquals(List.of(BULK), resolved.via());
        // A wait of worker-1's that ended before the victim's began: it held the lock throughout.
        final Block early = wait(40, 100, BRIEF);
        assertSame(early, holders(BRIEF, wait(0, 40, BULK)).resolve(VICTIM, early));
        // No wait at all: the same.
        assertSame(early, holders().resolve(VICTIM, early));
    }

    @Test
    void theHolderIsFoundTheLongestHolderAcrossSeveralHops() {
        // The victim waited 97 ms: the housekeeper held the lock for the first 36, worker-2 got
        // it at 36 and held it 21, worker-1 got it at 57 and held the last 40. Stopping at the
        // first thread that got the lock before the middle named worker-2, which held it least.
        final Block victim = wait(0, 97, BRIEF);
        final Block resolved = holders(
                OTHER, wait(2, 36, BULK),
                BRIEF, wait(4, 57, OTHER)).resolve(VICTIM, victim);
        assertEquals(BRIEF, resolved.owner());
        assertEquals(List.of(BULK, OTHER), resolved.via());
        // Shift the holds so the housekeeper held longest: it is named, and the others follow it in order.
        final Block again = holders(
                OTHER, wait(2, 50, BULK),
                BRIEF, wait(4, 80, OTHER)).resolve(VICTIM, victim);
        assertEquals(BULK, again.owner());
        assertEquals(List.of(OTHER, BRIEF), again.via());
    }

    @Test
    void theHoldIsMeasuredFromTheWaitThatEndedLastBeforeTheHandOver() {
        // Ping-pong: C waited 0-100 and got the lock from B. B's longest wait ended at 5, but its
        // last one ended at 99: B held the lock for 1 ms at the end, D from 6 to 99, and before
        // that B from 5 to 6 and D from the start. D held it for 98 of the 100 ms.
        final ThreadRef b = BRIEF;
        final ThreadRef d = BULK;
        final Block victim = wait(0, 100, b);
        final Block resolved = holders(
                b, wait(-200, 5, d),
                d, wait(5, 6, b),
                b, wait(7, 99, d)).resolve(VICTIM, victim);
        assertEquals(d, resolved.owner());
        assertEquals(List.of(b), resolved.via());
    }

    @Test
    void aTieNamesTheThreadNearerTheVictim() {
        // worker-1 held the lock for the last 100 ms, the housekeeper for the first 100.
        final Block victim = wait(100, 300, BRIEF);
        final Block resolved = holders(BRIEF, wait(100, 200, BULK)).resolve(VICTIM, victim);
        assertEquals(BRIEF, resolved.owner());
        assertEquals(List.of(BULK), resolved.via());
    }

    @Test
    void theWalkStopsAtAnUnknownOwnerAndAtACycle() {
        final Block victim = wait(0, 100, BRIEF);
        // worker-1 got it at 95 from nobody known: the 95 ms before are no one's.
        assertSame(victim, holders(BRIEF, wait(0, 95, null)).resolve(VICTIM, victim));
        // worker-1 got it at 90 from the victim itself, which cannot hold what it waits for.
        assertSame(victim, holders(BRIEF, wait(10, 90, VICTIM)).resolve(VICTIM, victim));
        // Two threads naming each other with no time between: the walk ends.
        final Block loop = holders(
                BRIEF, wait(0, 100, BULK),
                BULK, wait(0, 100, BRIEF)).resolve(VICTIM, victim);
        assertSame(victim, loop);
        // The same after a hold of some length: worker-1 held the last 10 ms, then the two name
        // each other at 90 ms.
        assertSame(victim, holders(
                BRIEF, wait(0, 90, BULK),
                BULK, wait(0, 90, BRIEF)).resolve(VICTIM, victim));
        final Block unowned = wait(0, 100, null);
        assertSame(unowned, holders(BRIEF, wait(0, 95, BULK)).resolve(VICTIM, unowned));
        // The recorded owner is the waiter: nothing to walk.
        final Block self = wait(0, 100, VICTIM);
        assertSame(self, holders(VICTIM, wait(-50, -10, BULK)).resolve(VICTIM, self));
    }

    @Test
    void waitsForAnotherLockAreNotLinks() {
        final Block victim = wait(0, 100, BRIEF);
        final Block elsewhere = new Block(new Interval(0, 95 * MS), BlockKind.MONITOR, "dev.app.Store@2", Stack.EMPTY,
                BULK);
        assertSame(victim, holders(BRIEF, elsewhere).resolve(VICTIM, victim));
    }

    @Test
    void aLongChainNamesTheFirstFewAndCountsTheRest() {
        final List<ThreadRef> via = List.of(new ThreadRef(10, "a"), new ThreadRef(11, "b"), new ThreadRef(12, "c"),
                new ThreadRef(13, "d"), new ThreadRef(14, "e"), new ThreadRef(15, "f"));
        assertEquals(" (handed on through a, b, c, d and 2 more)", Holders.appendVia(new StringBuilder(), via).toString());
        assertEquals(" (handed on through a, b)", Holders.appendVia(new StringBuilder(), via.subList(0, 2)).toString());
        assertEquals("", Holders.appendVia(new StringBuilder(), List.of()).toString());
    }
}
