// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ThreadCpu} on hand-fed readings, with exact expectations. */
class ThreadCpuTest {

    static final long S = 1_000_000_000L;
    static final ThreadRef A = new ThreadRef(1, "a");
    static final ThreadRef B = new ThreadRef(2, "b");

    /** A recording of {@code seconds}, with or without {@code jdk.ThreadStart} on. */
    static RecordingInfo info(final long seconds, final boolean starts) {
        return new RecordingInfo(Path.of("a.jfr"), new Interval(0, seconds * S), 1, Map.of(),
                starts ? Map.of("jdk.ThreadStart", Map.of("enabled", "true")) : Map.of(), Set.of(), List.of());
    }

    @Test
    void eachThreadsEarliestReadingIsLeftOutWhateverTheFileOrder() {
        final ThreadCpu cpu = new ThreadCpu();
        // In file order, not time order: a's earliest reading, 0.9 of the machine, comes last. The
        // periodic instants at 1, 2 and 3 s are shared by a and b, so each later reading covers 1 s.
        cpu.add(A, 3 * S, 0.25);
        cpu.add(B, 1 * S, 0.5);
        cpu.add(B, 3 * S, 0.5);
        cpu.add(A, 2 * S, 0.25);
        cpu.add(B, 2 * S, 0.5);
        cpu.add(A, 1 * S, 0.9);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertTrue(r.isKnown());
        assertEquals(0.05, r.of(A), 1e-12);
        assertEquals(0.1, r.of(B), 1e-12);
        assertEquals(0.15, r.share(), 1e-12);
        assertEquals(4, r.readings());
        assertEquals(2, r.leftOut());
        assertTrue(Double.isNaN(r.of(new ThreadRef(3, "never read"))));
    }

    @Test
    void aReadingAtAThreadsEndCoversOnlyTheStretchSinceTheLastPeriodicInstant() {
        // Periodic instants at 1 s and 2 s, which a and b share. b ends 0.25 s after the second:
        // its end reading of a whole core covers 0.25 s, not the 1 s period.
        final ThreadCpu cpu = new ThreadCpu();
        cpu.add(A, 1 * S, 0.1);
        cpu.add(B, 1 * S, 0.2);
        cpu.add(A, 2 * S, 0.1);
        cpu.add(B, 2 * S, 0.2);
        cpu.add(B, 2 * S + S / 4, 0.4);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(0.1 / 10, r.of(A), 1e-12);
        assertEquals((0.2 + 0.4 / 4) / 10, r.of(B), 1e-12);
        final LongList instants = new LongList();
        instants.add(S);
        instants.add(2 * S);
        assertEquals(Nulls.LONG_NULL, ThreadCpu.before(instants, S));
        assertEquals(S, ThreadCpu.before(instants, 2 * S));
        assertEquals(2 * S, ThreadCpu.atOrBefore(instants, 2 * S));
        assertEquals(Nulls.LONG_NULL, ThreadCpu.atOrBefore(instants, S - 1));
        assertEquals(Nulls.LONG_NULL, ThreadCpu.before(null, S));
    }

    @Test
    void aThreadReadAloneCountsEachReadingFromItsOwnPreviousOne() {
        // No other thread has a reading, so no instant is shared: a's own readings are the
        // evaluations. Its first, at 1 s, is left out; each later one covers the second before it.
        final ThreadCpu cpu = new ThreadCpu();
        for (int s = 1; s <= 4; s++) {
            cpu.add(A, s * S, 0.5);
        }
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(3 * 0.5 / 10, r.of(A), 1e-12);
        assertEquals(3, r.readings());
        assertEquals(1, r.leftOut());
    }

    @Test
    void eachLifeOfAThreadCountsFromItsOwnStart() {
        // c lives twice: started at 0.5 s and read at 1.5 s, then started again at 5.1 s and read
        // at 5.2 s. a and b share the instants 1 s to 6 s. The first life's reading covers the
        // stretch since 1 s, the shared instant after its start; the second's since 5.1 s.
        final ThreadRef c = new ThreadRef(3, "C2 CompilerThread1");
        final ThreadCpu cpu = new ThreadCpu();
        for (int s = 1; s <= 6; s++) {
            cpu.add(A, s * S, 0.1);
            cpu.add(B, s * S, 0.1);
        }
        cpu.start(c, 5 * S + S / 10, false);
        cpu.add(c, 5 * S + S / 5, 0.5);
        cpu.start(c, S / 2, false);
        cpu.add(c, S + S / 2, 0.5);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals((0.5 * 0.5 + 0.5 * 0.1) / 10, r.of(c), 1e-12);
        // a's and b's first readings are left out; c's both count.
        assertEquals(2, r.leftOut());
        assertEquals(12, r.readings());
    }

    @Test
    void aFirstReadingAfterASharedInstantCountsOnlyWhenTheStartsWereRecorded() {
        // e's first reading, at 2 s, follows the evaluation at 1 s that a and b share. With
        // jdk.ThreadStart on and no start of e in the file, e was alive at 1 s: the reading covers
        // that second.
        final ThreadRef e = new ThreadRef(5, "e");
        final ThreadCpu cpu = new ThreadCpu();
        cpu.add(A, S, 0.1);
        cpu.add(B, S, 0.1);
        cpu.add(e, 2 * S, 0.3);
        final ThreadCpu.Result on = cpu.result(info(10, true));
        assertEquals(0.3 / 10, on.of(e), 1e-12);
        assertEquals(2, on.leftOut());
        assertEquals(1, on.readings());
        // Without it, e may have started at 1.95 s and run a whole core since: the stretch is not
        // in the file, and the reading is left out.
        final ThreadCpu.Result off = cpu.result(info(10, false));
        assertEquals(0.0, off.of(e));
        assertEquals(3, off.leftOut());
        assertEquals(0, off.readings());
        assertEquals(0.0, off.share());
    }

    @Test
    void aFirstReadingCountsFromItsThreadsStartWhenTheStartIsInTheFile() {
        // c starts at 2.0003 s and reads a whole core (1/12 of the machine) at 2.0006 s: that core
        // covers 0.3 ms, not a period. d started before the recording: its first reading is left out.
        final ThreadRef c = new ThreadRef(3, "worker");
        final ThreadRef d = new ThreadRef(4, "old");
        final ThreadCpu cpu = new ThreadCpu();
        cpu.add(A, 1 * S, 0.1);
        cpu.add(d, 1 * S, 0.5);
        cpu.add(A, 2 * S, 0.1);
        cpu.add(d, 2 * S, 0.5);
        cpu.start(c, 2 * S + 300_000, false);
        cpu.add(c, 2 * S + 600_000, 1.0 / 12);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(300_000.0 / 12 / (10 * S), r.of(c), 1e-15);
        assertEquals(0.5 / 10, r.of(d), 1e-12);
        // a's and d's first readings are left out; a's and d's second, and c's only, are counted.
        assertEquals(2, r.leftOut());
        assertEquals(3, r.readings());
    }

    @Test
    void anAttachedThreadsReadingsAreLeftOutUntilTheCPUFromBeforeTheAttachIsSpent() {
        // main returns at 2.9 s having used 2.2 s of CPU, and the launcher attaches its native
        // thread again as DestroyJavaVM, which only waits. The JVM caps a reading at one core
        // (1/12) and carries the rest: 0.1 s at 3 s, 1 s at 4 s, 1 s at 5 s, the last 0.1 s at
        // 6 s (1/120). All four are main's CPU again and are left out; the reading at 7 s, 0.001,
        // is DestroyJavaVM's own and counts.
        final ThreadRef destroy = new ThreadRef(30, "DestroyJavaVM");
        final ThreadCpu cpu = new ThreadCpu();
        for (int s = 1; s <= 7; s++) {
            cpu.add(A, s * S, 0.05);
            cpu.add(B, s * S, 0.05);
        }
        cpu.start(destroy, 2 * S + 9 * S / 10, true);
        for (int s = 3; s <= 5; s++) {
            cpu.add(destroy, s * S, 1.0 / 12);
        }
        cpu.add(destroy, 6 * S, 1.0 / 120);
        cpu.add(destroy, 7 * S, 0.001);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(0.001 / 10, r.of(destroy), 1e-12);
        // a's and b's first readings, and DestroyJavaVM's four.
        assertEquals(6, r.leftOut());
        assertEquals(13, r.readings());
    }

    @Test
    void withNoReadingAtOneCoreOnlyAnAttachedThreadsFirstReadingIsLeftOut() {
        // The largest reading, 0.07, is c's first after its attach, and it is not 1/N: no reading
        // in the file reached the cap, so nothing was carried. c's second reading counts.
        final ThreadRef c = new ThreadRef(3, "Thread-0");
        final ThreadCpu cpu = new ThreadCpu();
        for (int s = 1; s <= 3; s++) {
            cpu.add(A, s * S, 0.01);
            cpu.add(B, s * S, 0.01);
        }
        cpu.start(c, S + S / 2, true);
        cpu.add(c, 2 * S, 0.07);
        cpu.add(c, 3 * S, 0.05);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(0.05 / 10, r.of(c), 1e-12);
        // a's and b's first readings, and c's first.
        assertEquals(3, r.leftOut());
    }

    @Test
    void theVmsOwnMainCountsItsFirstReadingThoughItIsAttached() {
        // HotSpot attaches the thread that creates the VM as main; before that its native thread
        // ran only the launcher. Its first reading, at the cap, is main's own start-up work.
        final ThreadRef main = new ThreadRef(1, ThreadCpu.VM_CREATOR);
        final ThreadCpu cpu = new ThreadCpu();
        cpu.start(main, S / 2, true);
        cpu.add(main, S, 1.0 / 12);
        cpu.add(A, S, 0.01);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(1.0 / 12 / 2 / 10, r.of(main), 1e-12);
        assertEquals(1, r.leftOut());
    }

    @Test
    void aLifeJavaStartsAfterAnAttachedOneCountsItsFirstReading() {
        // c is attached at 0.5 s and read at 1 s (left out), then ends; Java code starts it again at
        // 2.5 s, read at 3 s: that start is not an attach, so the reading counts from it.
        final ThreadRef c = new ThreadRef(3, "Thread-7");
        final ThreadCpu cpu = new ThreadCpu();
        for (int s = 1; s <= 3; s++) {
            cpu.add(A, s * S, 0.1);
            cpu.add(B, s * S, 0.1);
        }
        cpu.start(c, S / 2, true);
        cpu.add(c, S, 0.4);
        cpu.start(c, 2 * S + S / 2, false);
        cpu.add(c, 3 * S, 0.4);
        final ThreadCpu.Result r = cpu.result(info(10, true));
        assertEquals(0.4 * 0.5 / 10, r.of(c), 1e-12);
        assertEquals(3, r.leftOut());
    }

    @Test
    void withoutReadingsOrAWindowTheShareIsUnknownNotZero() {
        assertSame(ThreadCpu.Result.UNKNOWN, new ThreadCpu().result(info(10, true)));
        final ThreadCpu cpu = new ThreadCpu();
        cpu.add(A, S, 0.5);
        cpu.add(A, 2 * S, 0.5);
        final ThreadCpu.Result r = cpu.result(info(0, true));
        assertSame(ThreadCpu.Result.UNKNOWN, r);
        assertFalse(r.isKnown());
        assertTrue(Double.isNaN(r.share()));
        // Without jdk.ThreadStart a thread's own readings still bound the stretches after its first.
        assertEquals(0.05, cpu.result(info(10, false)).share(), 1e-12);
    }
}
