// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.List;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.util.Sorts;

/**
 * Every thread's samples, watched or not, kept to say who the sampler saw running while a
 * stall with no recorded holder lasted ({@link Meanwhile}). A {@code jdk.ThreadPark} names no
 * owner, so for a park on a lock the samples of the other threads at that moment are the only
 * trace of whoever held it; the answer is a correlation and is labelled as one.
 *
 * <p>Per event this is an append to the thread's {@link Samples}, or for a watched thread
 * nothing beyond its timeline. On the first question every thread's samples are put in time
 * order. Idle is judged with a matcher that also calls idle the native waits that run nothing
 * ({@link IdleMatcher#forBystanders}): an accept, a wait for a child process, a blocking read
 * share the sampler's one native slot with the thread that really ran, and without them they
 * fill the list. A question reads a thread's samples inside the stall until reading them has
 * cost as much as the thread has samples; from then on the thread's running count of samples
 * that are not idle answers it in two binary searches. Short stalls on a narrow {@code --thread}
 * then judge only the samples inside them, and a stall of minutes with hundreds of threads
 * sampled through it costs no more than one pass over them. The frames are read only for the
 * threads a question names.
 */
final class Bystanders {

    /** Threads named per stall. */
    static final int SHOWN = 3;

    private final IdleMatcher idle;
    /** What names a sample's site under {@code --app}; {@code null} without it. */
    private final SiteKey siteKey;
    private final ObjList<ThreadRef> threads = new ObjList<>();
    private final ObjList<Samples> samples = new ObjList<>();
    /** Watched threads' samples, in time order, turned into {@link Samples} on the first question. */
    private final ObjList<List<Sample>> watched = new ObjList<>();
    private boolean counted;
    /** Scratch for {@link #during}: the threads ranked so far, best first, and their counts (G-3.1). */
    private final int[] best = new int[SHOWN];
    private final int[] bestJava = new int[SHOWN];
    private final int[] bestNative = new int[SHOWN];
    /** Scratch for {@link #describe}: innermost frames in first-seen order, their counts, a stack for each. */
    private final ObjList<Frame> frames = new ObjList<>();
    private final ObjLongHashMap<Frame> frameCounts = new ObjLongHashMap<>(64);
    private final ObjObjHashMap<Frame, Stack> frameStacks = new ObjObjHashMap<>(64);

    /**
     * @param idle    what an idle sample of another thread looks like
     * @param siteKey what names a site under {@code --app}, or {@code null} to name none
     */
    Bystanders(final IdleMatcher idle, final SiteKey siteKey) {
        this.idle = idle;
        this.siteKey = siteKey;
    }

    /** No thread's samples: every question has no answer. */
    static Bystanders none() {
        return new Bystanders(IdleMatcher.none(), null);
    }

    /** A thread not watched, whose samples the collector kept flat. */
    void add(final ThreadRef thread, final Samples threadSamples) {
        threads.add(thread);
        samples.add(threadSamples);
        watched.add(null);
        counted = false;
    }

    /** A watched thread, with its timeline's samples in time order. */
    void add(final ThreadRef thread, final List<Sample> threadSamples) {
        threads.add(thread);
        samples.add(null);
        watched.add(threadSamples);
        counted = false;
    }

    /**
     * The threads other than {@code self} with samples inside {@code interval} that are not
     * idle, most Java samples first (a Java sample is the thread executing), then most native
     * ones, then by name; at most {@link #SHOWN}. Empty when there were none, which says only
     * that the sampler saw nobody: a stall shorter than another thread's sampling interval can
     * hold no sample of it.
     */
    List<Meanwhile> during(final ThreadRef self, final Interval interval) {
        count();
        int ranked = 0;
        for (int i = 0, n = threads.size(); i < n; i++) {
            final Samples s = samples.getQuick(i);
            final int java = s.java.busy(interval, idle);
            final int inNative = s.inNative.busy(interval, idle);
            if (java + inNative == 0 || threads.getQuick(i).equals(self)) {
                continue;
            }
            int at = ranked;
            while (at > 0 && isAhead(i, java, inNative, at - 1)) {
                at--;
            }
            if (at == SHOWN) {
                continue;
            }
            for (int k = Math.min(ranked, SHOWN - 1); k > at; k--) {
                best[k] = best[k - 1];
                bestJava[k] = bestJava[k - 1];
                bestNative[k] = bestNative[k - 1];
            }
            best[at] = i;
            bestJava[at] = java;
            bestNative[at] = inNative;
            ranked = Math.min(ranked + 1, SHOWN);
        }
        final List<Meanwhile> seen = new ArrayList<>(ranked);
        for (int k = 0; k < ranked; k++) {
            seen.add(describe(best[k], bestJava[k], bestNative[k], interval));
        }
        return seen;
    }

    /** Whether thread {@code i}, with these counts, ranks ahead of the one at {@code rank}. */
    private boolean isAhead(final int i, final int java, final int inNative, final int rank) {
        if (java != bestJava[rank]) {
            return java > bestJava[rank];
        }
        if (inNative != bestNative[rank]) {
            return inNative > bestNative[rank];
        }
        return ThreadRef.ORDER.compare(threads.getQuick(i), threads.getQuick(best[rank])) < 0;
    }

    /** Every thread's samples in time order. */
    private void count() {
        if (counted) {
            return;
        }
        for (int i = 0, n = threads.size(); i < n; i++) {
            Samples s = samples.getQuick(i);
            if (s == null) {
                s = new Samples();
                final List<Sample> list = watched.getQuick(i);
                for (int j = 0, m = list.size(); j < m; j++) {
                    final Sample sample = list.get(j);
                    s.add(sample.time(), sample.stack(), sample.isInNative());
                }
                samples.setQuick(i, s);
                watched.setQuick(i, null);
            }
            s.java.sort();
            s.inNative.sort();
        }
        counted = true;
    }

    /** The thread at index {@code i} as the stall saw it: its counts, and the innermost frame most of them had. */
    private Meanwhile describe(final int i, final int java, final int inNative, final Interval interval) {
        frames.clear();
        frameCounts.clear();
        frameStacks.clear();
        final Samples s = samples.getQuick(i);
        tally(s.java, interval);
        tally(s.inNative, interval);
        Frame top = frames.getQuick(0);
        for (int f = 1, n = frames.size(); f < n; f++) {
            final Frame frame = frames.getQuick(f);
            if (frameCounts.get(frame) > frameCounts.get(top)) {
                top = frame;
            }
        }
        final String site = siteKey == null ? null : siteKey.of(frameStacks.get(top));
        return new Meanwhile(threads.getQuick(i), java, inNative, top.stableName(), (int) frameCounts.get(top), site);
    }

    private void tally(final Series series, final Interval interval) {
        for (int j = series.times.lowerBound(interval.start()), n = series.times.size(); j < n; j++) {
            if (series.times.getQuick(j) >= interval.end()) {
                break;
            }
            final Stack stack = series.stacks.getQuick(j);
            if (Series.isBusy(stack, idle)) {
                final Frame top = stack.topOrNull();
                if (frameCounts.keyIndex(top) >= 0) {
                    frames.add(top);
                    frameStacks.put(top, stack);
                }
                frameCounts.increment(top, 1);
            }
        }
    }

    /** One thread's samples, Java and native apart, in arrival order until the first question. */
    static final class Samples {
        private final Series java = new Series();
        private final Series inNative = new Series();

        void add(final long time, final Stack stack, final boolean isInNative) {
            (isInNative ? inNative : java).add(time, stack);
        }
    }

    /**
     * Samples of one kind: times and stacks side by side, and once the questions have read as
     * many samples as there are, how many before each one were not idle.
     */
    private static final class Series {
        /**
         * Element moves an insertion sort may make per sample before the merge sort takes over.
         * A thread's samples arrive nearly in order: on a 30-minute recording, 13,768 samples of
         * one thread were 1,208 moves from it, against the merge sort's n log n.
         */
        private static final int INSERTION_MOVES_PER_SAMPLE = 8;

        private LongList times = new LongList(16);
        private ObjList<Stack> stacks = new ObjList<>(16);
        /** {@code busyBefore[j]}: samples before index {@code j} that are not idle; one longer than the list. */
        private int[] busyBefore;
        /** Samples the questions have read one by one, before {@link #busyBefore} was built. */
        private long read;

        void add(final long time, final Stack stack) {
            times.add(time);
            stacks.add(stack);
        }

        void sort() {
            if (!insertionSort()) {
                final int[] order = Sorts.order(times);
                final LongList sortedTimes = new LongList(order.length);
                final ObjList<Stack> sortedStacks = new ObjList<>(order.length);
                for (final int j : order) {
                    sortedTimes.add(times.getQuick(j));
                    sortedStacks.add(stacks.getQuick(j));
                }
                times = sortedTimes;
                stacks = sortedStacks;
            }
        }

        /** Samples inside {@code interval} that are not idle, by reading them or by the running count. */
        int busy(final Interval interval, final IdleMatcher idle) {
            final int from = times.lowerBound(interval.start());
            final int to = times.lowerBound(interval.end());
            if (busyBefore == null && read + (to - from) > times.size()) {
                busyBefore = new int[times.size() + 1];
                for (int j = 0, n = times.size(); j < n; j++) {
                    busyBefore[j + 1] = busyBefore[j] + (isBusy(stacks.getQuick(j), idle) ? 1 : 0);
                }
            }
            if (busyBefore != null) {
                return busyBefore[to] - busyBefore[from];
            }
            read += to - from;
            int busy = 0;
            for (int j = from; j < to; j++) {
                busy += isBusy(stacks.getQuick(j), idle) ? 1 : 0;
            }
            return busy;
        }

        /** Whether a sample is the thread running: it has a frame, and the matcher does not call it idle. */
        static boolean isBusy(final Stack stack, final IdleMatcher idle) {
            return !stack.isEmpty() && !idle.isIdle(stack);
        }

        /**
         * Sorts in place while the list is nearly in order; false, with the list a permutation of
         * what it was, once that has cost {@link #INSERTION_MOVES_PER_SAMPLE} moves per sample.
         */
        private boolean insertionSort() {
            final int n = times.size();
            long budget = (long) INSERTION_MOVES_PER_SAMPLE * n;
            for (int i = 1; i < n; i++) {
                final long time = times.getQuick(i);
                if (time >= times.getQuick(i - 1)) {
                    continue;
                }
                final Stack stack = stacks.getQuick(i);
                int j = i - 1;
                while (j >= 0 && times.getQuick(j) > time) {
                    times.setQuick(j + 1, times.getQuick(j));
                    stacks.setQuick(j + 1, stacks.getQuick(j));
                    j--;
                    budget--;
                }
                times.setQuick(j + 1, time);
                stacks.setQuick(j + 1, stack);
                if (budget < 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
