// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.util.Glob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --json} read back as a program reads it, through {@link JsonParser}: the writer's
 * edge cases, and each document's contract on hand-built reports with exact values.
 */
class JsonTest {

    static final long MS = 1_000_000L;
    /** The span starts one second after the epoch, so instants and offsets differ. */
    static final long T0 = 1_000 * MS;

    static RecordingInfo info() {
        return new RecordingInfo(Path.of("dir", "rec.jfr"), new Interval(T0, T0 + 2_000 * MS), 1, Map.of(),
                Map.of("jdk.JavaMonitorEnter", Map.of("enabled", "true", "threshold", "10 ms"),
                        "jdk.NativeMethodSample", Map.of("enabled", "true", "period", "20 ms")),
                Set.of(), List.of("a file warning"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(final Map<String, Object> doc, final String name) {
        return (List<Map<String, Object>>) doc.get(name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(final Map<String, Object> doc, final String name) {
        return (Map<String, Object>) doc.get(name);
    }

    @Test
    void theWriterEscapesAndSaysNullForWhatJsonCannotCarry() {
        final Json.Writer w = new Json.Writer();
        w.object();
        w.name("text").value("a \"quoted\" \\ back\nslash\t\u0001");
        w.name("nan").value(Double.NaN);
        w.name("infinite").value(Double.POSITIVE_INFINITY);
        w.name("absent").value(Nulls.LONG_NULL);
        w.name("none").value((String) null);
        w.name("empty").array();
        w.end();
        w.name("nested").array();
        w.object();
        w.name("n").value(1);
        w.end();
        w.value(2.5);
        w.end();
        final Map<String, Object> doc = JsonParser.object(w.finish());
        assertEquals("a \"quoted\" \\ back\nslash\t\u0001", doc.get("text"));
        assertNull(doc.get("nan"));
        assertNull(doc.get("infinite"));
        assertNull(doc.get("absent"));
        assertTrue(doc.containsKey("none"));
        assertEquals(List.of(), doc.get("empty"));
        assertEquals(List.of(Map.of("n", 1L), 2.5), doc.get("nested"));
        // The parser is strict, so a malformed document fails the tests that read one.
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{\"a\":1,}"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("[NaN]"));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("{} {}"));
    }

    @Test
    void theStallsDocumentCarriesWhatTheTextSaysInUnitsAProgramCanUse() {
        final ThreadRef loop = new ThreadRef(7, "event-loop-1");
        final ThreadRef idle = new ThreadRef(8, "idle-1");
        final Stack stack = new Stack(List.of(new Frame("java.lang.Thread", "sleep0", 0, "Native"),
                new Frame("dev.app.Handler", "read", 42, "JIT compiled")), false);
        final Stack lambda = new Stack(List.of(new Frame("java.lang.Thread", "sleep0", 0, "Native"),
                new Frame("dev.app.Handler$$Lambda.0x0000007e0198f238", "run", 0, "JIT compiled")), false);
        final Stall sleep = new Stall(loop, new Interval(T0 + 100 * MS, T0 + 400 * MS), Stall.Verdict.SLEEP,
                "Thread.sleep", stack, Stall.Evidence.EVENT, 0);
        final Stall gap = new Stall(loop, new Interval(T0 + 500 * MS, T0 + 600 * MS), Stall.Verdict.UNEXPLAINED,
                "no samples", Stack.EMPTY, Stall.Evidence.SILENCE, 0);
        final Stall inLambda = new Stall(loop, new Interval(T0 + 700 * MS, T0 + 760 * MS), Stall.Verdict.SLEEP,
                "Thread.sleep", lambda, Stall.Evidence.EVENT, 0);
        final Pause gc = new Pause(new Interval(T0 + 900 * MS, T0 + 1_000 * MS), PauseKind.GC, "G1 Young");
        final StallReport r = new StallReport(info(), 50 * MS, List.of(
                new StallReport.ThreadSummary(loop, 100, 0, 571 * MS, 2, 400 * MS, 300 * MS, 1_750 * MS,
                        StallReport.Sight.NATIVE_SAMPLER, 571 * MS),
                new StallReport.ThreadSummary(idle, 0, 0, 0, 0, 0, 0)), List.of(sleep, gap, inLambda), List.of(gc),
                List.of("a warning"));
        final Map<String, Object> doc = JsonParser.object(Json.stalls(r, 15, "9.9.9"));

        assertEquals("jfrq", doc.get("tool"));
        assertEquals("9.9.9", doc.get("version"));
        assertEquals((long) Json.SCHEMA, doc.get("schema"));
        assertEquals("stalls", doc.get("command"));
        final Map<String, Object> recording = map(doc, "recording");
        assertEquals("rec.jfr", recording.get("file"));
        assertEquals(Path.of("dir", "rec.jfr").toString(), recording.get("path"));
        assertEquals("1970-01-01T00:00:01Z", recording.get("start"));
        assertEquals(2_000 * MS, recording.get("durationNanos"));
        assertEquals(List.of("a file warning"), recording.get("warnings"));
        assertEquals(50 * MS, doc.get("gapNanos"));
        assertEquals(20 * MS, map(doc, "samplingPeriodNanos").get("native"));
        assertNull(map(doc, "samplingPeriodNanos").get("java"));
        assertEquals(10 * MS, map(doc, "thresholdNanos").get("jdk.JavaMonitorEnter"));
        assertNull(map(doc, "thresholdNanos").get("jdk.ThreadPark"));
        assertEquals(r.unseenHeadline(), doc.get("unseenHeadline"));
        assertEquals(r.unseen(), doc.get("unseen"));
        assertEquals(List.of("a warning"), doc.get("warnings"));

        // The thread with no stall is counted, not listed, as in the text.
        assertEquals(1L, doc.get("threadsWithStalls"));
        assertEquals(1L, doc.get("threadsWithoutStalls"));
        final Map<String, Object> t = list(doc, "threads").getFirst();
        assertEquals("event-loop-1", t.get("thread"));
        assertEquals(7L, t.get("threadId"));
        assertNull(t.get("javaCadenceNanos"));
        assertEquals(571 * MS, t.get("nativeCadenceNanos"));
        assertEquals("NATIVE_SAMPLER", t.get("sight"));
        assertEquals(1_750 * MS, t.get("unseenBelowNanos"));

        // Explained stalls and unexplained gaps are two lists, as in the text.
        assertEquals(2L, doc.get("stallsFound"));
        final Map<String, Object> s = list(doc, "stalls").getFirst();
        assertEquals("1970-01-01T00:00:01.100Z", s.get("start"));
        assertEquals(100 * MS, s.get("offsetNanos"));
        assertEquals(300 * MS, s.get("durationNanos"));
        assertEquals("SLEEP", s.get("verdict"));
        assertEquals("EVENT", s.get("evidence"));
        final Map<String, Object> st = map(s, "stack");
        assertEquals(List.of("java.lang.Thread.sleep0(Native Method)", "dev.app.Handler.read(Handler.java:42)"),
                st.get("frames"));
        assertEquals("dev.app.Handler.read", st.get("culprit"));
        assertEquals(false, st.get("truncated"));
        // A lambda's culprit carries no per-JVM class address, so two runs name it alike.
        assertEquals("dev.app.Handler$$Lambda.run", map(list(doc, "stalls").get(1), "stack").get("culprit"));
        assertEquals(1L, doc.get("unexplainedFound"));
        assertNull(list(doc, "unexplained").getFirst().get("stack"));
        assertEquals("GC", list(doc, "pauses").getFirst().get("kind"));
        assertEquals(List.of(Map.of("verdict", "SLEEP", "stalls", 2L, "stalledNanos", 360 * MS, "worstNanos", 300 * MS),
                Map.of("verdict", "UNEXPLAINED", "stalls", 1L, "stalledNanos", 100 * MS, "worstNanos", 100 * MS)),
                doc.get("byVerdict"));

        // --top bounds the lists and the counts say what was cut.
        final List<Stall> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(new Stall(loop, new Interval(T0 + i * 100 * MS, T0 + i * 100 * MS + 60 * MS),
                    Stall.Verdict.SLEEP, "", stack, Stall.Evidence.EVENT, 0));
        }
        final Map<String, Object> cut = JsonParser.object(Json.stalls(new StallReport(info(), 50 * MS, List.of(),
                many, List.of(), List.of()), 2, "9.9.9"));
        assertEquals(5L, cut.get("stallsFound"));
        assertEquals(2, list(cut, "stalls").size());
    }

    @Test
    void everyFamilyGlobMatchesItsThreadsAndAThreadOfOneMatchesOnlyItself() {
        final List<ThreadRef> threads = List.of(new ThreadRef(1, "pool-3-thread-1"), new ThreadRef(2, "pool-12-thread-7"),
                new ThreadRef(3, "Netty-worker-1"), new ThreadRef(4, "Netty-worker-2"), new ThreadRef(5, "G1 Main Marker"),
                new ThreadRef(6, "odd[1]*,name"), new ThreadRef(7, "odd[2]*,name"), new ThreadRef(8, "main"),
                new ThreadRef(9, "Netty-worker-main"), new ThreadRef(10, " idle "));
        final RecordingInfo recording = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, Map.of(), Map.of(),
                Set.copyOf(threads), List.of());
        final Map<String, Object> doc = JsonParser.object(Json.info(recording, ThreadCensus.Result.UNKNOWN, "9.9.9"));
        final List<Map<String, Object>> families = list(doc, "threadFamilies");
        assertEquals(7, families.size(), families.toString());
        for (final Map<String, Object> f : families) {
            final Glob glob = Glob.of((String) f.get("glob"));
            final String family = (String) f.get("family");
            final long members = threads.stream().filter(t -> RecordingSummary.family(t.name()).equals(family)).count();
            assertEquals(f.get("threads"), members, family);
            for (final ThreadRef t : threads) {
                final boolean member = RecordingSummary.family(t.name()).equals(family);
                // Every member matches; a family of one is a literal and matches only itself. A
                // wildcard can reach another family's thread (Netty-worker-* and Netty-worker-main).
                if (member || f.get("threads").equals(1L)) {
                    assertEquals(member, glob.test(t.name()), f.get("glob") + " on '" + t.name() + "'");
                }
            }
            // Life counts the recording cannot give are null, not zero.
            assertNull(f.get("aliveAtEnd"));
        }
        final Map<String, Object> netty = families.stream().filter(f -> f.get("family").equals("Netty-worker-N"))
                .findFirst().orElseThrow();
        assertEquals("Netty-worker-*", netty.get("glob"));
        assertNull(map(doc, "threadsAlive").get("atEnd"));
    }

    @Test
    void aDiffHasNoRatioWhereTheBaselineHadNothing() {
        final AllocationReport before = new AllocationReport(info(), "jdk.ObjectAllocationSample", 0, 0, 0, Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE);
        final AllocationReport after = new AllocationReport(info(), "jdk.ObjectAllocationSample", 2_000, 10, 10,
                Map.of(), Map.of("worker-1", 2_000L), Map.of("byte[]", 2_000L), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        final Map<String, Object> doc = JsonParser.object(Json.allocDiff(new AllocationDiff(before, after), 15, false,
                SiteKey.culpritMethod(), "9.9.9"));
        final Map<String, Object> thread = list(doc, "threads").getFirst();
        assertEquals("worker-1", thread.get("thread"));
        assertEquals(0L, thread.get("bytesPerSecondBefore"));
        assertEquals(1_000L, thread.get("bytesPerSecondAfter"));
        assertTrue(thread.containsKey("ratio"));
        assertNull(thread.get("ratio"));
        // Both recordings are named: the baseline in its own object, the current one in the envelope.
        assertEquals("rec.jfr", map(map(doc, "baseline"), "recording").get("file"));
        assertEquals("rec.jfr", map(doc, "recording").get("file"));
        assertEquals(1_000L, map(doc, "change").get("bytesPerSecondAfter"));
    }
}
