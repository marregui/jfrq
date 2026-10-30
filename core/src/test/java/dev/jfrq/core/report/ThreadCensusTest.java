// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.core.health.HealthCollector;
import dev.jfrq.core.health.ThreadCpu;
import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.NativeThreads;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Glob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ThreadCensus}: the counting rules on hand-fed rows and lives, with exact
 * expectations, then one real recording for what the JVM writes.
 */
class ThreadCensusTest {

    static final ThreadRef A = new ThreadRef(1, "a");
    static final ThreadRef B = new ThreadRef(2, "b");
    static final ThreadRef C = new ThreadRef(3, "c");
    static final ThreadRef D = new ThreadRef(4, "d");
    static final ThreadRef X = new ThreadRef(5, "x");

    @TempDir
    Path dir;

    /** A recording over [0, 100) whose settings enable the lifetime events, or not. */
    static RecordingInfo info(final boolean lifetimes) {
        final Map<String, Map<String, String>> settings = lifetimes
                ? Map.of("jdk.ThreadStart", Map.of("enabled", "true"), "jdk.ThreadEnd", Map.of("enabled", "true"))
                : Map.of();
        return new RecordingInfo(Path.of("a.jfr"), new Interval(0, 100), 1, Map.of(), settings, Set.of(), List.of());
    }

    @Test
    void onlyTheLivesBetweenTheTwoCensusesCountAndTheyAddUp() {
        final ThreadCensus census = new ThreadCensus();
        // Delivered out of time order, as a file's chunks can be.
        census.row(100, A);
        census.life(60, D, false);
        census.row(100, C);
        census.life(50, B, true);
        census.row(10, A);
        census.life(40, C, false);
        census.row(100, D);
        census.row(10, B);
        // Before the first census and after the last: already in them, or not this window's.
        census.life(5, X, false);
        census.life(150, A, true);
        // A start for a thread the first census already has is not a new life.
        census.life(20, A, false);
        census.finish(info(true));

        final ThreadCensus.Result r = census.result();
        assertEquals(Set.of(A, B), r.aliveAtStart());
        assertEquals(Map.of(C, 1L, D, 1L), r.started());
        assertEquals(Map.of(B, 1L), r.ended());
        assertEquals(Set.of(A, C, D), r.aliveAtEnd());
        assertEquals(r.aliveAtStart().size() + r.starts() - r.ends(), r.aliveAtEnd().size());
        assertEquals("platform threads: 2 alive at start, 2 started, 1 ended, 3 alive at end",
                RecordingSummary.lives(r));
    }

    @Test
    void aThreadRestartedUnderOneIdCountsEveryLife() {
        // A dynamic compiler thread: stopped and started again, twice, under one id.
        final ThreadCensus census = new ThreadCensus();
        census.row(10, A);
        census.life(20, A, true);
        census.life(30, A, false);
        census.life(40, A, true);
        census.life(50, A, false);
        census.row(90, A);
        census.finish(info(true));
        assertEquals(Map.of(A, 2L), census.result().started());
        assertEquals(Map.of(A, 2L), census.result().ended());
    }

    @Test
    void whatTheRecordingCannotSayIsUnknownNotZero() {
        // No lifetime events: starts and ends are unknown, the census still says who was alive.
        final ThreadCensus noLives = new ThreadCensus();
        noLives.row(10, A);
        noLives.row(90, A);
        noLives.row(90, B);
        noLives.finish(info(false));
        assertNull(noLives.result().started());
        assertEquals("platform threads: 1 alive at start, 2 alive at end", RecordingSummary.lives(noLives.result()));
        assertEquals(List.of("Family", "Threads", "Seen", "At start", "At end", "Example"),
                RecordingSummary.familyHeaders(noLives.result(), List.of()));

        // One census only, near the end: it is the end's, and every start and end in the file counts.
        final ThreadCensus late = new ThreadCensus();
        late.life(30, B, false);
        late.row(95, A);
        late.row(95, B);
        late.finish(info(true));
        assertNull(late.result().aliveAtStart());
        assertEquals(Set.of(A, B), late.result().aliveAtEnd());
        assertEquals(Map.of(B, 1L), late.result().started());

        final ThreadCensus nothing = new ThreadCensus();
        nothing.finish(info(false));
        assertEquals(ThreadCensus.Result.UNKNOWN, nothing.result());
        assertEquals("", RecordingSummary.lives(nothing.result()));
    }

    @Test
    void aRecordingCountsWhoWasAliveWhoStartedAndWhoEnded() throws Exception {
        // "silent" is parked through the whole recording, so no event names it but the census;
        // "stayer" starts inside and is still alive at the end; "gone-N" start and end inside.
        final CountDownLatch release = new CountDownLatch(1);
        final Thread silent = new Thread(() -> await(release), "silent");
        silent.start();
        final Thread[] stayer = new Thread[1];
        final Path file = JfrFixtures.record(dir, "census", r -> {
            r.enable("jdk.ThreadStart");
            r.enable("jdk.ThreadEnd");
            r.enable("jdk.ThreadAllocationStatistics").with("period", "everyChunk");
        }, () -> {
            stayer[0] = new Thread(() -> await(release), "stayer");
            stayer[0].start();
            JfrFixtures.onThread("gone-1", () -> JfrFixtures.sleep(1));
            JfrFixtures.onThread("gone-2", () -> JfrFixtures.sleep(1));
            // A joined thread is gone in Java before the JVM has torn it down: stopped at once, the
            // closing census can still list gone-2 and its ThreadEnd come after it (seen on Windows).
            JfrFixtures.sleep(500);
        });
        release.countDown();
        silent.join();
        stayer[0].join();

        final ThreadCensus census = new ThreadCensus();
        final RecordingInfo info = JfrReader.read(file, census);
        final ThreadCensus.Result r = census.result();
        assertTrue(names(r.aliveAtStart()).contains("silent"), r.toString());
        assertTrue(names(r.aliveAtEnd()).containsAll(Set.of("silent", "stayer")), r.toString());
        assertFalse(names(r.aliveAtEnd()).contains("gone-1"), r.toString());
        assertTrue(names(r.started().keySet()).containsAll(Set.of("stayer", "gone-1", "gone-2")), r.toString());
        assertTrue(names(r.ended().keySet()).containsAll(Set.of("gone-1", "gone-2")), r.toString());
        assertEquals(r.aliveAtStart().size() + r.starts() - r.ends(), r.aliveAtEnd().size(), r.toString());
        // Only the census names the silent thread, and the families take it in all the same.
        assertFalse(names(info.threads()).contains("silent"));
        final RecordingSummary.Family family = RecordingSummary.threadFamilies(info, r).stream()
                .filter(f -> f.name().equals("silent")).findFirst().orElseThrow();
        assertEquals(0, family.seen());
        assertEquals(1, family.aliveAtEnd());
    }

    @Test
    void aStartWithNeitherParentNorStackIsAnAttachAndTheStartsKeepTheirOrder() {
        final ThreadCensus census = new ThreadCensus();
        final Stack made = new Stack(List.of(new Frame("com.example.Pool", "grow", 12, false)), false);
        census.row(10, A);
        // File order, not time order.
        census.start(60, C, null, Stack.EMPTY, true);
        census.start(40, B, A, made, false);
        // A start with no parent but a stack is a start the JVM made, not an attach.
        census.start(50, D, null, made, false);
        census.row(90, A);
        census.finish(info(true));
        final ThreadCensus.Result r = census.result();
        assertEquals(Set.of(C), r.attached());
        assertEquals(List.of(B, D, C), r.startEvents().stream().map(ThreadCensus.Start::thread).toList());
        assertEquals(new ThreadCensus.Start(40, B, A, made, false), r.startEvents().getFirst());
        assertTrue(r.startEvents().getLast().isAttached());

        // Starts recorded without their parent cannot tell an attach apart: unknown, not none.
        final ThreadCensus older = new ThreadCensus();
        older.life(40, B, false);
        older.finish(info(true));
        assertNull(older.result().attached());
        assertEquals(1, older.result().startEvents().size());
    }

    @Test
    void aThreadNativeCodeAttachedIsToldApartAndEachThreadHasItsCpu() throws Exception {
        assumeTrue(NativeThreads.available(), "pthread_create");
        final String[] attached = new String[1];
        final Path file = JfrFixtures.record(dir, "attach", r -> {
            r.enable("jdk.ThreadStart").withStackTrace();
            r.enable("jdk.ThreadEnd");
            r.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(50));
        }, () -> {
            attached[0] = NativeThreads.attachOnce();
            JfrFixtures.onThread("spinner", () -> JfrFixtures.burn(600));
            JfrFixtures.onThread("spawner", () -> JfrFixtures.onThread("spawned", () -> JfrFixtures.sleep(1)));
        });
        final ThreadCensus census = new ThreadCensus();
        final RecordingInfo info = JfrReader.read(file, census);
        final ThreadCensus.Result r = census.result();
        assertEquals(Set.of(attached[0]), names(r.attached()), r.startEvents().toString());
        final ThreadCensus.Start spawned = r.startEvents().stream().filter(s -> s.thread().name().equals("spawned"))
                .findFirst().orElseThrow();
        assertFalse(spawned.isAttached());
        assertEquals("spawner", spawned.parent().name());
        assertFalse(spawned.stack().isEmpty());

        final List<RecordingSummary.Family> families = RecordingSummary.threadFamilies(info, r);
        assertTrue(RecordingSummary.familyHeaders(r, families).containsAll(List.of("Attached", "CPU")));
        final RecordingSummary.Family nativeFamily = families.stream()
                .filter(f -> f.name().equals(RecordingSummary.family(attached[0]))).findFirst().orElseThrow();
        assertTrue(nativeFamily.attached() >= 1, nativeFamily.toString());
        final RecordingSummary.Family spinner = families.stream().filter(f -> f.name().equals("spinner"))
                .findFirst().orElseThrow();
        // Burning for 600 ms in readings 50 ms apart: a share above zero, and never above the machine.
        assertTrue(spinner.cpu() > 0 && spinner.cpu() <= 1, spinner.toString());
        assertEquals(2, RecordingSummary.familyNotes(r, families).size());
        final RecordingSummary.Starts starts = RecordingSummary.starts(r, Glob.of(attached[0]), 5);
        assertEquals(1, starts.attached());
        assertEquals(RecordingSummary.ATTACHED, starts.creators().getFirst().site());
    }

    @Test
    void infoAndHealthLeaveOutTheSameAttachedReadings() throws Exception {
        // An attached thread burning 300 ms reads at the cap until it ends: left out by both the
        // census (info) and the health collector, which find the attach each on its own.
        assumeTrue(NativeThreads.available(), "pthread_create");
        final Path file = JfrFixtures.record(dir, "attach-cpu", r -> {
            r.enable("jdk.ThreadStart").withStackTrace();
            r.enable("jdk.ThreadEnd");
            r.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(50));
        }, () -> {
            NativeThreads.attachOnce(300);
            JfrFixtures.onThread("spinner", () -> JfrFixtures.burn(300));
        });
        final ThreadCensus census = new ThreadCensus();
        JfrReader.read(file, census);
        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        final ThreadCpu.Result fromInfo = census.result().cpu();
        final ThreadCpu.Result fromHealth = health.report().threadCpu();
        assertTrue(fromInfo.isKnown());
        assertEquals(fromInfo.leftOut(), fromHealth.leftOut());
        assertEquals(fromInfo.readings(), fromHealth.readings());
        assertEquals(fromInfo.share(), fromHealth.share(), 1e-12);
    }

    private static Set<String> names(final Set<ThreadRef> threads) {
        final Set<String> out = new HashSet<>();
        for (final ThreadRef t : threads) {
            out.add(t.name());
        }
        return out;
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
