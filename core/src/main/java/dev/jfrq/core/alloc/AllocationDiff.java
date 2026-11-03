// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jfrq.core.model.SiteKey;
import dev.jfrq.core.model.Stack;

/**
 * Compares two allocation reports, a baseline and a candidate, by <em>rate</em>
 * (bytes per second) rather than by total, so that recordings of different lengths are
 * comparable. Threads are matched by name, classes by name, and sites either by full stack
 * ({@link #sitesByStack(int)}) or by the fold a reader is shown ({@link #sites(SiteKey, int)}); a
 * key present on one side only is reported against zero on the other.
 *
 * @param baseline the "before" report
 * @param current  the "after" report
 */
public record AllocationDiff(AllocationReport baseline, AllocationReport current) {

    /**
     * One compared key.
     *
     * @param key         thread name, class name or stack
     * @param beforeRate  bytes per second in the baseline
     * @param afterRate   bytes per second in the current report
     */
    public record Delta<K>(K key, double beforeRate, double afterRate) {
        public double delta() {
            return afterRate - beforeRate;
        }

        /** Relative change, or {@code +Infinity} when the baseline had nothing. */
        public double ratio() {
            if (beforeRate == 0) {
                return afterRate == 0 ? 0 : Double.POSITIVE_INFINITY;
            }
            return delta() / beforeRate;
        }
    }

    /**
     * A site as it is compared: the name both sides were folded under, a stack that stands
     * for it, and the samples behind each side. A change of several hundred percent on a
     * handful of samples is noise, and only the counts say so.
     */
    public record Site(String label, Stack stack, long beforeSamples, long afterSamples) {
    }

    /**
     * What the reader must know before trusting the comparison: each recording's
     * {@link AllocationReport#warnings()}, a warning both carry said once with both counts.
     */
    public List<String> warnings() {
        if (baseline.warnings().isEmpty() && current.warnings().isEmpty()) {
            return List.of();
        }
        return List.of(AllocationReport.virtualWarning("baseline " + baseline.virtualCounts() + "; current "
                + current.virtualCounts()));
    }

    public Delta<String> total() {
        return new Delta<>("total", baseline.rate(), current.rate());
    }

    public List<Delta<String>> threads(final int top) {
        return compare(baseline.byThread(), current.byThread(), top);
    }

    public List<Delta<String>> classes(final int top) {
        return compare(baseline.byClass(), current.byClass(), top);
    }

    /** Sites compared one stack at a time; {@link #sites(SiteKey, int)} is what a reader is shown. */
    public List<Delta<Stack>> sitesByStack(final int top) {
        return compare(baseline.bySite(), current.bySite(), top);
    }

    /**
     * Sites compared after each side is folded by {@code key}, which is what a reader is
     * shown. Matched per stack, one site moving appears once per path it was sampled down:
     * a diff of two loaded windows opened with the same six frames twice, at 302 MB/s and
     * 216 MB/s, and neither number was the change. Folded, the row is the site and its rate
     * is the site's.
     */
    public List<Delta<Site>> sites(final SiteKey key, final int top) {
        final Map<String, AllocationReport.SiteRow> before = baseline.fold(key);
        final Map<String, AllocationReport.SiteRow> after = current.fold(key);
        final Set<String> labels = new LinkedHashSet<>(after.keySet());
        labels.addAll(before.keySet());
        final List<Delta<Site>> deltas = new ArrayList<>(labels.size());
        for (final String label : labels) {
            final AllocationReport.SiteRow b = before.get(label);
            final AllocationReport.SiteRow a = after.get(label);
            // The stack under the row comes from the side that still has the site.
            final Site site = new Site(label, a != null ? a.stack() : b.stack(),
                    b == null ? 0 : b.samples(), a == null ? 0 : a.samples());
            deltas.add(new Delta<>(site, b == null ? 0 : baseline.rate(b.bytes()),
                    a == null ? 0 : current.rate(a.bytes())));
        }
        deltas.sort(Comparator.<Delta<Site>>comparingDouble(d -> Math.abs(d.delta())).reversed()
                .thenComparing(d -> d.key().label()));
        return limit(deltas, top);
    }

    /** The first {@code top} of an already ordered list, copied so the result is immutable. */
    private static <K> List<Delta<K>> limit(final List<Delta<K>> deltas, final int top) {
        return deltas.size() > top ? List.copyOf(deltas.subList(0, top)) : List.copyOf(deltas);
    }

    /** Sorted by absolute rate change, largest first. */
    <K> List<Delta<K>> compare(final Map<K, Long> before, final Map<K, Long> after, final int top) {
        final Set<K> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        final List<Delta<K>> deltas = new ArrayList<>(keys.size());
        for (final K k : keys) {
            final double b = baseline.rate(before.getOrDefault(k, 0L));
            final double a = current.rate(after.getOrDefault(k, 0L));
            deltas.add(new Delta<>(k, b, a));
        }
        deltas.sort(Comparator.<Delta<K>>comparingDouble(d -> Math.abs(d.delta())).reversed());
        return limit(deltas, top);
    }
}
