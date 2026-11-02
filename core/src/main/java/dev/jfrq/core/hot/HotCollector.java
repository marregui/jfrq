// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.hot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.report.RecordingSummary;

/**
 * Where the CPU went: {@code jdk.ExecutionSample} by thread family, by the frame a {@link SiteKey}
 * names in each sampled stack and the frame below it, by leaf method, and over time.
 *
 * <p>Each sample is counted against its stack, per family; the key and the frames below it are
 * resolved once per distinct stack in {@link #finish}, not per sample, because a recording
 * holds orders of magnitude more samples than stacks. A family is
 * {@link RecordingSummary#family}: {@code AWT-EventQueue-0} and {@code AWT-EventQueue-1} are one
 * row, {@code pool-3-thread-7} and {@code pool-4-thread-1} another. The per-sample path is two
 * probes into pre-sized tables and one append of the sample's time.
 *
 * <p>Only threads running Java code are in {@code jdk.ExecutionSample}; a thread in native code
 * is in {@code jdk.NativeMethodSample}, which says where it is, not that it uses CPU, so it is
 * not read ({@code docs/design/hot.md}).
 */
public final class HotCollector implements JfrReader.Sink {

    public static final String EXECUTION_SAMPLE = EventKinds.nameOf(EventKinds.EXECUTION_SAMPLE);

    private static final Set<String> TYPES = Set.of(EXECUTION_SAMPLE);

    /**
     * The most timeline buckets a report holds per family: a bucket asked for that would make
     * more over the samples' span is widened to fit, and the report gives the width it used.
     */
    static final int MAX_BUCKETS = 1_000;

    private final SiteKey key;
    private final long bucketNanos;
    private final IdentityObjObjHashMap<ThreadRef, Family> byThread = new IdentityObjObjHashMap<>(256);
    private final ObjObjHashMap<String, Family> byName = new ObjObjHashMap<>(64);
    private Interner interner = new Interner();
    private HotReport report;

    /**
     * @param key         which frame of a sampled stack names its site: the innermost non-JDK
     *                    frame, or the innermost under {@code --app}'s prefixes
     * @param bucketNanos the timeline's resolution, positive
     */
    public HotCollector(final SiteKey key, final long bucketNanos) {
        if (bucketNanos <= 0) {
            throw new IllegalArgumentException("bucket must be positive: " + bucketNanos);
        }
        this.key = key;
        this.bucketNanos = bucketNanos;
    }

    @Override
    public void begin(final Interner interner) {
        this.interner = interner;
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void accept(@Transient final RecordedEvent e) {
        accept(e, EventKinds.kindOf(e.getEventType().getName()));
    }

    @Override
    public void accept(@Transient final RecordedEvent e, final int kind) {
        if (kind != EventKinds.EXECUTION_SAMPLE) {
            return;
        }
        final ThreadRef thread = interner.thread(e);
        if (thread != null) {
            sample(thread, Events.startNanos(e), Events.stack(e, interner));
        }
    }

    /** A sample whose fields are already read: what {@link #accept(RecordedEvent, int)} does, for tests. */
    void sample(final ThreadRef thread, final long timeNanos, final Stack stack) {
        final int index = byThread.keyIndex(thread);
        final Family family = index < 0 ? byThread.valueAtQuick(index) : byThread.putAt(index, thread, family(thread));
        family.stacks.increment(stack, 1);
        family.times.add(timeNanos);
    }

    /** The family {@code thread} belongs to, made on its first thread: once per thread, not per sample. */
    private Family family(final ThreadRef thread) {
        final String name = RecordingSummary.family(thread.name());
        final int index = byName.keyIndex(name);
        final Family family = index < 0 ? byName.valueAtQuick(index) : byName.putAt(index, name, new Family(name));
        family.threads++;
        return family;
    }

    @Override
    public void finish(final RecordingInfo info) {
        long firstNanos = Long.MAX_VALUE;
        long lastNanos = Long.MIN_VALUE;
        long total = 0;
        for (int s = 0, n = byName.slots(); s < n; s++) {
            if (byName.hasKeyAtSlot(s)) {
                final LongList times = byName.valueAtSlot(s).times;
                for (int i = 0, m = times.size(); i < m; i++) {
                    final long timeNanos = times.getQuick(i);
                    firstNanos = Math.min(firstNanos, timeNanos);
                    lastNanos = Math.max(lastNanos, timeNanos);
                }
                total += times.size();
            }
        }
        // A whole multiple of the bucket asked for, so buckets stay aligned to the clock, wide enough that
        // the span fits MAX_BUCKETS - 1 of them and the aligned ends add at most one.
        final long widthNanos = total == 0 ? bucketNanos
                : Math.multiplyExact(Math.ceilDiv(Math.ceilDiv(lastNanos - firstNanos + 1, MAX_BUCKETS - 1), bucketNanos),
                        bucketNanos);
        final long originNanos = total == 0 ? 0 : Math.floorDiv(firstNanos, widthNanos) * widthNanos;
        final int buckets = total == 0 ? 0
                : (int) ((Math.floorDiv(lastNanos, widthNanos) * widthNanos - originNanos) / widthNanos + 1);
        final Map<String, Long> leaves = new HashMap<>();
        final List<HotReport.Group> groups = new ArrayList<>(byName.size());
        for (int s = 0, n = byName.slots(); s < n; s++) {
            if (byName.hasKeyAtSlot(s)) {
                groups.add(group(byName.valueAtSlot(s), leaves, originNanos, widthNanos, buckets));
            }
        }
        groups.sort(Comparator.comparingLong(HotReport.Group::samples).reversed()
                .thenComparing(HotReport.Group::name));
        report = new HotReport(info, total, key.description(), widthNanos, originNanos, buckets, List.copyOf(groups),
                ranked(leaves));
    }

    private HotReport.Group group(final Family family, final Map<String, Long> leaves, final long originNanos,
            final long widthNanos, final int buckets) {
        final Map<String, Map<String, Long>> sites = new HashMap<>();
        final ObjLongHashMap<Stack> stacks = family.stacks;
        for (int s = 0, n = stacks.slots(); s < n; s++) {
            if (stacks.hasKeyAtSlot(s)) {
                final Stack stack = stacks.keyAtSlot(s);
                final long samples = stacks.valueAtSlot(s);
                final int at = key.frameIndex(stack);
                final String site = at < 0 ? SiteKey.NO_STACK : stack.frameQuick(at).stableName();
                sites.computeIfAbsent(site, _ -> new HashMap<>()).merge(below(stack, at), samples, Long::sum);
                leaves.merge(at < 0 ? SiteKey.NO_STACK : stack.frameQuick(0).stableName(), samples, Long::sum);
            }
        }
        final List<HotReport.Site> rows = new ArrayList<>(sites.size());
        for (final Map.Entry<String, Map<String, Long>> e : sites.entrySet()) {
            final List<HotReport.Count> next = ranked(e.getValue());
            long samples = 0;
            for (final HotReport.Count c : next) {
                samples += c.samples();
            }
            rows.add(new HotReport.Site(e.getKey(), samples, next));
        }
        rows.sort(Comparator.comparingLong(HotReport.Site::samples).reversed().thenComparing(HotReport.Site::frame));
        final long[] timeline = new long[buckets];
        final LongList times = family.times;
        for (int i = 0, n = times.size(); i < n; i++) {
            timeline[(int) ((times.getQuick(i) - originNanos) / widthNanos)]++;
        }
        final List<Long> perBucket = new ArrayList<>(buckets);
        for (final long count : timeline) {
            perBucket.add(count);
        }
        return new HotReport.Group(family.name, family.threads, times.size(), List.copyOf(rows),
                List.copyOf(perBucket));
    }

    /**
     * What the site's samples were doing below it: the outermost frame under the site that is not
     * JDK code, so a container that only dispatches ({@code paint} calling its children's) is split
     * by the code it dispatched to; else the frame the site called, where everything below it is JDK
     * code ({@code invokeLater}, not the {@code HashMap} inside it, which the leaf table already has);
     * {@link HotReport#AT_SITE} when the site is the leaf.
     */
    static String below(final Stack stack, final int at) {
        if (at <= 0) {
            return HotReport.AT_SITE;
        }
        for (int i = at - 1; i >= 0; i--) {
            if (!stack.frameQuick(i).isJdk()) {
                return stack.frameQuick(i).stableName();
            }
        }
        return stack.frameQuick(at - 1).stableName();
    }

    private static List<HotReport.Count> ranked(final Map<String, Long> counts) {
        final List<HotReport.Count> rows = new ArrayList<>(counts.size());
        for (final Map.Entry<String, Long> e : counts.entrySet()) {
            rows.add(new HotReport.Count(e.getKey(), e.getValue()));
        }
        rows.sort(Comparator.comparingLong(HotReport.Count::samples).reversed().thenComparing(HotReport.Count::frame));
        return List.copyOf(rows);
    }

    /** The answer, after {@link #finish}. */
    public HotReport report() {
        if (report == null) {
            throw new IllegalStateException("finish() has not run");
        }
        return report;
    }

    /** One family's samples while reading: per distinct stack, and each sample's time for the timeline. */
    private static final class Family {

        final String name;
        final ObjLongHashMap<Stack> stacks = new ObjLongHashMap<>(256);
        final LongList times = new LongList(1024);
        int threads;

        Family(final String name) {
            this.name = name;
        }
    }
}
