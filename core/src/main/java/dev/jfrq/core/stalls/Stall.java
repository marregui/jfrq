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
 * @param meanwhile for a stall whose lock has no recorded holder, the other threads seen
 *                 running during it, most samples first: a correlation, not a holder
 * @param holder   whether the stall is a wait on a lock, and if so whether the recording names
 *                 who held it; decided from the blocking events behind the stall, never from
 *                 its detail
 */
public record Stall(ThreadRef thread, Interval interval, Verdict verdict, String detail, Stack stack,
                    Evidence evidence, int samples, List<Meanwhile> meanwhile, LockHolder holder) {

    public Stall {
        meanwhile = List.copyOf(meanwhile);
        if (!meanwhile.isEmpty() && holder != LockHolder.UNRECORDED) {
            throw new IllegalArgumentException("threads seen meanwhile stand in only for an unrecorded holder");
        }
    }

    /** A stall that is no wait on a lock, with nobody seen running meanwhile. */
    public Stall(final ThreadRef thread, final Interval interval, final Verdict verdict, final String detail,
                 final Stack stack, final Evidence evidence, final int samples) {
        this(thread, interval, verdict, detail, stack, evidence, samples, LockHolder.NOT_A_LOCK);
    }

    /** A stall with nobody seen running meanwhile, which is every stall until the analysis's last step. */
    public Stall(final ThreadRef thread, final Interval interval, final Verdict verdict, final String detail,
                 final Stack stack, final Evidence evidence, final int samples, final LockHolder holder) {
        this(thread, interval, verdict, detail, stack, evidence, samples, List.of(), holder);
    }

    /**
     * A stall with the threads seen running during it, which only a lock with no recorded holder
     * has; with none, a stall that is no wait on a lock.
     */
    public Stall(final ThreadRef thread, final Interval interval, final Verdict verdict, final String detail,
                 final Stack stack, final Evidence evidence, final int samples, final List<Meanwhile> meanwhile) {
        this(thread, interval, verdict, detail, stack, evidence, samples, meanwhile,
                meanwhile.isEmpty() ? LockHolder.NOT_A_LOCK : LockHolder.UNRECORDED);
    }

    /** This stall with the threads seen running during it. */
    public Stall withMeanwhile(final List<Meanwhile> seen) {
        return new Stall(thread, interval, verdict, detail, stack, evidence, samples, seen, holder);
    }

    /** The same stall with another detail or interval: what it rests on, its holder, is kept. */
    Stall with(final Interval newInterval, final String newDetail) {
        return new Stall(thread, newInterval, verdict, newDetail, stack, evidence, samples, meanwhile, holder);
    }

    /**
     * Whether a stall is a wait on a lock and whether the recording names who held it. A
     * {@code jdk.ThreadPark} never names an owner; a {@code jdk.JavaMonitorEnter} names one
     * when JFR recorded the previous owner or another thread's wait fills it in ({@link Holders}).
     */
    public enum LockHolder {
        /** A sleep, an {@code Object.wait}, blocking I/O, a park on no object, a run of samples, a pause. */
        NOT_A_LOCK,
        /** A park on an object, or a monitor enter whose holder the recording does not name. */
        UNRECORDED,
        /** A monitor enter whose holder the recording names. */
        KNOWN;

        /** What one blocking event says. */
        static LockHolder of(final Timeline.Block b) {
            return switch (b.kind()) {
                case PARK -> ParkShapes.NO_BLOCKER.equals(b.detail()) ? NOT_A_LOCK : UNRECORDED;
                case MONITOR -> b.owner() == null ? UNRECORDED : KNOWN;
                case OBJECT_WAIT, SLEEP, SOCKET_READ, SOCKET_WRITE, FILE_READ, FILE_WRITE, FILE_FORCE -> NOT_A_LOCK;
            };
        }

        /**
         * What a stall made of several blocking events says: a holder any of them names wins,
         * since a correlation must never stand beside a known holder; then a lock nobody is named
         * for; and only events that are no lock at all make a stall that is none.
         */
        LockHolder and(final LockHolder other) {
            if (this == KNOWN || other == KNOWN) {
                return KNOWN;
            }
            return this == UNRECORDED || other == UNRECORDED ? UNRECORDED : NOT_A_LOCK;
        }
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
