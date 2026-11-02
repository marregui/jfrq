// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.List;

import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

/**
 * One interval during which a watched thread did not return to its idle point, with the
 * best explanation the recording supports.
 *
 * @param thread   the thread that stalled
 * @param interval when
 * @param verdict  the explanation category
 * @param detail   the explanation in one line: which lock, which host, which method
 * @param stack    the most representative stack: the blocking event's, or the busiest sample's
 * @param evidence how the interval was found
 * @param samples  sampler observations inside the interval (zero for silent gaps)
 * @param meanwhile for a park or a monitor enter with no holder in the recording, the other
 *                 threads seen running during it, most samples first: a correlation, not a holder
 */
public record Stall(ThreadRef thread, Interval interval, Verdict verdict, String detail, Stack stack,
                    Evidence evidence, int samples, List<Meanwhile> meanwhile) {

    public Stall {
        meanwhile = List.copyOf(meanwhile);
    }

    /** A stall with nobody seen running meanwhile, which is every stall until the analysis's last step. */
    public Stall(final ThreadRef thread, final Interval interval, final Verdict verdict, final String detail,
                 final Stack stack, final Evidence evidence, final int samples) {
        this(thread, interval, verdict, detail, stack, evidence, samples, List.of());
    }

    /** This stall with the threads seen running during it. */
    public Stall withMeanwhile(final List<Meanwhile> seen) {
        return new Stall(thread, interval, verdict, detail, stack, evidence, samples, seen);
    }

    public enum Verdict {
        /** The whole JVM was stopped for garbage collection. */
        GC_PAUSE,
        /** The whole JVM was stopped at a safepoint that was not a GC. */
        SAFEPOINT,
        /** Blocked entering a {@code synchronized} block; the detail names the holder when known. */
        BLOCKED_MONITOR,
        /** Parked in a {@code java.util.concurrent} lock or queue. */
        PARKED,
        /** In {@code Object.wait()}. */
        OBJECT_WAIT,
        /** In {@code Thread.sleep()}. */
        SLEEP,
        /** Blocked in a synchronous socket or file operation. */
        BLOCKING_IO,
        /** Running one dominant piece of code without yielding. */
        BUSY,
        /** Running many different things without yielding: the loop is saturated, not stuck. */
        SATURATED,
        /** No samples and no event: blocked below the recording's thresholds, or sampled too sparsely. */
        UNEXPLAINED;

        /** True for verdicts that are the thread's own doing rather than the JVM's. */
        public boolean isThreadLocal() {
            return this != GC_PAUSE && this != SAFEPOINT;
        }
    }

    public enum Evidence {
        /** A blocking event of at least the gap length. */
        EVENT,
        /** A run of non-idle samples spanning at least the gap. */
        SAMPLES,
        /** An absence of samples longer than the thread's sampling cadence allows. */
        SILENCE
    }

    public long start() {
        return interval.start();
    }

    public long duration() {
        return interval.duration();
    }
}
