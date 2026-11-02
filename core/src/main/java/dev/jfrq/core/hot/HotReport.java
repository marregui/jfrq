// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.hot;

import java.time.Instant;
import java.util.List;

import dev.jfrq.core.jfr.RecordingInfo;

/**
 * Where the CPU went, from {@code jdk.ExecutionSample}: every list ranked by samples, most first,
 * then by name. A sample is a thread found running Java code; its share of all samples is its
 * share of the Java threads' CPU as the sampler saw it ({@code docs/design/hot.md}).
 *
 * @param info        the recording
 * @param samples     every execution sample read
 * @param siteKey     what a site is, in the words the report prints ({@code SiteKey.description})
 * @param bucketNanos the timeline's bucket width: the one asked for, or the smallest multiple of it
 *                    that keeps the span within {@link HotCollector#MAX_BUCKETS} buckets
 * @param originNanos the start of the first bucket, epoch nanoseconds, a multiple of the width
 * @param buckets     how many buckets each {@link Group#timeline} has
 * @param groups      the thread families
 * @param leaves      the innermost frame of every sample, across families
 */
public record HotReport(RecordingInfo info, long samples, String siteKey, long bucketNanos, long originNanos,
        int buckets, List<Group> groups, List<Count> leaves) {

    /** Sites listed per family, by every renderer; the rest are summed on one line. */
    public static final int SITES_SHOWN = 4;

    /** Frames listed below each site, by every renderer; the rest are summed on one line. */
    public static final int NEXT_SHOWN = 3;

    /** Families given a column of their own in the timeline, beside the total. */
    public static final int TIMELINE_GROUPS = 5;

    /** What {@link Site#next} says for the samples whose site is their innermost frame. */
    public static final String AT_SITE = "(the site itself)";

    /** {@code part}'s share of every sample, 0 when there are none. */
    public double share(final long part) {
        return samples == 0 ? 0 : (double) part / samples;
    }

    /** When bucket {@code index} starts, epoch nanoseconds. */
    public long bucketStartNanos(final int index) {
        return originNanos + index * bucketNanos;
    }

    /** {@link #bucketStartNanos} as an instant. */
    public Instant bucketStart(final int index) {
        final long nanos = bucketStartNanos(index);
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    /** The samples of every family in bucket {@code index}. */
    public long bucketTotal(final int index) {
        long total = 0;
        for (final Group g : groups) {
            total += g.timeline().get(index);
        }
        return total;
    }

    /**
     * One thread family.
     *
     * @param name     the family, {@code pool-N-thread-N}, or the thread's name when no digits fold
     * @param threads  how many sampled threads it has
     * @param samples  its samples
     * @param sites    its samples by site, each split by the frame below the site
     * @param timeline its samples per bucket, {@link HotReport#buckets} of them
     */
    public record Group(String name, int threads, long samples, List<Site> sites, List<Long> timeline) {
    }

    /**
     * The frame a site key names, and what the samples under it were doing.
     *
     * @param frame   {@code pkg.Class.method}
     * @param samples the samples whose stack names this site
     * @param next    the same samples by the outermost non-JDK frame below the site, else the frame
     *                the site called, else {@link #AT_SITE}
     */
    public record Site(String frame, long samples, List<Count> next) {
    }

    /** A frame and how many samples it accounts for. */
    public record Count(String frame, long samples) {
    }
}
