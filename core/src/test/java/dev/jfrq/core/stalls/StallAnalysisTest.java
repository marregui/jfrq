// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall.Evidence;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link StallAnalysis} with hand-built timelines. Times are in milliseconds in the
 * helpers and converted to nanoseconds, the sampler period is 10 ms and the gap 50 ms
 * unless a test says otherwise.
 */
class StallAnalysisTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOOP = new ThreadRef(1, "event-loop-1");
    static final ThreadRef OTHER = new ThreadRef(2, "event-loop-2");
    static final ThreadRef HOLDER = new ThreadRef(3, "housekeeper");

    static final Stack IDLE = stack(new Frame("sun.nio.ch.KQueue", "poll", 0, "Native"),
            new Frame("sun.nio.ch.KQueueSelectorImpl", "doSelect", 100, "JIT compiled"));
    static final Stack BURN = stack(new Frame("dev.app.CpuWork", "burn", 14, "JIT compiled"),
            new Frame("dev.app.Handler", "channelRead0", 59, "JIT compiled"));
    static final Stack READ0 = stack(new Frame("sun.nio.ch.SocketDispatcher", "read0", 0, "Native"),
            new Frame("dev.app.Handler", "lookup", 80, "JIT compiled"));

    static Stack stack(final Frame... frames) {
        return new Stack(List.of(frames), false);
    }

    static LongList longs(final long... values) {
        final LongList list = new LongList(values.length);
        for (final long v : values) {
            list.add(v);
        }
        return list;
    }

    static RecordingInfo info(final long spanMillis, final Map<String, Map<String, String>> settings, final String... presentTypes) {
        final Map<String, Long> counts = new HashMap<>();
        for (final String t : presentTypes) {
            counts.put(t, 1L);
        }
        return new RecordingInfo(Path.of("test.jfr"), new Interval(0, spanMillis * MS), 1, counts, settings, Set.of(), List.of());
    }

    static RecordingInfo sampledInfo() {
        return info(10_000, Map.of("jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms")),
                "jdk.ExecutionSample", "jdk.NativeMethodSample");
    }

    /** Idle samples every {@code step} ms over [from, to). */
    static List<Sample> idle(final long from, final long to, final long step) {
        final List<Sample> out = new ArrayList<>();
        for (long t = from; t < to; t += step) {
            out.add(new Sample(t * MS, IDLE, true, true));
        }
        return out;
    }

    static List<Sample> busy(final long from, final long to, final long step, final Stack stack, final boolean inNative) {
        final List<Sample> out = new ArrayList<>();
        for (long t = from; t < to; t += step) {
            out.add(new Sample(t * MS, stack, false, inNative));
        }
        return out;
    }

    static Block block(final long fromMs, final long toMs, final BlockKind kind, final String detail, final ThreadRef owner) {
        return new Block(new Interval(fromMs * MS, toMs * MS), kind, detail, Stack.EMPTY, owner);
    }

    /** A block whose stack matters: the classifier reads it. */
    static Block blockWith(final long fromMs, final long toMs, final BlockKind kind, final String detail, final Stack stack) {
        return new Block(new Interval(fromMs * MS, toMs * MS), kind, detail, stack, null);
    }

    /**
     * A fixed pool's worker with nothing to do, as JDK 25 records it: an untimed await goes
     * through the managed-blocker frames, so the frame that says so is eight deep.
     */
    static final Stack NO_WORK = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 369, "JIT compiled"),
            new Frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode", "block", 520, "JIT compiled"),
            new Frame("java.util.concurrent.ForkJoinPool", "unmanagedBlock", 4364, "JIT compiled"),
            new Frame("java.util.concurrent.ForkJoinPool", "managedBlock", 4310, "JIT compiled"),
            new Frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "await", 1752, "JIT compiled"),
            new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1016, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1076, "JIT compiled"),
            new Frame("java.lang.Thread", "run", 1474, "Interpreted"));

    /**
     * The same pool, running a task that waits for a result: the wait is real and costs a
     * caller. JDK 25 blocks a {@code CompletableFuture.get} through the same
     * {@code ForkJoinPool.managedBlock} frame as the idle worker above.
     */
    static final Stack AWAITING_RESULT = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 223, "JIT compiled"),
            new Frame("java.util.concurrent.CompletableFuture$Signaller", "block", 1885, "JIT compiled"),
            new Frame("java.util.concurrent.ForkJoinPool", "unmanagedBlock", 4364, "JIT compiled"),
            new Frame("java.util.concurrent.ForkJoinPool", "managedBlock", 4310, "JIT compiled"),
            new Frame("java.util.concurrent.CompletableFuture", "waitingGet", 1918, "JIT compiled"),
            new Frame("java.util.concurrent.CompletableFuture", "get", 2094, "JIT compiled"),
            new Frame("dev.app.Browser", "browse", 163, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1076, "JIT compiled"),
            new Frame("java.lang.Thread", "run", 1474, "Interpreted"));

    static StallReport analyse(final List<Sample> samples, final List<Block> blocks, final List<Pause> pauses) {
        return analyse(sampledInfo(), samples, blocks, pauses);
    }

    static StallReport analyse(final RecordingInfo info, final List<Sample> samples, final List<Block> blocks,
                               final List<Pause> pauses) {
        final List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingLong(Sample::time));
        return new StallAnalysis(50 * MS).analyse(info, List.of(new ThreadTimeline(LOOP, sorted, blocks)), pauses);
    }

    /** A recording whose span does not start at zero: what a {@code jfrq-live delta} dump looks like. */
    static RecordingInfo window(final long fromMs, final long toMs) {
        return new RecordingInfo(Path.of("test.jfr"), new Interval(fromMs * MS, toMs * MS), 1,
                Map.of("jdk.ExecutionSample", 1L),
                Map.of("jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms")), Set.of(), List.of());
    }

    @Test
    void busyRunOfSamplesIsAStallNamedAfterTheDominantCulprit() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(busy(200, 320, 10, BURN, false));
        samples.addAll(idle(320, 500, 10));
        final StallReport r = analyse(samples, List.of(), List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BUSY, s.verdict());
        assertEquals(Evidence.SAMPLES, s.evidence());
        assertEquals(12, s.samples());
        assertEquals(200 * MS, s.start());
        // Ends at the next observation, which was the idle sample at 320 ms.
        assertEquals(120 * MS, s.duration());
        assertTrue(s.detail().contains("dev.app.CpuWork.burn"), s.detail());
        assertTrue(s.detail().contains("100% of 12 samples"), s.detail());
        assertEquals(BURN, s.stack());
        assertEquals(1, r.threads().size());
        assertEquals(120 * MS, r.threads().getFirst().stalledNanos());
        assertEquals(120 * MS, r.threads().getFirst().worstNanos());
        assertEquals(10 * MS, r.threads().getFirst().javaCadenceNanos());
    }

    @Test
    void underAppABusyRunIsNamedAfterTheApplicationFrameNotTheFramework() {
        // A program over a runtime: the innermost frame outside the JDK is the runtime's.
        final Stack overRuntime = stack(new Frame("clojure.lang.RT", "get", 780, "JIT compiled"),
                new Frame("dev.app.Work", "step", 12, "JIT compiled"));
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(busy(200, 320, 10, overRuntime, false));
        samples.addAll(idle(320, 500, 10));
        final List<ThreadTimeline> timelines = List.of(new ThreadTimeline(LOOP, samples, List.of()));

        final Stall plain = new StallAnalysis(50 * MS).analyse(sampledInfo(), timelines, List.of()).stalls().getFirst();
        assertTrue(plain.detail().startsWith("busy in clojure.lang.RT.get (100% of 12 samples)"), plain.detail());
        final Stall app = new StallAnalysis(50 * MS, IdleMatcher.forWorkWaits(), SiteKey.inPackages(List.of("dev.app")))
                .analyse(sampledInfo(), timelines, List.of()).stalls().getFirst();
        assertTrue(app.detail().startsWith("busy in dev.app.Work.step (100% of 12 samples)"), app.detail());
        // The stall is the same one, only named differently.
        assertEquals(plain.interval(), app.interval());
        assertEquals(plain.verdict(), app.verdict());
    }

    @Test
    void aThreadNeverSampledIdleIsLabelledBusyAndItsStallsKept() {
        // 100 samples, none idle, one per period across the second it was seen.
        final StallReport busy = analyse(busy(0, 1000, 10, BURN, false), List.of(), List.of());
        assertEquals(1, busy.stalls().size());
        assertTrue(busy.warnings().stream().anyMatch(w -> w.startsWith("1 matching thread was never sampled at an "
                + "idle point, so busy for the whole window (event-loop-1 (1 of its stalls, 100 samples))")),
                busy.warnings().toString());

        // One idle sample is a return to idle: no label.
        final List<Sample> rests = new ArrayList<>(busy(0, 500, 10, BURN, false));
        rests.addAll(idle(500, 510, 10));
        rests.addAll(busy(510, 1000, 10, BURN, false));
        assertTrue(analyse(rests, List.of(), List.of()).warnings().stream().noneMatch(w -> w.contains("never sampled")));

        // Seen 100 times in ten seconds is a thread the sampler rarely found running, not a busy one.
        assertTrue(analyse(busy(0, 10_000, 100, BURN, false), List.of(), List.of()).warnings().stream()
                .noneMatch(w -> w.contains("never sampled")));

        // Too few samples to say anything about the whole window.
        assertTrue(analyse(busy(0, 400, 10, BURN, false), List.of(), List.of()).warnings().stream()
                .noneMatch(w -> w.contains("never sampled")));
    }

    @Test
    void shortBusyRunIsBelowTheGap() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(busy(200, 230, 10, BURN, false)); // 3 samples, ~40 ms
        samples.addAll(idle(230, 400, 10));
        assertTrue(analyse(samples, List.of(), List.of()).stalls().isEmpty());
    }

    @Test
    void aSingleSampleIsNeverARun() {
        // With a 10 ms gap one sample spans exactly one period, which would pass the length test.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.add(new Sample(100 * MS, BURN, false, false));
        samples.addAll(idle(110, 200, 10));
        final StallReport r = new StallAnalysis(10 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, samples, List.of())), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
    }

    @Test
    void sparseNonIdleSamplesDoNotChainIntoARun() {
        // Non-idle samples 60 ms apart: nothing proves the thread was busy between them.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 400, 60, BURN, false));
        samples.addAll(idle(400, 500, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
    }

    @Test
    void busyRunWithoutADominantCulpritIsSaturatedOnlyWithEnoughSamples() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        for (int i = 0; i < 8; i++) {
            final Stack distinct = stack(new Frame("dev.app.Task" + i, "run", 1, "JIT compiled"));
            samples.add(new Sample((100 + i * 10) * MS, distinct, false, false));
        }
        samples.addAll(idle(180, 300, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.SATURATED, r.stalls().getFirst().verdict());
        assertTrue(r.stalls().getFirst().detail().contains("8 distinct culprits"));

        // Four distinct samples over 60 ms: too little evidence to call it saturated.
        final List<Sample> few = new ArrayList<>(idle(0, 100, 10));
        for (int i = 0; i < 4; i++) {
            final Stack distinct = stack(new Frame("dev.app.Task" + i, "run", 1, "JIT compiled"));
            few.add(new Sample((100 + i * 20) * MS, distinct, false, false));
        }
        few.addAll(idle(180, 300, 10));
        assertTrue(analyse(few, List.of(), List.of()).stalls().isEmpty());
    }

    @Test
    void blockingEventLongerThanTheGapIsAStallAndSwallowsTheSilence() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(500, 700, 10)); // silent for 300 ms
        final Block monitor = block(205, 495, BlockKind.MONITOR, "dev.app.Registry@1", HOLDER);
        final StallReport r = analyse(samples, List.of(monitor), List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKED_MONITOR, s.verdict());
        assertEquals(Evidence.EVENT, s.evidence());
        assertEquals(monitor.interval(), s.interval());
        assertEquals("blocked on monitor dev.app.Registry@1 held by housekeeper", s.detail());
    }

    @Test
    void aWorkerParkedOnItsOwnEmptyQueueIsNotAStall() {
        // The exact shape that was reported as a 1m10s PARKED stall on an idle API pool.
        final Block idlePool = blockWith(200, 70_200, BlockKind.PARK,
                "on java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject@1", NO_WORK);
        final StallReport r = analyse(info(80_000, Map.of("jdk.ExecutionSample",
                Map.of("enabled", "true", "period", "10 ms")), "jdk.ExecutionSample"),
                idle(0, 200, 10), List.of(idlePool), List.of());

        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("1 wait totalling 1m10s were workers waiting")),
                r.warnings().toString());
    }

    @Test
    void aPoolThreadWaitingForAResultIsStillAStall() {
        // Same pool, same park, but the frame under it is an application call: someone is waiting.
        final Block awaiting = blockWith(200, 13_000, BlockKind.PARK,
                "on java.util.concurrent.CompletableFuture$Signaller@1", AWAITING_RESULT);
        final StallReport r = analyse(info(20_000, Map.of("jdk.ExecutionSample",
                Map.of("enabled", "true", "period", "10 ms")), "jdk.ExecutionSample"),
                idle(0, 200, 10), List.of(awaiting), List.of());

        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.PARKED, r.stalls().getFirst().verdict());
        assertEquals(12_800 * MS, r.stalls().getFirst().duration());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("waiting for their own queue")), r.warnings().toString());
    }

    @Test
    void anIdleWorkersSilenceDoesNotComeBackAsUnexplained() {
        // No samples for the whole park: the silence must not be reported through the other door.
        final Block idlePool = blockWith(100, 9_000, BlockKind.PARK, "on q@1", NO_WORK);
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(idle(9_000, 9_500, 10));
        final StallReport r = analyse(samples, List.of(idlePool), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
    }

    @Test
    void anEventStallIsCountedOnlyForThePartInsideTheWindow() {
        // The window opens at 1 000 ms; the monitor wait began 500 ms before it. Counted
        // whole it is a 1 300 ms stall inside a 1 000 ms window.
        final RecordingInfo info = window(1_000, 2_000);
        final List<Sample> samples = new ArrayList<>(idle(1_800, 2_000, 10));
        final Block monitor = block(500, 1_800, BlockKind.MONITOR, "dev.app.Registry@1", HOLDER);
        final StallReport r = analyse(info, samples, List.of(monitor), List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(800 * MS, s.duration());
        assertEquals(1_000 * MS, s.start());
        assertEquals(Verdict.BLOCKED_MONITOR, s.verdict());
        assertEquals(Evidence.EVENT, s.evidence());
        assertEquals("blocked on monitor dev.app.Registry@1 held by housekeeper", s.detail());
        assertEquals(800 * MS, r.threads().getFirst().stalledNanos());
        assertTrue(r.warnings().contains(
                "1 stall extends beyond the recording's span and is counted only for the part inside it"),
                r.warnings().toString());
    }

    @Test
    void anEventWhoseInWindowPartIsBelowTheGapIsNotAStall() {
        // 700 ms of blocking, 20 ms of it inside the window: below the 50 ms gap.
        final RecordingInfo info = window(1_000, 2_000);
        final Block monitor = block(300, 1_020, BlockKind.MONITOR, "dev.app.Registry@1", HOLDER);
        final StallReport r = analyse(info, idle(1_100, 2_000, 10), List.of(monitor), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("beyond the recording's span")), r.warnings().toString());
    }

    @Test
    void aBusyRunDoesNotReachPastTheEndOfTheRecording() {
        // The last sample is 2 ms before the end, so the run's one-period tail would overrun it.
        final RecordingInfo info = window(1_000, 2_000);
        final List<Sample> samples = new ArrayList<>(idle(1_000, 1_900, 10));
        samples.addAll(busy(1_900, 1_999, 10, BURN, false));
        final StallReport r = analyse(info, samples, List.of(), List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(2_000 * MS, s.interval().end());
        assertEquals(100 * MS, s.duration());
    }

    @Test
    void silenceExplainedByShortBlocksThatCoverMostOfIt() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(400, 600, 10)); // 200 ms silence
        final List<Block> sleeps = List.of(
                block(210, 250, BlockKind.SLEEP, "", null),
                block(260, 300, BlockKind.SLEEP, "", null),
                block(310, 350, BlockKind.SLEEP, "", null)); // 120 of 200 ms
        final StallReport r = analyse(samples, sleeps, List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.SLEEP, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals("3 × Thread.sleep", s.detail());
        assertEquals(new Interval(190 * MS, 400 * MS), s.interval());
    }

    @Test
    void silenceExplainedByAGcPause() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(400, 600, 10));
        final Pause gc = new Pause(new Interval(220 * MS, 380 * MS), PauseKind.GC, "G1 Young (gcId 4)");
        final StallReport r = analyse(samples, List.of(), List.of(gc));

        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.GC_PAUSE, r.stalls().getFirst().verdict());
        assertEquals("GC pause: G1 Young (gcId 4)", r.stalls().getFirst().detail());
        assertEquals(List.of(gc), r.pauses());
        assertFalse(r.stalls().getFirst().verdict().isThreadLocal());
    }

    @Test
    void silenceExplainedByASafepointWhenNoGcCoversIt() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(400, 600, 10));
        final Pause gc = new Pause(new Interval(220 * MS, 240 * MS), PauseKind.GC, "small");
        final Pause sp = new Pause(new Interval(240 * MS, 390 * MS), PauseKind.SAFEPOINT, "safepoint 9");
        final StallReport r = analyse(samples, List.of(), List.of(gc, sp));
        assertEquals(Verdict.SAFEPOINT, r.stalls().getFirst().verdict());
        assertEquals(List.of(sp), r.pauses()); // only pauses >= gap are listed
    }

    @Test
    void unexplainedSilenceIsReportedAsSuch() {
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(600, 800, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.UNEXPLAINED, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals(Stack.EMPTY, s.stack());
        assertFalse(s.detail().contains("simultaneous"));
    }

    @Test
    void routineSamplingGapsAreNotSilence() {
        // Native samples routinely 100 ms apart: 250 ms is not evidence, 400 ms is.
        final List<Sample> samples = new ArrayList<>(idle(0, 1000, 100));
        samples.addAll(idle(1150, 2000, 100));
        assertTrue(analyse(samples, List.of(), List.of()).stalls().isEmpty());

        final List<Sample> longer = new ArrayList<>(idle(0, 1000, 100));
        longer.addAll(idle(1400, 2000, 100));
        final StallReport r = analyse(longer, List.of(), List.of());
        assertEquals(1, r.stalls().size());
        assertEquals(new Interval(900 * MS, 1400 * MS), r.stalls().getFirst().interval());
        // The reader is told what the samples cannot show: three routine absences.
        final StallReport.ThreadSummary t = r.threads().getFirst();
        assertEquals(StallReport.Sight.NATIVE_SAMPLER, t.sight());
        assertEquals(300 * MS, t.unseenBelowNanos());
        assertEquals("300 ms", t.unseenBelow(r.info().span().duration()));
        assertTrue(r.unseen().getFirst().startsWith("on 1 of 1 threads, a stall no event explains is seen only from "
                + "300 ms: each is sampled in native code every ~100 ms"), r.unseen().toString());
    }

    @Test
    void busyRunCoveredByIoEventsIsBlockingIo() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 200, 10, READ0, true));
        samples.addAll(idle(200, 300, 10));
        final List<Block> reads = List.of(
                block(100, 130, BlockKind.SOCKET_READ, "from db:5432 (12 bytes)", null),
                block(140, 170, BlockKind.SOCKET_READ, "from db:5432 (12 bytes)", null));
        final StallReport r = analyse(samples, reads, List.of());
        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKING_IO, s.verdict());
        assertEquals(Evidence.SAMPLES, s.evidence());
        assertEquals("2 × blocking socket read from db:5432 (12 bytes)", s.detail());
    }

    @Test
    void busyRunMostlyInNativeCodeSaysSo() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 200, 10, READ0, true));
        samples.addAll(idle(200, 300, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BUSY, s.verdict());
        assertTrue(s.detail().contains("dev.app.Handler.lookup"));
        assertTrue(s.detail().endsWith("[mostly in native code]"));
    }

    @Test
    void simultaneousUnexplainedSilencesAreMarked() {
        final List<Sample> a = new ArrayList<>(idle(0, 200, 10));
        a.addAll(idle(600, 800, 10));
        final List<Sample> b = new ArrayList<>(idle(0, 210, 10));
        b.addAll(idle(590, 800, 10));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, a, List.of()), new ThreadTimeline(OTHER, b, List.of())), List.of());
        assertEquals(2, r.stalls().size());
        for (final Stall s : r.stalls()) {
            assertEquals(Verdict.UNEXPLAINED, s.verdict());
            assertTrue(s.detail().contains("simultaneous on 2 watched threads"), s.detail());
        }
        assertEquals(List.of("event-loop-1", "event-loop-2"),
                r.threads().stream().map(t -> t.thread().name()).toList());
    }

    @Test
    void eventStallsAreFoundWithoutAnySamples() {
        final Block sleep = block(100, 400, BlockKind.SLEEP, "", null);
        final StallReport r = new StallAnalysis(50 * MS).analyse(info(1000, Map.of()),
                List.of(new ThreadTimeline(LOOP, List.of(), List.of(sleep))), List.of());
        assertEquals(1, r.stalls().size());
        assertEquals(Verdict.SLEEP, r.stalls().getFirst().verdict());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("no sampler events")));
        assertEquals(0, r.threads().getFirst().javaCadenceNanos());
    }

    @Test
    void thresholdAboveGapIsWarnedAbout() {
        final RecordingInfo info = info(1000, Map.of(
                "jdk.JavaMonitorEnter", Map.of("enabled", "true", "threshold", "100 ms"),
                "jdk.ThreadPark", Map.of("enabled", "false", "threshold", "100 ms"),
                "jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms")), "jdk.ExecutionSample");
        final StallReport r = new StallAnalysis(50 * MS).analyse(info, List.of(), List.of());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().getFirst().startsWith("jdk.JavaMonitorEnter threshold 100 ms exceeds gap 50.0 ms"));
    }

    @Test
    void reportOrdersStallsLongestFirstAndSummarisesByVerdict() {
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 220, 10, BURN, false));
        samples.addAll(idle(220, 400, 10));
        final Block sleep = block(500, 900, BlockKind.SLEEP, "", null);
        final StallReport r = analyse(samples, List.of(sleep), List.of());
        assertEquals(2, r.stalls().size());
        assertEquals(Verdict.SLEEP, r.stalls().getFirst().verdict());
        assertEquals(Verdict.BUSY, r.stalls().get(1).verdict());
        assertEquals(1, r.top(1).size());
        assertEquals(2, r.stallsOf(LOOP).size());
        assertEquals(Verdict.BUSY, r.stallsOf(LOOP).getFirst().verdict()); // time order
        final List<StallReport.VerdictSummary> byVerdict = r.byVerdict();
        assertEquals(Verdict.SLEEP, byVerdict.getFirst().verdict());
        assertEquals(400 * MS, byVerdict.getFirst().totalNanos());
        assertEquals(1, byVerdict.get(1).count());
        assertEquals(50 * MS, r.gapNanos());
    }

    @Test
    void describeIncludesTheHandOverChain() {
        final Block b = new Block(new Interval(0, MS), BlockKind.MONITOR, "Registry@1", Stack.EMPTY, HOLDER,
                List.of(OTHER, LOOP), 0, false);
        assertEquals("blocked on monitor Registry@1 held by housekeeper (handed on through event-loop-2, event-loop-1)",
                StallAnalysis.describe(b));
        assertEquals("blocked on monitor X held by unknown",
                StallAnalysis.describe(block(0, 1, BlockKind.MONITOR, "X", null)));
        assertEquals("blocking file read /tmp/x", StallAnalysis.describe(block(0, 1, BlockKind.FILE_READ, "/tmp/x", null)));
        assertEquals(Verdict.PARKED, StallAnalysis.verdictOf(BlockKind.PARK));
        assertEquals(Verdict.OBJECT_WAIT, StallAnalysis.verdictOf(BlockKind.OBJECT_WAIT));
        assertEquals(Verdict.BLOCKING_IO, StallAnalysis.verdictOf(BlockKind.FILE_FORCE));
        assertTrue(BlockKind.FILE_WRITE.isIo());
        assertFalse(BlockKind.MONITOR.isIo());
    }

    @Test
    void rejectsNonPositiveGap() {
        assertThrows(IllegalArgumentException.class, () -> new StallAnalysis(0));
        assertEquals(50 * MS, new StallAnalysis(50 * MS).gap());
    }

    @Test
    void cadenceStatistics() {
        final StallAnalysis.Cadence c = StallAnalysis.Cadence.of(List.of(), 10 * MS);
        assertEquals(0, c.java());
        assertEquals(10 * MS, c.period());
        assertEquals(0, StallAnalysis.Cadence.percentile(new LongList(), 0.9));
        assertEquals(20L, StallAnalysis.Cadence.percentile(longs(10L, 20L, 30L), 0.5));
        assertEquals(30L, StallAnalysis.Cadence.percentile(longs(10L, 20L, 30L), 0.9));
        final StallAnalysis.Cadence mixed = StallAnalysis.Cadence.of(List.of(
                new Sample(0, BURN, false, false), new Sample(10 * MS, BURN, false, false),
                new Sample(110 * MS, IDLE, true, true), new Sample(210 * MS, IDLE, true, true)), 0);
        assertEquals(10 * MS, mixed.java());
        assertEquals(100 * MS, mixed.inNative());
        assertEquals(10 * MS, mixed.period());
    }

    /**
     * The cadence threshold decides whether an unexplained silence is evidence; a silence a
     * pause or a group of blocks explains is reported whatever the cadence, as the warning
     * text promises ("only ones a blocking event or a JVM pause explains").
     */
    @Test
    void anExplainedSilenceIsReportedBelowTheCadenceThreshold() {
        // Samples every 100 ms: routine absence 100 ms, so an unexplained silence needs 300 ms.
        final List<Sample> samples = new ArrayList<>(idle(0, 1000, 100));
        samples.addAll(idle(1150, 2000, 100));
        final Pause gc = new Pause(new Interval(1000 * MS, 1140 * MS), PauseKind.GC, "G1 Young (gcId 7)");
        final StallReport r = analyse(samples, List.of(), List.of(gc));

        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.GC_PAUSE, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        // What the pause accounts for, not the whole silence around it: the thread may have
        // been running on either side of it.
        assertEquals(gc.interval(), s.interval());
        // The same silence with nothing to explain it is below the threshold: not reported.
        assertTrue(analyse(samples, List.of(), List.of()).stalls().isEmpty());
        // Below the threshold the explanation must cover a whole gap by itself: a 40 ms pause
        // is half of an 80 ms silence but not a stall on its own.
        final List<Sample> shortGap = new ArrayList<>(idle(0, 1000, 100));
        shortGap.addAll(idle(1080, 2000, 100));
        final Pause brief = new Pause(new Interval(1000 * MS, 1040 * MS), PauseKind.GC, "G1 Young (gcId 8)");
        assertTrue(analyse(shortGap, List.of(), List.of(brief)).stalls().isEmpty());
        // And a 400 ms unexplained silence is.
        final List<Sample> longer = new ArrayList<>(idle(0, 1000, 100));
        longer.addAll(idle(1400, 2000, 100));
        final StallReport u = analyse(longer, List.of(), List.of());
        assertEquals(1, u.stalls().size());
        assertEquals(Verdict.UNEXPLAINED, u.stalls().getFirst().verdict());
    }

    @Test
    void manyShortReadsFromOnePeerExplainASilenceTogether() {
        // The loop vanishes for 300 ms; the file holds twelve 20 ms socket reads from one
        // backend, each with a different byte count. They must add up to one answer.
        final List<Sample> samples = new ArrayList<>(idle(0, 200, 10));
        samples.addAll(idle(500, 700, 10));
        final List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final long from = 200 + i * 25L;
            blocks.add(new Block(new Interval(from * MS, (from + 20) * MS), BlockKind.SOCKET_READ,
                    "from backend:9000", READ0, 100L + i));
        }
        final StallReport r = analyse(samples, blocks, List.of());

        assertEquals(1, r.stalls().size());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKING_IO, s.verdict());
        assertEquals(Evidence.SILENCE, s.evidence());
        assertEquals("12 × blocking socket read from backend:9000 (1.27 KB)", s.detail());
        assertEquals(READ0, s.stack());
        // One read alone keeps its own byte count.
        assertEquals("blocking socket read from backend:9000 (100 B)", StallAnalysis.describe(blocks.getFirst()));
    }

    @Test
    void throttledBlockingEventsAreAWarning() {
        final RecordingInfo info = info(10_000, Map.of(
                "jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms"),
                "jdk.SocketRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "300/s"),
                "jdk.FileRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "off"),
                "jdk.SocketWrite", Map.of("enabled", "false", "threshold", "1 ms", "throttle", "300/s")),
                "jdk.ExecutionSample");
        final StallReport r = new StallAnalysis(50 * MS).analyse(info, List.of(new ThreadTimeline(LOOP, idle(0, 100, 10),
                List.of())), List.of());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        final String w = r.warnings().getFirst();
        assertTrue(w.startsWith("throttled events (jdk.SocketRead 300/s)"), w);
        assertFalse(w.contains("FileRead") || w.contains("SocketWrite"), w);
    }

    /** An application's own worker loop: no frame here is in any idle list. */
    static final Stack MAILBOX = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "parkNanos", 271, "JIT compiled"),
            new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
            new Frame("dev.app.SystemDispatcher$DispatchLoop", "run", 80, "JIT compiled"));

    @Test
    void aLoopParkedOnItsOwnMailboxIsNotStalledAtAnyOfTheThreeDoors() {
        // 60 ms parked out of every 70 ms of a 10 s recording, on a lock nobody else touches
        // and nobody holds: the thread has nothing to do, and no idle list names that frame.
        final List<Block> blocks = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 140; i++) {
            final long from = i * 70L;
            blocks.add(blockWith(from, from + 60, BlockKind.PARK, "dev.app.DefaultMailbox@1", MAILBOX));
            // The sampler sees a park as native code, not as the thread's idle point, so these
            // samples chain into runs: door three, which is where they came back before.
            samples.add(new Sample((from + 30) * MS, MAILBOX, false, true));
        }
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, samples, blocks)), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("waiting for their own queue")),
                r.warnings().toString());

        // The same loop with the rule turned off: --idle none means none, and every park is a
        // stall again — with the runs of park samples between them on top, which is what the
        // report looked like before any of this existed.
        final StallReport raw = new StallAnalysis(50 * MS, IdleMatcher.none()).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, samples, blocks)), List.of());
        assertTrue(raw.stalls().size() >= blocks.size(), "got " + raw.stalls().size());
    }

    @Test
    void aConsumerThatWaitsForWorkSomeoneOwesItStaysAStall() {
        // The same shape at a fifth of the window: this one was waiting for data another
        // thread had to produce, which is the finding the rule must not swallow.
        final List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final long from = i * 1_000L;
            blocks.add(blockWith(from, from + 400, BlockKind.PARK, "dev.app.Sink@2", MAILBOX));
        }
        final StallReport r = new StallAnalysis(100 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, idle(0, 10_000, 10), blocks)), List.of());
        assertEquals(5, r.stalls().size());
        assertEquals(Stall.Verdict.PARKED, r.stalls().getFirst().verdict());
    }

    @Test
    void gapsWithNoEvidenceAreListedApartFromTheStallsWithAnExplanation() {
        final Stall gap = new Stall(LOOP, new Interval(0, 474 * MS), Stall.Verdict.UNEXPLAINED, "no evidence",
                Stack.EMPTY, Stall.Evidence.SILENCE, 0);
        final Stall parked = new Stall(LOOP, new Interval(500 * MS, 671 * MS), Stall.Verdict.PARKED, "parked",
                Stack.EMPTY, Stall.Evidence.EVENT, 3);
        final StallReport r = new StallReport(info(10_000, Map.of(), "jdk.ExecutionSample"), 50 * MS, List.of(),
                List.of(parked, gap), List.of(), List.of());

        // Ranked together the 474 ms gap comes first and says nothing; apart, each list is
        // longest-first within its own kind, and both still count in stalls().
        assertEquals(List.of(gap, parked), r.stalls());
        assertEquals(List.of(parked), r.explained());
        assertEquals(List.of(gap), r.unexplained());
        assertEquals(List.of(parked), StallReport.top(r.explained(), 5));
        assertEquals(List.of(), StallReport.top(r.explained(), 0));
    }

    /** A consumer loop parked on a queue: nothing in any idle list. */
    static final Stack CONSUMER = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
            new Frame("java.util.concurrent.ArrayBlockingQueue", "take", 420, "JIT compiled"),
            new Frame("dev.app.Consumer", "awaitData", 40, "JIT compiled"),
            new Frame("dev.app.Consumer", "run", 20, "JIT compiled"));

    static final ThreadRef CONSUMER_2 = new ThreadRef(4, "consumer-2");

    @Test
    void aPerchIsJudgedOnEveryThreadsParksNotOnlyOnTheWatchedOnes() {
        // 6 s of a 10 s window parked in two parks: alone, that is the loop's perch. But a
        // second thread parks on the same queue, so it is a queue two threads wait on, and
        // which of them --thread selected must not change that.
        final String queue = "on dev.app.Queue@1f";
        final List<Block> mine = List.of(blockWith(1_000, 4_000, BlockKind.PARK, queue, CONSUMER),
                blockWith(5_000, 8_000, BlockKind.PARK, queue, CONSUMER));
        final ParkShapes everyone = new ParkShapes();
        for (final Block b : mine) {
            everyone.add(LOOP, b.detail(), b.start(), b.interval().end(), b.stack());
        }
        final ParkShapes alone = new ParkShapes();
        for (final Block b : mine) {
            alone.add(LOOP, b.detail(), b.start(), b.interval().end(), b.stack());
        }
        everyone.add(CONSUMER_2, queue, 8_500 * MS, 8_600 * MS, CONSUMER);
        final List<ThreadTimeline> watched = List.of(new ThreadTimeline(LOOP, List.of(), mine));

        final StallReport shared = new StallAnalysis(50 * MS).analyse(sampledInfo(), watched, List.of(), everyone, List.of(),
                new StallAnalysis.SamplerShares());
        assertEquals(2, shared.stalls().size(), shared.stalls().toString());
        assertEquals(Verdict.PARKED, shared.stalls().getFirst().verdict());

        final StallReport own = new StallAnalysis(50 * MS).analyse(sampledInfo(), watched, List.of(), alone, List.of(),
                new StallAnalysis.SamplerShares());
        assertTrue(own.stalls().isEmpty(), own.stalls().toString());
    }

    @Test
    void parksWithNoBlockerObjectNeverMakeAPerch() {
        // A retry loop's parkNanos backoff: 6 s of a 10 s window in three parks, on no object.
        // Every blocker-less park in the JVM shares that "lock"; it is not the thread's idle point.
        final Stack backoff = stack(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("java.util.concurrent.locks.LockSupport", "parkNanos", 400, "JIT compiled"),
                new Frame("dev.app.Loop", "retryWithBackoff", 77, "JIT compiled"),
                new Frame("dev.app.Loop", "run", 20, "JIT compiled"));
        final List<Block> parks = List.of(
                blockWith(1_000, 3_000, BlockKind.PARK, ParkShapes.NO_BLOCKER, backoff),
                blockWith(4_000, 6_000, BlockKind.PARK, ParkShapes.NO_BLOCKER, backoff),
                blockWith(7_000, 9_000, BlockKind.PARK, ParkShapes.NO_BLOCKER, backoff));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, List.of(), parks)), List.of());
        assertEquals(3, r.stalls().size(), r.stalls().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("waiting for their own queue")), r.warnings().toString());
    }

    /** A thread whose life the recording bounds: here the whole 10 s span. */
    static ThreadTimeline lived(final List<Sample> samples, final List<Block> blocks, final long fromMs, final long toMs) {
        return new ThreadTimeline(LOOP, samples, blocks, fromMs * MS, toMs * MS);
    }

    @Test
    void aThreadStuckFromTheMiddleToTheEndIsReported() {
        // Idle at its selector for a second, then nothing: the monitor it blocked on was still
        // held when the recording stopped, so no event was ever written for it.
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(0, 1_000, 10), List.of(), 0, 10_000)), List.of());
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.UNEXPLAINED, s.verdict());
        assertEquals(new Interval(990 * MS, 10_000 * MS), s.interval());
        assertTrue(s.detail().contains("runs to the thread's last moment in the recording"), s.detail());
        assertTrue(s.detail().contains("still in progress when the recording stopped"), s.detail());

        // The same stretch at the start of the recording.
        final StallReport lead = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(9_000, 10_000, 10), List.of(), 0, 10_000)), List.of());
        assertEquals(new Interval(0, 9_000 * MS), lead.stalls().getFirst().interval());
        assertTrue(lead.stalls().getFirst().detail().contains("from the thread's first moment"),
                lead.stalls().getFirst().detail());
    }

    @Test
    void aWorkerThatWentBackToItsQueueIsNotStuckToTheEnd() {
        // A pool worker busy for a second, then parked for work until after the recording
        // stopped: that park has no event yet. Seen waiting for work earlier, the stretch after
        // its last sample is most likely the same wait, and nothing says otherwise.
        final List<Sample> samples = new ArrayList<>(busy(3_000, 4_000, 10, BURN, false));
        final Block earlier = blockWith(100, 2_900, BlockKind.PARK, "on q@1", NO_WORK);
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(samples, List.of(earlier), 0, 10_000)), List.of());
        assertTrue(r.stalls().stream().noneMatch(st -> st.verdict() == Verdict.UNEXPLAINED), r.stalls().toString());
    }

    @Test
    void aWorkerIdleOnAQueueTheCollectorMovedIsNotUnexplained() {
        // Field case: a pool worker parked on its own queue for all but 2 s of a four-minute
        // silence, but the collector moved the queue twice, so the parks name three addresses
        // and none covers half the silence alone. Grouped by instance it read as a 3m52s
        // UNEXPLAINED stall, the worst in the report; by stack it is the worker at rest.
        final List<Block> parks = List.of(
                blockWith(100, 2_900, BlockKind.PARK, "on q@1", NO_WORK),
                blockWith(2_950, 5_900, BlockKind.PARK, "on q@2", NO_WORK),
                blockWith(5_950, 8_900, BlockKind.PARK, "on q@3", NO_WORK));
        final List<Sample> samples = idle(9_000, 9_100, 50);
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(samples, parks, 0, 9_100)), List.of());
        assertTrue(r.stalls().stream().noneMatch(st -> st.verdict() == Verdict.UNEXPLAINED), r.stalls().toString());

        // Short waits for a result from one place, on instances that keep moving, each below the
        // gap: together they are the silence's explanation, named by the lock's class.
        final List<Block> waits = new ArrayList<>();
        for (int i = 0; i < 178; i++) {
            waits.add(blockWith(i * 50L, i * 50L + 40, BlockKind.PARK, "on q@" + (i % 3), AWAITING_RESULT));
        }
        final StallReport w = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(samples, waits, 0, 9_100)), List.of());
        assertEquals(1, w.stalls().size(), w.stalls().toString());
        final Stall silence = w.stalls().getFirst();
        assertEquals(Verdict.PARKED, silence.verdict());
        assertEquals(Stall.Evidence.SILENCE, silence.evidence());
        assertTrue(silence.detail().startsWith("178 × parked on q;"), silence.detail());
    }

    /** A synchronized method at one of two lines, reached from the same handler. */
    static Stack storePut(final int line) {
        return stack(new Frame("dev.app.Store", "put", line, "JIT compiled"),
                new Frame("dev.app.Handler", "handle", 40, "JIT compiled"),
                new Frame("java.lang.Thread", "run", 1474, "Interpreted"));
    }

    @Test
    void oneLockTakenFromTwoPlacesStillExplainsTheSilence() {
        // Grouped by stack, 178 short waits on one monitor from two lines split in halves, neither
        // covering half the silence, and the silence read as UNEXPLAINED beside 178 blocking events.
        final List<Block> waits = new ArrayList<>();
        for (int i = 0; i < 178; i++) {
            waits.add(new Block(new Interval(i * 50L * MS, (i * 50L + 40) * MS), BlockKind.MONITOR, "dev.app.Store@1",
                    storePut(i % 2 == 0 ? 10 : 20), HOLDER));
        }
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(9_000, 9_100, 50), waits, 0, 9_100)), List.of());
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        assertEquals(Verdict.BLOCKED_MONITOR, r.stalls().getFirst().verdict());
        assertTrue(r.stalls().getFirst().detail().startsWith("178 × blocked on monitor dev.app.Store@1 held by housekeeper"),
                r.stalls().getFirst().detail());
    }

    @Test
    void anIdleParkOnALockOfTheSameClassDoesNotStandForBusyWaits() {
        // Field case (round 1): three short waits for browse results on one ConditionObject, and
        // a pool's idle park on another ConditionObject overlapping the end. Grouped by class,
        // the idle park was the longest block, so it stood for all of them, and a real stall
        // was dropped as the worker at rest.
        final List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            blocks.add(blockWith(i * 40L, i * 40L + 30, BlockKind.PARK, "on q@1", AWAITING_RESULT));
        }
        blocks.add(blockWith(220, 2_000, BlockKind.PARK, "on q@2", NO_WORK));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(300, 400, 50), blocks, 0, 400)), List.of());
        assertTrue(r.stalls().stream().anyMatch(st -> st.verdict() == Verdict.PARKED
                && st.detail().startsWith("6 × parked on q@1")), r.stalls().toString());
    }

    @Test
    void aGroupOnOneLockKeepsItsHoldersChain() {
        // One monitor, its longest wait held by housekeeper after event-loop-2 handed it on: the
        // detail reads as it did before groups of several instances named every holder.
        final List<Block> waits = new ArrayList<>();
        for (int i = 0; i < 178; i++) {
            waits.add(new Block(new Interval(i * 50L * MS, (i * 50L + (i == 5 ? 45 : 40)) * MS), BlockKind.MONITOR,
                    "dev.app.Store@1", storePut(10), i % 2 == 0 ? HOLDER : OTHER, i == 5 ? List.of(OTHER) : List.of(),
                    0, false));
        }
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(9_000, 9_100, 50), waits, 0, 9_100)), List.of());
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final String detail = r.stalls().getFirst().detail();
        assertTrue(detail.startsWith("178 × " + StallAnalysis.describe(waits.get(5))), detail);
        assertTrue(detail.contains("event-loop-2"), detail);
    }

    @Test
    void aGroupOfMonitorWaitsNamesEveryHolder() {
        // One place in the code, three instances of a lock, held by two threads: the detail names
        // the class and both. One Stack object, as the interner makes it for equal stacks.
        final Stack put = storePut(10);
        final List<Block> waits = new ArrayList<>();
        for (int i = 0; i < 178; i++) {
            waits.add(new Block(new Interval(i * 50L * MS, (i * 50L + 40) * MS), BlockKind.MONITOR, "dev.app.Conn@" + (i % 3),
                    put, i % 3 == 0 ? HOLDER : OTHER));
        }
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(9_000, 9_100, 50), waits, 0, 9_100)), List.of());
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        assertTrue(r.stalls().getFirst().detail().startsWith("178 × blocked on monitor dev.app.Conn held by housekeeper, "
                + "event-loop-2;"), r.stalls().getFirst().detail());
    }

    @Test
    void aThreadsBirthAndDeathAreNotStalls() {
        // Started at 3 s and ended at 6 s: nothing before or after its life is a silence.
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(idle(3_000, 6_000, 10), List.of(), 3_000, 6_000)), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        // Without lifetime events the ends of a thread's life are not judged at all.
        final StallReport unknown = analyse(idle(3_000, 4_000, 10), List.of(), List.of());
        assertTrue(unknown.stalls().isEmpty(), unknown.stalls().toString());
    }

    @Test
    void aThreadNeverSampledIsNotAnUnexplainedGapButItsExplainedStretchIs() {
        // No samples at all: no routine absence to be longer than, so no UNEXPLAINED; a GC
        // pause that stopped it for a whole gap is still a stall.
        final Block sleep = block(100, 120, BlockKind.SLEEP, "", null);
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(List.of(), List.of(sleep), 0, 10_000)), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        final Pause gc = new Pause(new Interval(2_000 * MS, 7_500 * MS), PauseKind.GC, "Full (gcId 3)");
        final StallReport paused = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(List.of(), List.of(sleep), 0, 10_000)), List.of(gc));
        assertEquals(1, paused.stalls().size(), paused.stalls().toString());
        assertEquals(Verdict.GC_PAUSE, paused.stalls().getFirst().verdict());
        assertEquals(gc.interval(), paused.stalls().getFirst().interval());
    }

    @Test
    void aRunsEstimatedTailGivesWayToTheSilenceAfterIt() {
        // Busy from 1000 to its last sample at 1090, then nothing until 1145: the 55 ms silence
        // is evidence, the run's one-period tail past 1090 is an estimate, and the silence wins.
        final List<Sample> samples = new ArrayList<>(idle(0, 1_000, 10));
        samples.addAll(busy(1_000, 1_100, 10, BURN, false));
        samples.addAll(idle(1_145, 2_000, 10));
        final StallReport r = analyse(samples, List.of(), List.of());
        final Stall run = r.stalls().stream().filter(st -> st.verdict() == Verdict.BUSY).findFirst()
                .orElseThrow(() -> new AssertionError(r.stalls().toString()));
        final Stall silence = r.stalls().stream().filter(st -> st.verdict() == Verdict.UNEXPLAINED).findFirst()
                .orElseThrow(() -> new AssertionError(r.stalls().toString()));
        assertEquals(new Interval(1_090 * MS, 1_145 * MS), silence.interval());
        assertEquals(new Interval(1_000 * MS, 1_090 * MS), run.interval());
    }

    @Test
    void aThreadNeverSampledIsNotToldAboutItsLastSample() {
        // Short blocker-less parks (never a perch) for its whole life, with one long sleep inside: the stretch after
        // the sleep runs to the thread's last moment, and there is no sample it can come after.
        final List<Block> blocks = new ArrayList<>();
        for (long t = 0; t + 40 <= 10_000; t += 45) {
            if (t + 40 <= 3_000 || t >= 4_000) {
                blocks.add(blockWith(t, t + 40, BlockKind.PARK, "(no blocker object)", AWAITING_RESULT));
            }
        }
        blocks.add(block(3_000, 4_000, BlockKind.SLEEP, "", null));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(List.of(), blocks, 0, 10_000)), List.of());
        assertFalse(r.stalls().isEmpty(), r.stalls().toString());
        for (final Stall s : r.stalls()) {
            assertFalse(s.detail().contains("after its last sample"), s.detail());
        }
    }

    @Test
    void blockingEventsOfDifferentKindsTogetherExplainARun() {
        // Neither the socket reads nor the file read cover half the run on their own; together
        // they do, so the thread was blocked, not busy.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(busy(100, 200, 10, READ0, true));
        samples.addAll(idle(200, 300, 10));
        final List<Block> blocks = List.of(
                block(100, 130, BlockKind.SOCKET_READ, "from db:5432", null),
                block(140, 170, BlockKind.FILE_READ, "/var/data", null));
        final StallReport r = analyse(samples, blocks, List.of());
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.BLOCKING_IO, s.verdict());
        assertEquals(Evidence.SAMPLES, s.evidence());
        assertEquals("blocking socket read from db:5432, with 1 other blocking event", s.detail());
    }

    @Test
    void aThreadSeenOnlyInNativeCodeHasNoJavaCadenceAndChainsByThePeriod() {
        // Native samples 40 ms apart, 10 ms configured period: 40 ms is the native spacing, not
        // a Java cadence, and must not stretch the chaining limit to 120 ms.
        final List<Sample> samples = new ArrayList<>(idle(0, 120, 40));
        samples.addAll(busy(120, 400, 40, READ0, true));
        samples.addAll(idle(400, 500, 40));
        final StallReport r = analyse(samples, List.of(), List.of());
        assertEquals(0, r.threads().getFirst().javaCadenceNanos());
        assertEquals(40 * MS, r.threads().getFirst().nativeCadenceNanos());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());

        final StallAnalysis.Cadence onlyNative = StallAnalysis.Cadence.of(busy(0, 400, 40, READ0, true), 10 * MS);
        assertEquals(0, onlyNative.java());
        assertEquals(40 * MS, onlyNative.inNative());
        // The routine absence still counts every observation.
        assertEquals(40 * MS, onlyNative.routineAbsence());
    }

    @Test
    void aSmallerGapNeverFindsFewerSampleRuns() {
        // Busy samples 10.5 ms apart at a 10 ms period: at a 50 ms gap one BUSY run; at a gap
        // at or below the period the chaining limit used to follow the gap down and chain nothing.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        for (int k = 0; k < 20; k++) {
            samples.add(new Sample(100 * MS + k * 10_500_000L, BURN, false, false));
        }
        samples.addAll(idle(320, 400, 10));
        int previous = Integer.MAX_VALUE;
        for (final long gapMs : new long[]{100, 50, 20, 10, 5, 1}) {
            final StallReport r = new StallAnalysis(gapMs * MS).analyse(sampledInfo(),
                    List.of(new ThreadTimeline(LOOP, samples, List.of())), List.of());
            final long busyRuns = r.stalls().stream().filter(st -> st.verdict() == Verdict.BUSY).count();
            assertEquals(1, busyRuns, "gap " + gapMs + " ms: " + r.stalls());
            assertTrue(r.stalls().size() >= Math.min(previous, 1), "gap " + gapMs + " ms");
            previous = r.stalls().size();
        }
    }

    @Test
    void aCulpritNamedByOneSampleIsNotBusy() {
        // Two samples 40 ms apart at a 20 ms period chain into a 60 ms run, but they name two
        // different methods: "busy in A (50% of 2 samples)" rests on one sample.
        final RecordingInfo slow = info(10_000, Map.of("jdk.ExecutionSample", Map.of("enabled", "true", "period", "20 ms")),
                "jdk.ExecutionSample");
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 20));
        samples.add(new Sample(100 * MS, stack(new Frame("dev.app.A", "a", 1, "JIT compiled")), false, false));
        samples.add(new Sample(140 * MS, stack(new Frame("dev.app.B", "b", 1, "JIT compiled")), false, false));
        samples.addAll(idle(200, 300, 20));
        assertTrue(analyse(slow, samples, List.of(), List.of()).stalls().isEmpty());

        // The same two samples in one method are two observations of it: still BUSY.
        final List<Sample> same = new ArrayList<>(idle(0, 100, 20));
        same.addAll(List.of(new Sample(100 * MS, BURN, false, false), new Sample(140 * MS, BURN, false, false)));
        same.addAll(idle(200, 300, 20));
        final StallReport r = analyse(slow, same, List.of(), List.of());
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        assertTrue(r.stalls().getFirst().detail().contains("100% of 2 samples"), r.stalls().getFirst().detail());
    }

    @Test
    void manyShortPausesAreCountedAndTheSilenceIsCutToThem() {
        // 36 collections of 15 ms, one every 25 ms, inside a 1 s silence: they stopped the JVM
        // for 540 ms. One gcId on a 1 s stall reads as a single second-long pause.
        final List<Sample> samples = new ArrayList<>(idle(0, 1_000, 10));
        samples.addAll(idle(2_000, 2_100, 10));
        final List<Pause> pauses = new ArrayList<>();
        for (int k = 0; k < 36; k++) {
            final long from = 1_100 + 25L * k;
            pauses.add(new Pause(new Interval(from * MS, (from + 15) * MS), PauseKind.GC, "Young (gcId " + k + ")"));
        }
        final StallReport r = analyse(samples, List.of(), pauses);
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.GC_PAUSE, s.verdict());
        assertEquals("36 × GC pause, 540 ms stopped in total, longest 15.0 ms (Young (gcId 0))", s.detail());
        assertEquals(new Interval(1_100 * MS, 1_990 * MS), s.interval());
    }

    @Test
    void aFrameNamedIdleMakesASleepUnderItIdleButTheWaitPrimitivesDoNot() {
        // --idle names a hand-written poll loop that sleeps between polls.
        final Stack poll = stack(new Frame("java.lang.Thread", "sleep0", 0, "Native"),
                new Frame("java.lang.Thread", "sleep", 509, "JIT compiled"),
                new Frame("dev.app.Loop", "poll", 30, "JIT compiled"));
        final Block nap = blockWith(100, 600, BlockKind.SLEEP, "", poll);
        final IdleMatcher named = IdleMatcher.forWorkWaits(IdleMatcher.of("dev\\.app\\.Loop\\.poll"));
        final StallReport r = new StallAnalysis(50 * MS, named).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, idle(0, 100, 10), List.of(nap))), List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        // Without it, the sleep is a stall.
        assertEquals(1, new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, idle(0, 100, 10), List.of(nap))), List.of()).stalls().size());
        // A list that repeats the defaults, or names a park itself, must not make every park idle.
        final IdleMatcher wide = IdleMatcher.forWorkWaits(IdleMatcher.of(
                String.join(",", IdleMatcher.DEFAULT_PATTERNS) + ",.*Unsafe\\.park,java\\.lang\\.Thread\\.sleep"));
        assertFalse(wide.isIdle(AWAITING_RESULT));
        assertFalse(wide.isIdle(poll));
        assertTrue(wide.isIdle(NO_WORK));
    }

    @Test
    void matchingThreadsWithNothingToJudgeAreNamed() {
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(), List.of(), List.of(), new ParkShapes(),
                List.of("VM Thread"), new StallAnalysis.SamplerShares());
        assertTrue(r.threads().isEmpty());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().getFirst().startsWith("1 matching thread has no samples and no blocking events, "
                + "so nothing to judge by (VM Thread)"), r.warnings().getFirst());
        final List<String> many = List.of("a", "b", "c", "d", "e", "f", "g");
        final String w = new StallAnalysis(50 * MS).analyse(sampledInfo(), List.of(), List.of(), new ParkShapes(), many,
                new StallAnalysis.SamplerShares())
                .warnings().getFirst();
        assertTrue(w.startsWith("7 matching threads have no samples and no blocking events, so nothing to judge by "
                + "(a, b, c, d, e and 2 more)"), w);
    }

    @Test
    void aSilenceTheEventStallsCoverTogetherIsNotReportedAgain() {
        // Two one-minute waits inside a silence of two and a half: each is a stall, and the
        // silence around them is the same time a second time over.
        final List<Sample> samples = new ArrayList<>(idle(0, 100, 10));
        samples.addAll(idle(150_100, 150_200, 10));
        final List<Block> waits = List.of(block(1_000, 61_000, BlockKind.OBJECT_WAIT, "on dev.app.Lock@1", null),
                block(62_000, 122_000, BlockKind.OBJECT_WAIT, "on dev.app.Lock@1", null));
        final StallReport r = analyse(info(200_000, Map.of("jdk.ExecutionSample",
                Map.of("enabled", "true", "period", "10 ms")), "jdk.ExecutionSample"), samples, waits, List.of());
        assertEquals(2, r.stalls().size(), r.stalls().toString());
        assertTrue(r.stalls().stream().allMatch(st -> st.evidence() == Evidence.EVENT), r.stalls().toString());
    }

    @Test
    void aPauseBetweenTwoChainedSamplesStopsTheRun() {
        // Two busy samples 56 ms apart at a 20 ms period chain (three periods reach past the
        // 50 ms gap), and a 52 ms collection sits between them: the thread was stopped, not busy.
        final RecordingInfo slow = info(10_000, Map.of("jdk.ExecutionSample", Map.of("enabled", "true", "period", "20 ms")),
                "jdk.ExecutionSample", "jdk.NativeMethodSample");
        final List<Sample> samples = new ArrayList<>(idle(0, 1_000, 20));
        samples.addAll(List.of(new Sample(1_000 * MS, BURN, false, false), new Sample(1_056 * MS, BURN, false, false)));
        samples.addAll(idle(1_060, 3_000, 20));
        final Pause gc = new Pause(new Interval(1_003 * MS, 1_055 * MS), PauseKind.GC, "G1 young");
        final StallReport r = analyse(slow, samples, List.of(), List.of(gc));
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        assertEquals(Verdict.GC_PAUSE, r.stalls().getFirst().verdict());
        assertEquals(gc.interval(), r.stalls().getFirst().interval());

        // Without the collection the same two samples are one busy run.
        final StallReport busy = analyse(slow, samples, List.of(), List.of());
        assertEquals(List.of(Verdict.BUSY), busy.stalls().stream().map(Stall::verdict).toList());
    }

    @Test
    void aPauseCoveringHalfASilenceExplainsItWholeWhenItIsShorterThanTheGap() {
        // A 70 ms silence, 45 ms of it a collection: the pause covers it by the rule a block
        // is held to, so the silence is the pause's, not "no blocking event".
        final List<Sample> samples = new ArrayList<>(idle(0, 1_010, 10));
        samples.addAll(idle(1_070, 3_000, 10));
        final Pause gc = new Pause(new Interval(1_010 * MS, 1_055 * MS), PauseKind.GC, "G1 young");
        final StallReport r = analyse(samples, List.of(), List.of(gc));
        assertEquals(1, r.stalls().size(), r.stalls().toString());
        final Stall s = r.stalls().getFirst();
        assertEquals(Verdict.GC_PAUSE, s.verdict());
        assertEquals(new Interval(1_000 * MS, 1_070 * MS), s.interval());
        assertEquals("GC pause: G1 young, 45.0 ms of a 70.0 ms silence", s.detail());
    }

    @Test
    void anEventStallInsideAnExplainedSilenceIsNotCountedTwice() {
        // A client thread: 600 ms unseen, 30 short parks and one 150 ms socket read among them. The
        // read is an event stall; the parks explain what is left, around it, and no more.
        final List<Sample> samples = new ArrayList<>(idle(0, 1_000, 10));
        samples.addAll(idle(1_600, 2_000, 10));
        final List<Block> blocks = new ArrayList<>();
        for (int k = 0; k < 15; k++) {
            blocks.add(block(1_000 + 20L * k, 1_012 + 20L * k, BlockKind.PARK, ParkShapes.NO_BLOCKER, null));
            blocks.add(block(1_450 + 10L * k, 1_458 + 10L * k, BlockKind.PARK, ParkShapes.NO_BLOCKER, null));
        }
        blocks.add(new Block(new Interval(1_300 * MS, 1_450 * MS), BlockKind.SOCKET_READ, "from backend:9000", Stack.EMPTY, 6));
        blocks.sort(Comparator.comparing(Block::interval));
        final StallReport r = analyse(samples, blocks, List.of());
        final List<Stall> stalls = r.stallsOf(LOOP);
        assertEquals(List.of(Verdict.PARKED, Verdict.BLOCKING_IO, Verdict.PARKED),
                stalls.stream().map(Stall::verdict).toList(), stalls.toString());
        assertEquals(new Interval(990 * MS, 1_300 * MS), stalls.get(0).interval());
        assertEquals(new Interval(1_450 * MS, 1_600 * MS), stalls.get(2).interval());
        assertTrue(stalls.get(0).detail().startsWith("15 × parked"), stalls.get(0).detail());
        assertEquals(610 * MS, r.threads().getFirst().stalledNanos());
    }

    @Test
    void aThreadsStallsAreDisjointWhateverTheTimeline() {
        // Random timelines: samples idle and busy, blocks of every kind nested and overlapping,
        // pauses, lives bounded or not. However the evidence overlaps, a thread's stalls never
        // do, so its stalled time never exceeds its life.
        final long seed = Long.getLong("jfrq.test.seed", System.nanoTime());
        final String replay = "seed " + seed + ", replay with -Djfrq.test.seed=" + seed;
        final Random rnd = new Random(seed);
        final BlockKind[] kinds = BlockKind.values();
        final Stack[] stacks = {IDLE, BURN, READ0, NO_WORK, AWAITING_RESULT};
        for (int round = 0; round < 300; round++) {
            final long spanMs = 2_000 + rnd.nextInt(8_000);
            final List<Sample> samples = new ArrayList<>();
            for (long t = rnd.nextInt(50); t < spanMs; t += 1 + rnd.nextInt(rnd.nextBoolean() ? 15 : 120)) {
                final Stack st = stacks[rnd.nextInt(stacks.length)];
                samples.add(new Sample(t * MS, st, st == IDLE, rnd.nextBoolean()));
            }
            final List<Block> blocks = new ArrayList<>();
            for (int k = rnd.nextInt(40); k > 0; k--) {
                final long from = rnd.nextInt((int) spanMs + 200) - 100;
                final long length = rnd.nextBoolean() ? 1 + rnd.nextInt(40) : 1 + rnd.nextInt(1_500);
                blocks.add(blockWith(from, from + length, kinds[rnd.nextInt(kinds.length)], "on q@" + rnd.nextInt(3),
                        stacks[rnd.nextInt(stacks.length)]));
            }
            blocks.sort(Comparator.comparing(Block::interval));
            final List<Pause> pauses = new ArrayList<>();
            for (int k = rnd.nextInt(20); k > 0; k--) {
                final long from = rnd.nextInt((int) spanMs);
                pauses.add(new Pause(new Interval(from * MS, (from + 1 + rnd.nextInt(80)) * MS),
                        rnd.nextBoolean() ? PauseKind.GC : PauseKind.SAFEPOINT, "p" + k));
            }
            final long lifeStart = rnd.nextBoolean() ? 0 : rnd.nextInt(500);
            final ThreadTimeline tl = rnd.nextBoolean() ? new ThreadTimeline(LOOP, samples, blocks)
                    : new ThreadTimeline(LOOP, samples, blocks, lifeStart * MS, spanMs * MS);
            final long gapMs = 5 + rnd.nextInt(100);
            final RecordingInfo info = info(spanMs, Map.of("jdk.ExecutionSample",
                    Map.of("enabled", "true", "period", (5 + rnd.nextInt(20)) + " ms")), "jdk.ExecutionSample");
            final StallReport r = new StallAnalysis(gapMs * MS).analyse(info, List.of(tl), pauses);
            final List<Stall> stalls = r.stallsOf(LOOP);
            long total = 0;
            for (int i = 0; i < stalls.size(); i++) {
                final Stall s = stalls.get(i);
                assertTrue(s.duration() >= gapMs * MS, replay + ", round " + round + ": below the gap " + s);
                assertTrue(info.span().start() <= s.start() && s.interval().end() <= info.span().end(),
                        replay + ", round " + round + ": outside the span " + s);
                if (i > 0) {
                    assertTrue(stalls.get(i - 1).interval().end() <= s.start(),
                            replay + ", round " + round + ": " + stalls.get(i - 1) + " overlaps " + s);
                }
                total += s.duration();
            }
            final long stalled = r.threads().isEmpty() ? 0 : r.threads().getFirst().stalledNanos();
            assertEquals(total, stalled, replay + ", round " + round);
            assertTrue(stalled <= info.span().duration(), replay + ", round " + round);
        }
    }

    @Test
    void aRunWhoseEstimatedTailGivesWayToASilenceIsDroppedWhenWhatIsLeftIsUnderTheGap() {
        // Seed 68385057724875 of the property test above, reduced: two busy samples 43 ms apart
        // make a run that reaches the gap only by its estimated tail (one 17 ms period); the
        // silence after the second sample, which two socket writes explain, takes that tail.
        // What is left is 43 ms against a 46 ms gap: no stall, not a stall under the gap.
        final List<Sample> samples = new ArrayList<>(idle(0, 1_000, 10));
        samples.add(new Sample(1_000 * MS, BURN, false, false));
        samples.add(new Sample(1_043 * MS, BURN, false, false));
        samples.addAll(idle(1_139, 2_000, 10));
        final List<Block> writes = List.of(blockWith(1_025, 1_059, BlockKind.SOCKET_WRITE, "on q@0", READ0),
                blockWith(1_058, 1_093, BlockKind.SOCKET_WRITE, "on q@0", READ0));
        final RecordingInfo info = info(2_000, Map.of("jdk.ExecutionSample",
                Map.of("enabled", "true", "period", "17 ms")), "jdk.ExecutionSample");
        final StallReport r = new StallAnalysis(46 * MS).analyse(info, List.of(new ThreadTimeline(LOOP, samples, writes)),
                List.of());
        for (final Stall st : r.stallsOf(LOOP)) {
            assertTrue(st.duration() >= 46 * MS, st.toString());
        }
        assertTrue(r.stallsOf(LOOP).stream().noneMatch(st -> st.evidence() == Stall.Evidence.SAMPLES),
                r.stallsOf(LOOP).toString());
        assertEquals(List.of(new Interval(1_043 * MS, 1_139 * MS)),
                r.stallsOf(LOOP).stream().map(Stall::interval).toList());
    }

    /** A {@code java.util.Timer} thread between tasks: a wait with the time to the next one as its timeout. */
    static final Stack TIMER = stack(new Frame("java.lang.Object", "wait0", 0, "Native"),
            new Frame("java.lang.Object", "wait", 389, "JIT compiled"),
            new Frame("java.util.TimerThread", "mainLoop", 563, "JIT compiled"),
            new Frame("java.util.TimerThread", "run", 516, "Interpreted"));
    static final Stack NAP = stack(new Frame("java.lang.Thread", "sleep0", 0, "Native"),
            new Frame("java.lang.Thread", "sleep", 509, "JIT compiled"),
            new Frame("dev.app.Handler", "channelRead0", 61, "JIT compiled"));

    /** A park, a wait or a sleep that ran out its own timeout, or was woken before it did. */
    static Block timed(final long fromMs, final long toMs, final BlockKind kind, final Stack stack, final boolean timedOut) {
        return new Block(new Interval(fromMs * MS, toMs * MS), kind, "on java.util.TaskQueue@1", stack, timedOut);
    }

    @Test
    void aTimerLoopWaitingOutItsOwnDeadlinesIsScheduledIdleNotStalled() {
        // A timer thread for its whole 10 s life: four waits run out their timeout, one is woken
        // early when a sooner task is scheduled, and it is seen running a task after each.
        final List<Block> waits = List.of(timed(0, 2_000, BlockKind.OBJECT_WAIT, TIMER, true),
                timed(2_002, 4_000, BlockKind.OBJECT_WAIT, TIMER, true),
                timed(4_002, 6_000, BlockKind.OBJECT_WAIT, TIMER, true),
                timed(6_002, 7_000, BlockKind.OBJECT_WAIT, TIMER, false),
                timed(7_002, 9_000, BlockKind.OBJECT_WAIT, TIMER, true));
        final List<Sample> tasks = List.of(new Sample(2_001 * MS, BURN, false, false),
                new Sample(4_001 * MS, BURN, false, false), new Sample(6_001 * MS, BURN, false, false),
                new Sample(7_001 * MS, BURN, false, false), new Sample(9_001 * MS, BURN, false, false));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(), List.of(lived(tasks, waits, 0, 10_000)),
                List.of());
        assertTrue(r.stalls().isEmpty(), r.stalls().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("5 waits totalling ")
                && w.contains("(event-loop-1): scheduled idle, not stalls")), r.warnings().toString());

        // --idle none puts nothing aside.
        final StallReport none = new StallAnalysis(50 * MS, IdleMatcher.none()).analyse(sampledInfo(),
                List.of(lived(tasks, waits, 0, 10_000)), List.of());
        assertEquals(5, none.stalls().stream().filter(st -> st.verdict() == Verdict.OBJECT_WAIT).count(),
                none.stalls().toString());
        assertTrue(none.warnings().stream().noneMatch(w -> w.contains("scheduled idle")), none.warnings().toString());
    }

    @Test
    void oneWaitThatRanOutItsTimeoutIsStillAStall() {
        // A caller whose get with a timeout gave up once: it waited the whole time for nothing.
        final Block gaveUp = timed(1_000, 9_000, BlockKind.OBJECT_WAIT, TIMER, true);
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(List.of(), List.of(gaveUp), 0, 10_000)), List.of());
        assertEquals(List.of(Verdict.OBJECT_WAIT), r.stalls().stream().map(Stall::verdict).toList());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("scheduled idle")), r.warnings().toString());
    }

    @Test
    void sleepsThatRanOutOnALoopThatMostlyIdlesAreStalls() {
        // An event loop at its selector that sleeps twice: its own choice, but not where it idles.
        final List<Sample> samples = new ArrayList<>(idle(0, 1_000, 10));
        samples.addAll(idle(1_250, 5_000, 10));
        samples.addAll(idle(5_250, 10_000, 10));
        final List<Block> naps = List.of(timed(1_000, 1_250, BlockKind.SLEEP, NAP, true),
                timed(5_000, 5_250, BlockKind.SLEEP, NAP, true));
        final StallReport r = analyse(samples, naps, List.of());
        assertEquals(List.of(Verdict.SLEEP, Verdict.SLEEP), r.stalls().stream().map(Stall::verdict).toList());
    }

    @Test
    void waitsFromAnotherPlaceOnATimerThreadAreNotItsLoop() {
        // The loop is the timed-out sleeps; a monitor wait elsewhere on the same thread is judged on its own.
        final List<Block> blocks = List.of(timed(0, 3_000, BlockKind.SLEEP, NAP, true),
                timed(3_000, 6_000, BlockKind.SLEEP, NAP, true),
                timed(6_000, 6_500, BlockKind.OBJECT_WAIT, TIMER, false));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(lived(List.of(), blocks, 0, 10_000)), List.of());
        assertTrue(r.stalls().stream().anyMatch(st -> st.verdict() == Verdict.OBJECT_WAIT), r.stalls().toString());
        assertTrue(r.stalls().stream().noneMatch(st -> st.verdict() == Verdict.SLEEP), r.stalls().toString());
    }
}
