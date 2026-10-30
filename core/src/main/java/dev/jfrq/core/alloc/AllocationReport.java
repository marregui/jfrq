// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.util.Bytes;

/**
 * Estimated allocation pressure, by thread, class and site, from one recording.
 *
 * <p>Every number is an estimate derived from sampled events (see {@link #source()});
 * the estimates are statistically sound for ranking and for sizeable shares, not for
 * the last few percent.
 *
 * @param info       the recording the report was built from
 * @param source     the event type the estimate is based on, e.g. {@code jdk.ObjectAllocationSample}
 * @param totalBytes estimated bytes allocated over the recording
 * @param samples    number of allocation events in the estimate
 * @param events     number of allocation events seen, including each thread's discarded first sample
 * @param countedByThread per thread name, what the JVM's own counter ({@code jdk.ThreadAllocationStatistics})
 *                   grew by between its first reading, or the thread's start, and its last; a
 *                   thread that lived between two counter events is absent
 * @param byThread   estimated bytes per thread name
 * @param byClass    estimated bytes per allocated class (JVM name)
 * @param bySite     estimated bytes per allocation stack
 * @param classByThread per thread name, bytes per class
 * @param siteByThread  per thread name, bytes per stack
 * @param support    how many samples stand behind each row: a rate built on a handful of
 *                   them is noise, and a percentage printed next to it reads as a finding
 * @param virtualFirsts the first samples of virtual threads left out of the estimate; see {@link #warnings()}
 * @param carrierHistory later samples of virtual threads left out because each outweighs every thread's counted
 *                   allocation in the recording, so its carrier's history reaches back before it; see {@link #warnings()}
 * @param estimatedWhileCounted per name in {@code countedByThread}, the estimate over the same stretch as
 *                   the counter, so the two compare like for like; {@code null} for the whole-file
 *                   estimate of those names, which is what a report built by hand has
 */
public record AllocationReport(
        RecordingInfo info,
        String source,
        long totalBytes,
        long samples,
        long events,
        Map<String, Long> countedByThread,
        Map<String, Long> byThread,
        Map<String, Long> byClass,
        Map<Stack, Long> bySite,
        Map<String, Map<String, Long>> classByThread,
        Map<String, Map<Stack, Long>> siteByThread,
        Support support,
        Dropped virtualFirsts,
        Dropped carrierHistory,
        Map<String, Long> estimatedWhileCounted) {

    /**
     * A thread's row shows its counter only when the stretch the counter covers holds this
     * much of the row's estimate: set beside the whole-file estimate, the counter of a pool
     * thread started after the first reading showed 0 against 212 MB.
     */
    static final double ROW_COVERED = 0.95;

    public AllocationReport {
        if (estimatedWhileCounted == null) {
            final Map<String, Long> whole = new HashMap<>(countedByThread.size() * 2);
            for (final String thread : countedByThread.keySet()) {
                whole.put(thread, byThread.getOrDefault(thread, 0L));
            }
            estimatedWhileCounted = whole;
        }
    }

    /** A report whose estimate over the counters' stretches is the whole file's, as one built by hand. */
    public AllocationReport(final RecordingInfo info, final String source, final long totalBytes, final long samples,
            final long events, final Map<String, Long> countedByThread, final Map<String, Long> byThread,
            final Map<String, Long> byClass, final Map<Stack, Long> bySite,
            final Map<String, Map<String, Long>> classByThread, final Map<String, Map<Stack, Long>> siteByThread,
            final Support support, final Dropped virtualFirsts) {
        this(info, source, totalBytes, samples, events, countedByThread, byThread, byClass, bySite, classByThread,
                siteByThread, support, virtualFirsts, Dropped.NONE, null);
    }

    /** A report with no virtual-thread samples, or from events that have no history to drop. */
    public AllocationReport(final RecordingInfo info, final String source, final long totalBytes, final long samples,
            final long events, final Map<String, Long> countedByThread, final Map<String, Long> byThread,
            final Map<String, Long> byClass, final Map<Stack, Long> bySite,
            final Map<String, Map<String, Long>> classByThread, final Map<String, Map<Stack, Long>> siteByThread,
            final Support support) {
        this(info, source, totalBytes, samples, events, countedByThread, byThread, byClass, bySite, classByThread,
                siteByThread, support, Dropped.NONE, Dropped.NONE, null);
    }

    /**
     * Samples left out of the estimate, and the bytes they weighed.
     *
     * @param samples how many
     * @param bytes   their summed weight, which can include allocation from before the recording
     */
    public record Dropped(long samples, long bytes) {
        public static final Dropped NONE = new Dropped(0, 0);
    }

    /**
     * Samples per key, alongside the bytes. The estimate weights every sample by the bytes
     * it stands for, so two rows of equal size can rest on 2 000 samples and on 3; only the
     * count says which.
     */
    public record Support(Map<String, Long> byThread, Map<String, Long> byClass, Map<Stack, Long> bySite) {
        public static final Support NONE = new Support(Map.of(), Map.of(), Map.of());

        public long thread(final String name) {
            return byThread.getOrDefault(name, 0L);
        }

        public long className(final String name) {
            return byClass.getOrDefault(name, 0L);
        }

        public long site(final Stack stack) {
            return bySite.getOrDefault(stack, 0L);
        }
    }

    /** A ranked row: key, bytes and share of the report total. */
    public record Row<K>(K key, long bytes, double share) {
    }

    /**
     * A ranked site: what the fold called it, the stack that stands for it, and the evidence
     * behind the whole row.
     *
     * @param label   the name the stacks were summed under, e.g. {@code com.example.Parser.parse}
     * @param stack   the biggest single stack in the row, printed under it
     * @param samples the samples behind every stack in the row, not only {@code stack}
     * @param stacks  how many distinct stacks were summed
     */
    public record SiteRow(String label, Stack stack, long bytes, double share, long samples, int stacks) {
    }

    /**
     * What the reader must know before trusting the numbers below, one sentence each.
     *
     * <p>A virtual thread's sample is weighed by its carrier's allocation since the carrier
     * was last sampled, which for a carrier's first sample in the recording reaches back
     * before it. The collector drops every virtual thread's first sample, and every later one
     * heavier than all the allocation the threads' counters saw, so allocation on virtual
     * threads is mostly missing, and the line says how much was left out. It also says the
     * opposite error remains: carrier history under that bound, on a later sample, is kept.
     */
    public List<String> warnings() {
        return virtualFirsts.samples() == 0 && carrierHistory.samples() == 0
                ? List.of() : List.of(virtualWarning(virtualCounts()));
    }

    /**
     * {@code 42 first samples of virtual threads, 40.9 MB}, then {@code and 2 later ones heavier than the
     * recording's counted allocation, 7.60 GB} when there were such, or {@code none} when there were neither.
     */
    String virtualCounts() {
        final long n = virtualFirsts.samples();
        final long h = carrierHistory.samples();
        if (n == 0 && h == 0) {
            return "none";
        }
        final String firsts = n + " first sample" + (n == 1 ? "" : "s") + " of virtual threads, "
                + Bytes.format(virtualFirsts.bytes());
        return h == 0 ? firsts : firsts + ", and " + h + " later one" + (h == 1 ? "" : "s")
                + " heavier than the recording's counted allocation, " + Bytes.format(carrierHistory.bytes());
    }

    /**
     * {@code ; the first sample of each of 103 threads not seen starting in the
     * recording is left out, …}, or empty: why {@link #samples()} is short of {@link #events()} for platform
     * threads. The virtual threads' first samples have a warning of their own.
     */
    public String droppedNote() {
        final long platform = events - samples - virtualFirsts.samples() - carrierHistory.samples();
        if (platform <= 0) {
            return "";
        }
        return "; the first sample of " + (platform == 1 ? "1 thread" : "each of " + platform + " threads")
                + " not seen starting in the recording is left out, as its weight can reach back before it";
    }

    /** The virtual-thread warning around what was left out, said once however many recordings it covers. */
    static String virtualWarning(final String counts) {
        return "allocation on virtual threads is under-counted, and can still be over-counted: " + counts
                + ", not counted, because a sample is weighed by its carrier's allocation since the carrier was last "
                + "sampled, which can reach back before the recording; a virtual thread that moved to a carrier not "
                + "yet sampled carries that history on a later sample, kept when it weighs less than the recording's "
                + "counted allocation (docs/design/alloc.md)";
    }

    public double seconds() {
        return Math.max(info.span().duration(), 1) / 1e9;
    }

    /** Estimated bytes per second over the whole recording. */
    public double rate() {
        return totalBytes / seconds();
    }

    /** Whether the recording carries the JVM's own allocation counters for at least one thread. */
    public boolean hasCounters() {
        return !countedByThread.isEmpty();
    }

    /** The JVM's counters summed over the threads that have one. */
    public long countedBytes() {
        long total = 0;
        for (final long b : countedByThread.values()) {
            total += b;
        }
        return total;
    }

    /**
     * The estimate restricted to the threads that have a counter, and to the stretch each
     * counter covers, so the two compare like for like.
     */
    public long estimatedOnCountedThreads() {
        long total = 0;
        for (final long b : estimatedWhileCounted.values()) {
            total += b;
        }
        return total;
    }

    /**
     * The share of the estimate that the counters can speak for: the estimate on the
     * counted threads over the whole estimate. A thread that started and ended between two
     * counter events has no counter, so in a recording where short-lived pool threads did
     * most of the allocating, {@link #estimateError()} is a statement about a minority of
     * the report and reading it as the error of the whole would be wrong.
     */
    public double countedCoverage() {
        return totalBytes > 0 ? (double) estimatedOnCountedThreads() / totalBytes : 0;
    }

    /**
     * How far the estimate is from the JVM's counters on the threads that have one, as a
     * signed fraction of the counters ({@code 0.07} means the estimate is 7 % high); 0 when
     * nothing was counted.
     */
    public double estimateError() {
        final long counted = countedBytes();
        return counted > 0 ? (double) (estimatedOnCountedThreads() - counted) / counted : 0;
    }

    /**
     * Whether {@link #estimateError()} says anything: the counted threads must carry at
     * least 1 % of the estimate. Below that the two numbers differ by start-up noise (the
     * counters are read a few milliseconds after sampling begins) and a percentage would
     * only alarm. What those threads carry is their estimate, not their counters: a thread
     * whose counter grew by a gigabyte while it was sampled for one megabyte of a
     * 200 MB estimate is half a percent of the report, and the error measured on it is
     * a statement about that half percent.
     */
    public boolean isEstimateErrorMaterial() {
        return countedBytes() > 0 && estimatedOnCountedThreads() >= totalBytes / 100;
    }

    /**
     * The JVM's counter for a thread's row, when the stretch it covers holds nearly all the
     * row's estimate ({@link #ROW_COVERED}); otherwise the two would not be about the same bytes.
     */
    public Optional<Long> counted(final String thread) {
        final Long counted = countedByThread.get(thread);
        final long whole = byThread.getOrDefault(thread, 0L);
        return counted == null || estimatedWhileCounted.getOrDefault(thread, 0L) < ROW_COVERED * whole
                ? Optional.empty() : Optional.of(counted);
    }

    public double rate(final long bytes) {
        return bytes / seconds();
    }

    public List<Row<String>> threads(final int top) {
        return rank(byThread, top);
    }

    public List<Row<String>> classes(final int top) {
        return rank(byClass, top);
    }

    /** Sites ranked one stack at a time, which is what {@link AllocationDiff} matches on. */
    public List<Row<Stack>> sitesByStack(final int top) {
        return rank(bySite, top);
    }

    /**
     * Allocation sites ranked by bytes, every stack that {@code key} names the same summed
     * into one row. A logical site reaches the sampler down many paths, and one row per path
     * turns a fifth of the heap into a dozen rows of two percent.
     *
     * <p>The samples are summed with the bytes. A row whose bytes are the sum of ten stacks
     * and whose support is one of them says the most important row in the report rests on
     * three samples when it rests on thousands.
     */
    public List<SiteRow> sites(final SiteKey key, final int top) {
        final List<SiteRow> rows = new ArrayList<>(fold(key).values());
        rows.sort(Comparator.comparingLong(SiteRow::bytes).reversed().thenComparing(SiteRow::label));
        return rows.size() > top ? List.copyOf(rows.subList(0, top)) : List.copyOf(rows);
    }

    /**
     * The same fold unranked, by label, so {@link AllocationDiff} can compare two reports
     * row for row without either of them being ranked first.
     */
    Map<String, SiteRow> fold(final SiteKey key) {
        final Map<String, Fold> folds = new HashMap<>();
        for (final Map.Entry<Stack, Long> e : bySite.entrySet()) {
            folds.computeIfAbsent(key.of(e.getKey()), _ -> new Fold())
                    .add(e.getKey(), e.getValue(), support.site(e.getKey()));
        }
        final double total = Math.max(totalBytes, 1);
        final Map<String, SiteRow> rows = new LinkedHashMap<>(folds.size());
        folds.forEach((label, f) -> rows.put(label,
                new SiteRow(label, f.best, f.bytes, f.bytes / total, f.samples, f.stacks)));
        return rows;
    }

    /**
     * The non-JDK package roots the allocation came from, by bytes: the first two segments of
     * each culprit's class, which is the form {@code --app} takes. A reader who has never
     * seen the application before learns from its own report what to point the option at.
     */
    public List<Row<String>> packageRoots(final int top) {
        final Map<String, Long> bytes = new HashMap<>();
        for (final Map.Entry<Stack, Long> e : bySite.entrySet()) {
            final Frame culprit = e.getKey().culpritOrNull();
            if (culprit != null && !culprit.isJdk()) {
                bytes.merge(root(culprit.type()), e.getValue(), Long::sum);
            }
        }
        return rank(bytes, top);
    }

    private static String root(final String type) {
        final int first = type.indexOf('.');
        if (first < 0) {
            return "(default package)";
        }
        final int second = type.indexOf('.', first + 1);
        return second < 0 ? type.substring(0, first) : type.substring(0, second);
    }

    public List<Row<String>> classesOf(final String thread, final int top) {
        return rank(classByThread.getOrDefault(thread, Map.of()), top);
    }

    public List<Row<Stack>> sitesOf(final String thread, final int top) {
        return rank(siteByThread.getOrDefault(thread, Map.of()), top);
    }

    /** Ranks a map by value; shares are relative to the report's total, not the map's. */
    <K> List<Row<K>> rank(final Map<K, Long> map, final int top) {
        final List<Row<K>> rows = new ArrayList<>(map.size());
        final double total = Math.max(totalBytes, 1);
        for (final Map.Entry<K, Long> e : map.entrySet()) {
            rows.add(new Row<>(e.getKey(), e.getValue(), e.getValue() / total));
        }
        rows.sort(Comparator.<Row<K>>comparingLong(Row::bytes).reversed());
        return rows.size() > top ? List.copyOf(rows.subList(0, top)) : List.copyOf(rows);
    }

    /** One row while the sites are being folded: its totals and its biggest single stack. */
    private static final class Fold {

        private Stack best;
        private long bestBytes = -1;
        private long bytes;
        private long samples;
        private int stacks;

        void add(final Stack stack, final long stackBytes, final long stackSamples) {
            bytes += stackBytes;
            samples += stackSamples;
            stacks++;
            // The stack printed under a row is its biggest contributor, so the lines a reader
            // sees are the ones most of the row's bytes came through.
            if (stackBytes > bestBytes) {
                bestBytes = stackBytes;
                best = stack;
            }
        }
    }
}
