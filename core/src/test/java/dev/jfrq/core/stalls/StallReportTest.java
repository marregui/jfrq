// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.StallReport.Sight;
import dev.jfrq.core.stalls.StallReport.ThreadSummary;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;

import static dev.jfrq.core.stalls.StallAnalysisTest.MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StallReport#unseen()}: the verdict on what a recording can show, with the arithmetic
 * of its remedy, and the classification behind it on hand-built timelines.
 */
class StallReportTest {

    /** A 10 s recording whose native sampler ran at {@code nativePeriodMs}. */
    static RecordingInfo info(final long nativePeriodMs) {
        return new RecordingInfo(Path.of("a.jfr"), new Interval(0, 10_000 * MS), 1, Map.of(),
                Map.of("jdk.NativeMethodSample", Map.of("enabled", "true", "period", nativePeriodMs + " ms"),
                        "jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms")),
                Set.of(), List.of());
    }

    static ThreadSummary summary(final int id, final Sight sight, final long unseenMs, final long cadenceMs,
                                 final long roundTripMs) {
        return new ThreadSummary(new ThreadRef(id, "loop-" + id), 100, 0, cadenceMs * MS, 0, 0, 0, unseenMs * MS, sight,
                roundTripMs * MS);
    }

    static StallReport report(final long nativePeriodMs, final ThreadSummary... threads) {
        return new StallReport(info(nativePeriodMs), 50 * MS, List.of(threads), List.of(), List.of(), List.of());
    }

    @Test
    void theSamplerLineSaysWhyAndWhatWouldFixIt() {
        // The field case: 20 ms native period, each loop sampled every ~571 ms, blind below 1.75 s.
        // Absence 583.3 ms = 571 ms of round trip + 12.3 ms of its own; at 1 ms the round trip is
        // 28.6 ms, so 3 × (12.3 + 28.6) = 123 ms is as far as the sampler can go.
        final StallReport r = report(20, summary(1, Sight.NATIVE_SAMPLER, 1_750, 571, 571),
                summary(2, Sight.NATIVE_SAMPLER, 1_740, 571, 571), summary(3, Sight.CLEAR, 0, 0, 0));
        assertEquals(List.of("on 2 of 3 threads, a stall no event explains is seen only from 1.74 s to 1.75 s: each "
                + "is sampled in native code every ~571 ms, about 29 threads in native code sharing the sampler's "
                + "one native slot per 20.0 ms period. Record with jdk.NativeMethodSample#period=1ms, the shortest "
                + "the sampler takes, to see them from ~123 ms; shorter ones only blocking events can show"), r.unseen());
        assertEquals("1.75 s", r.threads().getFirst().unseenBelow(10_000 * MS));
        assertEquals("", r.threads().get(2).unseenBelow(10_000 * MS));
    }

    @Test
    void theRemedyIsThePeriodThatReachesTheGapWhenOneDoes() {
        // Absence 50 ms, all of it round trip, at 20 ms: 6 ms brings three absences to 45 ms.
        assertTrue(report(20, summary(1, Sight.NATIVE_SAMPLER, 150, 50, 50)).unseen().getFirst()
                .endsWith("Record with jdk.NativeMethodSample#period=6ms to see them from the gap"));
        // Already at the floor: only events can do better.
        assertTrue(report(1, summary(1, Sight.NATIVE_SAMPLER, 150, 50, 50)).unseen().getFirst()
                .endsWith("The sampler is at its shortest period already: only blocking events can show these "
                        + "stalls, so record them with low thresholds and no throttle"));
    }

    @Test
    void aBoundPastTheRecordingIsNoBound() {
        // 10 s recorded: a thread blind below 12 s is not seen at all, not "seen from 12 s".
        assertTrue(report(20, summary(1, Sight.NATIVE_SAMPLER, 1_750, 571, 571),
                summary(2, Sight.NATIVE_SAMPLER, 12_000, 4_000, 4_000)).unseen().getFirst()
                .startsWith("on 2 of 2 threads, a stall no event explains is seen only from 1.75 s, and not at all "
                        + "on 1 of them"));
        assertTrue(report(20, summary(1, Sight.OWN_ABSENCE, 12_000, 51, 0)).unseen().getFirst()
                .contains("is not seen at all"));
        // The PER THREAD cell says the same: a dash, as for a thread seen too little to have a bound.
        assertEquals("—", summary(2, Sight.NATIVE_SAMPLER, 12_000, 4_000, 4_000).unseenBelow(10_000 * MS));
        assertEquals("10.0 s", summary(2, Sight.NATIVE_SAMPLER, 10_000, 4_000, 4_000).unseenBelow(10_000 * MS));
    }

    @Test
    void theRemedyNeverGoesUnderTheSamplersFloor() {
        // 1.5 ms: one whole millisecond shorter would be 0.5 ms, below the 1 ms the sampler takes.
        final RecordingInfo fine = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 10_000 * MS), 1, Map.of(),
                Map.of("jdk.NativeMethodSample", Map.of("enabled", "true", "period", "1500 us")), Set.of(), List.of());
        final String line = new StallReport(fine, 50 * MS, List.of(summary(1, Sight.NATIVE_SAMPLER, 1_750, 571, 571)),
                List.of(), List.of(), List.of()).unseen().getFirst();
        assertTrue(line.contains("jdk.NativeMethodSample#period=1ms"), line);
    }

    @Test
    void aThreadsOwnAbsencesAreNotTheSamplers() {
        final StallReport r = report(20, summary(1, Sight.OWN_ABSENCE, 510, 51, 0),
                summary(2, Sight.OWN_ABSENCE, 620, 51, 0), summary(3, Sight.OWN_ABSENCE, 0, 0, 0));
        assertEquals(List.of("on 3 of 3 threads, a stall no event or pause explains is seen only from 510 ms to "
                + "620 ms: their silences are mostly the thread itself, parked or blocked where the sampler cannot "
                + "see it, not the sampler's pace, so no sampling period changes that much; blocking events are what "
                + "show their stalls"), r.unseen());
        assertEquals("—", r.threads().get(2).unseenBelow(10_000 * MS));
        assertTrue(report(20, summary(1, Sight.OWN_ABSENCE, 0, 0, 0)).unseen().getFirst()
                .contains("is not seen at all"));
        assertEquals(List.of(), report(20, summary(1, Sight.CLEAR, 0, 0, 0)).unseen());
    }

    /** Native samples every {@code stepMs} over [fromMs, toMs). */
    static List<Sample> inNative(final long fromMs, final long toMs, final long stepMs) {
        final List<Sample> out = new ArrayList<>();
        for (long t = fromMs; t < toMs; t += stepMs) {
            out.add(new Sample(t * MS, StallAnalysisTest.IDLE, true, true));
        }
        return out;
    }

    @Test
    void theSlotLimitsAThreadSeenAllAlongAndNotOneAbsentOnItsOwn() {
        // "loop" sits in native code all along and is seen every 100 ms: the slot's pace.
        // "bursty" is seen at that pace for 2 s of 10: a fifth of the share the slot gives, though
        // its routine gap is the slot's (one long gap in twenty is not routine).
        // "halftime" is seen at that pace for 0.8 s in every 1.5 s: more than half the share, but
        // its routine gap is its own 800 ms absence, eight round trips.
        // "rare" is seen once a second all along.
        final List<Sample> halftime = new ArrayList<>();
        for (long from = 0; from < 10_000; from += 1_500) {
            halftime.addAll(inNative(from, Math.min(from + 800, 10_000), 100));
        }
        final StallReport r = new StallAnalysis(50 * MS).analyse(info(10), List.of(
                new ThreadTimeline(new ThreadRef(1, "loop"), inNative(0, 10_000, 100), List.of()),
                new ThreadTimeline(new ThreadRef(2, "bursty"), inNative(0, 2_000, 100), List.of()),
                new ThreadTimeline(new ThreadRef(3, "halftime"), halftime, List.of()),
                new ThreadTimeline(new ThreadRef(4, "rare"), inNative(0, 10_000, 1_000), List.of())), List.of());
        assertEquals(List.of("bursty", "halftime", "loop", "rare"),
                r.threads().stream().map(t -> t.thread().name()).toList());
        assertEquals(List.of(Sight.OWN_ABSENCE, Sight.OWN_ABSENCE, Sight.NATIVE_SAMPLER, Sight.OWN_ABSENCE),
                r.threads().stream().map(ThreadSummary::sight).toList(), r.threads().toString());
        assertEquals(100 * MS, r.threads().get(2).roundTripNanos());
        assertEquals(2, r.unseen().size(), r.unseen().toString());
    }

    @Test
    void theHeadlineStatesTheBoundInOneLine() {
        // Every limited thread, whatever the reason, in one bound: the best thread's holds on all
        // of them, and the worst's is how far it reaches.
        assertEquals("unexplained stalls under 510 ms (1.75 s on the worst thread) cannot be seen on 2 of 3 threads",
                report(20, summary(1, Sight.NATIVE_SAMPLER, 1_750, 571, 571), summary(2, Sight.OWN_ABSENCE, 510, 51, 0),
                        summary(3, Sight.CLEAR, 0, 0, 0)).unseenHeadline());
        assertEquals("unexplained stalls under 750 ms cannot be seen on 1 of 1 threads",
                report(20, summary(1, Sight.NATIVE_SAMPLER, 750, 250, 250)).unseenHeadline());
        // A bound past the recording is no bound: those threads are counted as not seen at all.
        assertEquals("unexplained stalls under 750 ms cannot be seen on 2 of 2 threads, and none at all on 1 of them",
                report(20, summary(1, Sight.NATIVE_SAMPLER, 750, 250, 250),
                        summary(2, Sight.NATIVE_SAMPLER, 12_000, 4_000, 4_000)).unseenHeadline());
        assertEquals("no unexplained stall can be seen on 1 of 1 threads",
                report(20, summary(1, Sight.OWN_ABSENCE, 0, 0, 0)).unseenHeadline());
        assertNull(report(20, summary(1, Sight.CLEAR, 0, 0, 0)).unseenHeadline());
        // Ten times apart and more, the worst says nothing a reader can use.
        assertEquals("unexplained stalls under 85.0 ms (far longer on some) cannot be seen on 2 of 2 threads",
                report(20, summary(1, Sight.OWN_ABSENCE, 85, 30, 0), summary(2, Sight.OWN_ABSENCE, 9_000, 3_000, 0))
                        .unseenHeadline());
    }
}
