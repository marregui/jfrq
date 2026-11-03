// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jfrq.core.util.Bytes;

/**
 * A class histogram as {@code jcmd <pid> GC.class_histogram} prints it, and as the
 * {@code DiagnosticCommand} MBean's {@code gcClassHistogram} returns it: a row per class,
 * {@code num: #instances #bytes class name (module)}, then a {@code Total} row. Rows are
 * keyed by the class name without its module; two rows of one name (a class loaded by two
 * loaders) are added together, since a histogram cannot tell them apart across files anyway.
 *
 * @param classes per class name, {@code {instances, bytes}}
 */
record Histogram(Map<String, long[]> classes) {

    /** {@code   12:   4607   405416  java.lang.reflect.Method (java.base@25)}: the module is optional. */
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*$");

    /**
     * The rows of {@code text}; the header, the pid line {@code jcmd} prints first and the total are skipped.
     *
     * @param source names the text in the error, a file or the JVM
     * @throws IOException when a row's count does not fit a {@code long}, naming {@code source} and the line
     */
    static Histogram parse(final String text, final String source) throws IOException {
        final Map<String, long[]> classes = new HashMap<>();
        int lineNo = 0;
        for (final String line : text.split("\n", -1)) {
            lineNo++;
            final Matcher m = ROW.matcher(line);
            if (m.matches()) {
                final long[] counts = classes.computeIfAbsent(m.group(3), k -> new long[2]);
                try {
                    counts[0] += Long.parseLong(m.group(1));
                    counts[1] += Long.parseLong(m.group(2));
                } catch (final NumberFormatException e) {
                    throw new IOException(source + " line " + lineNo + ": a count does not fit 64 bits: "
                            + line.strip(), e);
                }
            }
        }
        return new Histogram(classes);
    }

    /** @throws IOException when the file cannot be read, holds no histogram row, or a row does not parse */
    static Histogram read(final Path file) throws IOException {
        final Histogram h = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        if (h.classes().isEmpty()) {
            throw new IOException(file + " holds no class histogram rows (num: #instances #bytes class name)");
        }
        return h;
    }

    long instances(final String name) {
        final long[] counts = classes.get(name);
        return counts == null ? 0 : counts[0];
    }

    long bytes(final String name) {
        final long[] counts = classes.get(name);
        return counts == null ? 0 : counts[1];
    }

    /**
     * A class whose bytes grew between every consecutive pair of histograms.
     *
     * @param instances per histogram, in the order given; 0 where the class was absent
     * @param bytes     the same for bytes
     */
    record Grower(String name, long[] instances, long[] bytes) {

        long byteGrowth() {
            return bytes[bytes.length - 1] - bytes[0];
        }

        long instanceGrowth() {
            return instances[instances.length - 1] - instances[0];
        }

        /** Whether the instances grew, strictly, at every step too. */
        boolean instancesGrew() {
            for (int i = 1; i < instances.length; i++) {
                if (instances[i] <= instances[i - 1]) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * The classes that grew, strictly, in instances and in bytes from each histogram to the
     * next, ranked by byte growth from the first to the last, then by name. A class absent
     * from a histogram counts as 0 there. Taken after a full GC each (what
     * {@code GC.class_histogram} does), growth at every step is what is still reachable,
     * not garbage waiting for a collection.
     */
    static List<Grower> growers(final List<Histogram> histograms) {
        final List<Grower> growers = new ArrayList<>();
        for (final Grower g : byteGrowth(histograms)) {
            if (g.instancesGrew()) {
                growers.add(g);
            }
        }
        return growers;
    }

    /**
     * The classes whose bytes grew, strictly, at every step while their instances did not:
     * fewer or as many objects taking more room, such as arrays or buffers that were replaced
     * by larger ones. Ranked as {@link #growers} are.
     */
    static List<Grower> bytesOnly(final List<Histogram> histograms) {
        final List<Grower> bytesOnly = new ArrayList<>();
        for (final Grower g : byteGrowth(histograms)) {
            if (!g.instancesGrew()) {
                bytesOnly.add(g);
            }
        }
        return bytesOnly;
    }

    /**
     * The classes the GC fills unused heap with ({@code CollectedHeap::fill_with_object} in
     * HotSpot): not application objects, and their bytes move with the GC's work, not the
     * program's. A quiet watch saw {@code [Ljdk.internal.vm.FillerElement;} as its top grower.
     */
    static final Set<String> GC_FILLERS = Set.of("[Ljdk.internal.vm.FillerElement;", "jdk.internal.vm.FillerObject");

    /** Every class whose bytes grew at every step, ranked by byte growth first to last, then by name. */
    private static List<Grower> byteGrowth(final List<Histogram> histograms) {
        if (histograms.size() < 2) {
            throw new IllegalArgumentException("growth needs two histograms or more, not " + histograms.size());
        }
        final Set<String> names = new LinkedHashSet<>();
        for (final Histogram h : histograms) {
            names.addAll(h.classes().keySet());
        }
        final List<Grower> grew = new ArrayList<>();
        final int n = histograms.size();
        names.removeAll(GC_FILLERS);
        for (final String name : names) {
            final long[] instances = new long[n];
            final long[] bytes = new long[n];
            boolean up = true;
            for (int i = 0; i < n && up; i++) {
                instances[i] = histograms.get(i).instances(name);
                bytes[i] = histograms.get(i).bytes(name);
                up = i == 0 || bytes[i] > bytes[i - 1];
            }
            if (up) {
                grew.add(new Grower(name, instances, bytes));
            }
        }
        grew.sort(Comparator.comparingLong(Grower::byteGrowth).reversed().thenComparing(Grower::name));
        return grew;
    }

    /**
     * The growth tables: the classes that grew in instances and bytes, then those that grew in
     * bytes only; a line per class with its growth first to last, then its instances and bytes
     * in each histogram, numbered as {@code labels} are. {@code top} rows per table.
     */
    static String table(final List<String> labels, final List<Histogram> histograms, final int top) {
        final List<Grower> growers = growers(histograms);
        final List<Grower> bytesOnly = bytesOnly(histograms);
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Growth     %d histograms, after a full GC each:\n", labels.size()));
        for (int i = 0; i < labels.size(); i++) {
            sb.append(String.format(Locale.ROOT, "  %2d  %s\n", i + 1, labels.get(i)));
        }
        final Set<String> all = new LinkedHashSet<>();
        for (final Histogram h : histograms) {
            all.addAll(h.classes().keySet());
        }
        sb.append(String.format(Locale.ROOT, "Growers    %,d of %,d classes grew in instances and bytes from each "
                + "histogram to the next%s\n", growers.size(), all.size(),
                growers.size() > top ? " (top " + top + " by bytes)" : ""));
        rows(sb, growers, top);
        for (final String filler : GC_FILLERS) {
            if (all.contains(filler)) {
                sb.append("Left out   ").append(filler).append(": the GC's heap filler, not application objects\n");
            }
        }
        sb.append("\nBYTES GREW AT EVERY STEP (instances did not): larger arrays or buffers, not more objects\n");
        sb.append(String.format(Locale.ROOT, "Bytes only %,d of %,d classes%s\n", bytesOnly.size(), all.size(),
                bytesOnly.size() > top ? " (top " + top + " by bytes)" : ""));
        rows(sb, bytesOnly, top);
        return sb.toString();
    }

    private static void rows(final StringBuilder sb, final List<Grower> growers, final int top) {
        if (growers.isEmpty()) {
            return;
        }
        sb.append(String.format(Locale.ROOT, "\n  %10s %12s  %s\n", "+bytes", "+instances", "class"));
        for (final Grower g : growers.subList(0, Math.min(top, growers.size()))) {
            sb.append(String.format(Locale.ROOT, "  %10s %+,12d  %s\n", Bytes.signed(g.byteGrowth()),
                    g.instanceGrowth(), g.name()));
            sb.append(String.format(Locale.ROOT, "  %23s  instances %s\n", "", series(g.instances(), false)));
            sb.append(String.format(Locale.ROOT, "  %23s  bytes     %s\n", "", series(g.bytes(), true)));
        }
    }

    private static String series(final long[] values, final boolean bytes) {
        final StringBuilder sb = new StringBuilder();
        for (final long v : values) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(bytes ? Bytes.format(v) : String.format(Locale.ROOT, "%,d", v));
        }
        return sb.toString();
    }
}
