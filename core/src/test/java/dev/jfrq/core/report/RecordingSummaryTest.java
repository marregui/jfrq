// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Glob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingSummaryTest {

    @Test
    void threadFamiliesFoldEveryNumberAPoolVaries() {
        assertEquals("pool-N-thread-N", RecordingSummary.family("pool-36-thread-2"));
        assertEquals("milo-shared-thread-pool-N", RecordingSummary.family("milo-shared-thread-pool-17"));
        assertEquals("main", RecordingSummary.family("main"));
        assertEquals("RMI TCP Connection(N)-N.N.N.N", RecordingSummary.family("RMI TCP Connection(1)-192.168.1.120"));
    }

    @Test
    void familiesCountEveryThreadAndNameTheFirstByName() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(new ThreadRef(1, "worker-9"), new ThreadRef(2, "worker-10"), new ThreadRef(3, "worker-9"),
                        new ThreadRef(4, "main")), List.of());
        // Two threads that share a name are two threads; the example does not depend on hash order.
        final int unknown = Nulls.INT_NULL;
        assertEquals(List.of(new RecordingSummary.Family("main", 1, 1, 0, unknown, unknown, unknown, unknown, unknown,
                        Double.NaN, "main"),
                new RecordingSummary.Family("worker-N", 3, 3, 0, unknown, unknown, unknown, unknown, unknown,
                        Double.NaN, "worker-10")),
                RecordingSummary.threadFamilies(info, ThreadCensus.Result.UNKNOWN));
        assertEquals(List.of("Family", "Threads", "Seen", "Example"),
                RecordingSummary.familyHeaders(ThreadCensus.Result.UNKNOWN, List.of()));
    }

    @Test
    void aFamilyOfVirtualThreadsHasNoLifeCountsRatherThanZero() {
        final ThreadRef pooled = new ThreadRef(1, "pool-1-thread-1");
        final ThreadRef churn = new ThreadRef(2, "churn-0", true);
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(pooled, churn), List.of());
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(), Map.of(pooled, 1L), Map.of(),
                Set.of(pooled), null);
        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, census);
        assertEquals(List.of("churn-N", "pool-N-thread-N"), families.stream().map(RecordingSummary.Family::name).toList());
        // Neither the census nor ThreadStart/ThreadEnd covers a virtual thread; the table says
        // which dashes are virtual threads, so they do not read like a VM thread's.
        assertEquals(List.of("Family", "Threads", "Seen", "Virtual", "At start", "Started", "Ended", "At end", "Example"),
                RecordingSummary.familyHeaders(census, families));
        assertArrayEquals(new Object[] {"churn-0", 1, 1, 1, "—", "—", "—", "—", ""},
                RecordingSummary.familyCells(families.getFirst(), census, families));
        assertArrayEquals(new Object[] {"pool-1-thread-1", 1, 1, "", 0, 1, 0, 1, ""},
                RecordingSummary.familyCells(families.get(1), census, families));
        // Without a virtual thread there is no such column.
        assertEquals(List.of("Family", "Threads", "Seen", "At start", "Started", "Ended", "At end", "Example"),
                RecordingSummary.familyHeaders(census, families.subList(1, 2)));
    }

    @Test
    void aFamilyTheCensusNeverNamedHasNoLifeCounts() {
        // A GC worker is seen in events but has no Java identity: in no census, never started or ended.
        final ThreadRef gc = new ThreadRef(9001, "GC Thread#0");
        final ThreadRef pooled = new ThreadRef(1, "pool-1-thread-1");
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(gc, pooled), List.of());
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(pooled), Map.of(), Map.of(),
                Set.of(pooled), Set.of(pooled));
        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, census);
        assertArrayEquals(new Object[] {"GC Thread#0", 1, 1, "—", "—", "—", "—", ""},
                RecordingSummary.familyCells(families.getFirst(), census, families));
        assertArrayEquals(new Object[] {"pool-1-thread-1", 1, 1, 1, 0, 0, 1, ""},
                RecordingSummary.familyCells(families.get(1), census, families));
    }

    @Test
    void threadNamesAreFoldedByPoolOnlyWhenTheyDoNotFit() {
        final List<ThreadRef> two = List.of(new ThreadRef(1, "event-loop-3-1"), new ThreadRef(2, "event-loop-3-2"));
        assertEquals("event-loop-3-1, event-loop-3-2", RecordingSummary.threadNames(two, 4));
        final List<ThreadRef> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(new ThreadRef(i, "ForkJoinPool.commonPool-worker-" + i));
        }
        many.add(new ThreadRef(20, "main"));
        many.add(new ThreadRef(21, "main"));
        assertEquals("ForkJoinPool.commonPool-worker-N* (11 threads), main (2 threads)",
                RecordingSummary.threadNames(many, 4));
        final List<ThreadRef> distinct = new ArrayList<>();
        for (final String n : List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta")) {
            distinct.add(new ThreadRef(distinct.size(), n));
        }
        assertEquals("alpha, beta, gamma, delta (+2 more)", RecordingSummary.threadNames(distinct, 4));
    }

    @Test
    void settingsNameTheThresholdThenThePeriodThenTheThrottle() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(),
                Map.of("jdk.ThreadPark", Map.of("enabled", "true", "threshold", "10 ms", "period", "1 s"),
                        "jdk.ExecutionSample", Map.of("enabled", "true", "period", "20 ms"),
                        "jdk.ObjectAllocationSample", Map.of("enabled", "true", "throttle", "150/s")),
                Set.of(), List.of());
        assertEquals("ThreadPark 10.0 ms, ExecutionSample 20.0 ms, ObjectAllocationSample 150/s",
                RecordingSummary.settings(info, "jdk.ThreadPark", "jdk.ExecutionSample", "jdk.SocketRead",
                        "jdk.ObjectAllocationSample"));
        assertEquals("", RecordingSummary.settings(info, "jdk.SocketRead"));
    }

    @Test
    void theThrottledLineNamesTheThrottleEvenWhereAThresholdIsSetToo() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(),
                Map.of("jdk.FileRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "300/s"),
                        "jdk.ThreadPark", Map.of("enabled", "true", "threshold", "10 ms"),
                        "jdk.SocketRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "off")),
                Set.of(), List.of());
        assertEquals("FileRead 300/s", RecordingSummary.throttles(info, "jdk.FileRead", "jdk.ThreadPark",
                "jdk.SocketRead"));
    }

    static final long MS = 1_000_000L;
    static final ThreadRef PARENT = new ThreadRef(100, "submitter-1");

    static Stack started(final String appClass) {
        return new Stack(List.of(new Frame("java.lang.Thread", "start", 1, false),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "addWorker", 2, false),
                new Frame(appClass, "submit", 3, false), new Frame("com.example.Main", "main", 4, false)), false);
    }

    static ThreadCensus.Result withStarts(final List<ThreadCensus.Start> starts) {
        return new ThreadCensus.Result(null, null, null, null, null, Set.of(), starts,
                dev.jfrq.core.health.ThreadCpu.Result.UNKNOWN);
    }

    @Test
    void startsSayHowManyTheBusiestWindowsAndWhichCodeStartedThem() {
        final List<ThreadCensus.Start> starts = new ArrayList<>();
        // Twelve pool workers: ten within 90 ms, two a second later; one attach; one other family.
        for (int i = 0; i < 10; i++) {
            starts.add(new ThreadCensus.Start(1_000 * MS + i * 10 * MS, new ThreadRef(i, "pool-1-thread-" + i), PARENT,
                    started("com.example.Listeners"), false));
        }
        starts.add(new ThreadCensus.Start(2_000 * MS, new ThreadRef(10, "pool-1-thread-10"), PARENT,
                started("com.example.Retry"), false));
        starts.add(new ThreadCensus.Start(2_050 * MS, new ThreadRef(11, "pool-1-thread-11"), null, Stack.EMPTY, true));
        starts.add(new ThreadCensus.Start(2_060 * MS, new ThreadRef(12, "pool-1-thread-12"), PARENT, Stack.EMPTY,
                false));
        starts.add(new ThreadCensus.Start(2_070 * MS, new ThreadRef(13, "other-1"), PARENT, started("x.Y"), false));
        final RecordingSummary.Starts s = RecordingSummary.starts(withStarts(starts), Glob.of("pool-*"), 2);
        assertEquals(13, s.starts());
        assertEquals(13, s.threads());
        assertEquals(1, s.attached());
        assertEquals(1_000 * MS, s.firstNanos());
        assertEquals(2_060 * MS, s.lastNanos());
        assertEquals(new RecordingSummary.Peak(100 * MS, 10, 1_000 * MS), s.burst());
        // In one second from the first: the ten, and nothing at exactly +1 s, which is the next window's.
        assertEquals(new RecordingSummary.Peak(1_000 * MS, 10, 1_000 * MS), s.second());
        assertEquals(4, s.creatorsFound());
        assertEquals(2, s.creators().size(), "--top 2");
        final RecordingSummary.Creator top = s.creators().getFirst();
        assertEquals("com.example.Listeners.submit", top.site());
        assertEquals(10, top.starts());
        assertEquals(10.0 / 13, top.share(), 1e-12);
        assertEquals(Set.of(PARENT), top.parents());
        // The stack from the site down: the frames above it start a thread the same way for every pool.
        assertEquals("submit", top.stack().frames().getFirst().method());
        assertEquals(2, top.stack().depth());
        // Ties are by site name.
        assertEquals(RecordingSummary.ATTACHED, s.creators().get(1).site());
        assertEquals(Set.of(), s.creators().get(1).parents());

        final RecordingSummary.Starts all = RecordingSummary.starts(withStarts(starts), Glob.of("pool-*"), 15);
        assertEquals(List.of("com.example.Listeners.submit", RecordingSummary.ATTACHED, RecordingSummary.NO_STACK,
                "com.example.Retry.submit"), all.creators().stream().map(RecordingSummary.Creator::site).toList());
        assertTrue(RecordingSummary.startsLine(all, 0).startsWith("13 starts of 13 threads, from +1.000s "),
                RecordingSummary.startsLine(all, 0));
        assertTrue(RecordingSummary.startsLine(all, 0).endsWith("; 1 attached from native code"));
        assertTrue(RecordingSummary.peaksLine(all, 0).startsWith("10 in 100 ms from +1.000s 00:00:01.000Z; 10 in "),
                RecordingSummary.peaksLine(all, 0));
    }

    @Test
    void noMatchingStartIsSaidAndTheWindowsAreEmpty() {
        final RecordingSummary.Starts none = RecordingSummary.starts(withStarts(List.of()), Glob.of("nothing-*"), 5);
        assertEquals(0, none.starts());
        assertEquals(Nulls.LONG_NULL, none.firstNanos());
        assertEquals(new RecordingSummary.Peak(100 * MS, 0, Nulls.LONG_NULL), none.burst());
        assertEquals("no jdk.ThreadStart of a thread matching nothing-* in the recording",
                RecordingSummary.startsLine(none, 0));
        // A JDK-only stack names its first frame, as a culprit does.
        final Stack jdkOnly = new Stack(List.of(new Frame("java.lang.Thread", "start", 1, false)), false);
        final RecordingSummary.Starts jdk = RecordingSummary.starts(withStarts(List.of(new ThreadCensus.Start(0,
                new ThreadRef(1, "w"), PARENT, jdkOnly, false))), Glob.any(), 5);
        assertEquals("java.lang.Thread.start", jdk.creators().getFirst().site());
        assertEquals(jdkOnly, jdk.creators().getFirst().stack());
    }

    @Test
    void labelsTakeAsManyDirectoriesAsItTakesToTellRecordingsApart() {
        assertEquals(List.of("a.jfr", "b.jfr"), RecordingSummary.labels(List.of(Path.of("x", "a.jfr"),
                Path.of("y", "b.jfr"))));
        assertEquals(List.of(Path.of("n1", "run", "node.jfr").toString(), Path.of("n2", "run", "node.jfr").toString()),
                RecordingSummary.labels(List.of(Path.of("r", "n1", "run", "node.jfr"), Path.of("r", "n2", "run",
                        "node.jfr"))));
        // The same file twice cannot be told apart by a suffix: its whole path, root and all, twice.
        final List<String> same = RecordingSummary.labels(List.of(Path.of("a.jfr"), Path.of("a.jfr")));
        assertEquals(List.of(Path.of("a.jfr").toAbsolutePath().toString(), Path.of("a.jfr").toAbsolutePath().toString()),
                same);
    }

    @Test
    void theAttachedAndCpuColumnsAreThereOnlyWhenTheRecordingCanFillThem() {
        final ThreadRef worker = new ThreadRef(1, "worker-1");
        final ThreadRef callback = new ThreadRef(2, "Thread-7");
        final ThreadRef gc = new ThreadRef(9001, "GC Thread#0");
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.of(worker, callback, gc), List.of());
        final dev.jfrq.core.health.ThreadCpu.Result cpu = new dev.jfrq.core.health.ThreadCpu.Result(
                Map.of(worker, 0.25), 0.25, 9, 2);
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(worker), Map.of(callback, 1L), Map.of(),
                Set.of(worker, callback), Set.of(worker, callback), Set.of(callback), List.of(), cpu);
        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, census);
        assertEquals(List.of("Family", "Threads", "Seen", "At start", "Started", "Ended", "At end", "Attached", "CPU",
                "Example"), RecordingSummary.familyHeaders(census, families));
        // A JVM thread is measured by neither; a Java thread with no reading used under a millisecond a period.
        assertArrayEquals(new Object[] {"GC Thread#0", 1, 1, "—", "—", "—", "—", "—", "—", ""},
                RecordingSummary.familyCells(families.get(0), census, families));
        assertArrayEquals(new Object[] {"Thread-7", 1, 1, 0, 1, 0, 1, 1, "0.0%", ""},
                RecordingSummary.familyCells(families.get(1), census, families));
        assertArrayEquals(new Object[] {"worker-1", 1, 1, 1, 0, 0, 1, "", "25.0%", ""},
                RecordingSummary.familyCells(families.get(2), census, families));
        final List<String> notes = RecordingSummary.familyNotes(census, families);
        assertEquals(2, notes.size());
        assertTrue(notes.get(0).startsWith("Attached: "), notes.get(0));
        assertTrue(notes.get(1).contains("; 2 readings left out: of threads whose start is not in the file"),
                notes.get(1));
        // Readings stand on their own: a family outside the census that has them shows them.
        final ThreadCensus.Result noCensus = new ThreadCensus.Result(null, null, null, null, Set.of(callback), null,
                List.of(), new dev.jfrq.core.health.ThreadCpu.Result(Map.of(worker, 0.25), 0.25, 9, 2));
        final List<RecordingSummary.Family> outside = RecordingSummary.threadFamilies(info, noCensus);
        assertEquals(0.25, outside.get(2).cpu(), 1e-12);
        assertTrue(Double.isNaN(outside.get(0).cpu()), "the collector's thread has no reading");
        // Neither column, and no note, when the recording has neither.
        final ThreadCensus.Result plain = new ThreadCensus.Result(Set.of(worker), Map.of(), Map.of(), Set.of(worker),
                Set.of(worker));
        final List<RecordingSummary.Family> plainFamilies = RecordingSummary.threadFamilies(info, plain);
        assertFalse(RecordingSummary.familyHeaders(plain, plainFamilies).contains("CPU"));
        assertTrue(RecordingSummary.familyNotes(plain, plainFamilies).isEmpty());
    }
}
