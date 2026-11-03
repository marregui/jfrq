// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.procs;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;

import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.util.Count;

/**
 * Which processes the JVM started, from {@code jdk.ProcessStart}: every list ranked by starts,
 * most first, then by name ({@code docs/design/procs.md}).
 *
 * @param info     the recording
 * @param starts   every process start read
 * @param siteKey  what a site is, in the words the report prints ({@code SiteKey.description})
 * @param commands the starts by command
 * @param families the starts by the family of the thread that made them
 */
public record ProcessReport(RecordingInfo info, long starts, String siteKey, List<Command> commands,
        List<Family> families) {

    /**
     * Threads and sites listed under each command, and commands beside each family, by text and HTML
     * ({@code Listing.listed}); the rest are summed as one entry.
     */
    public static final int SHOWN = 3;

    /** What a command line with no first word is called. */
    public static final String EMPTY = "(empty)";

    /** {@code count} starts as a rate over the recording's span, per minute; 0 for a span of zero. */
    public double perMinute(final long count) {
        final long spanNanos = info.span().duration();
        return spanNanos == 0 ? 0 : count * 60_000_000_000.0 / spanNanos;
    }

    /**
     * {@link #perMinute} as text and HTML print it: one decimal from 1 up ({@code 12.4}), two
     * significant digits under 1 ({@code 0.31}, {@code 0.012}), so a command started a few times
     * in a long recording does not read {@code 0.0}.
     */
    public String perMinuteText(final long count) {
        final double v = perMinute(count);
        return v == 0 || v >= 1 ? String.format(Locale.ROOT, "%.1f", v)
                : new BigDecimal(v).round(new MathContext(2)).toPlainString();
    }

    /**
     * One command: the basename of the first word of its command line.
     *
     * @param command the name, {@code git}, or {@link #EMPTY}
     * @param starts  how many times it was started
     * @param example the earliest command line under this name, whole
     * @param threads its starts by thread family, a count each
     * @param sites   its starts by the frame the site key names in the starting stack, a count each
     */
    public record Command(String command, long starts, String example, List<Count> threads, List<Count> sites) {
    }

    /**
     * One thread family ({@code RecordingSummary.family}) and what it started.
     *
     * @param name     the family
     * @param threads  how many of its threads started a process
     * @param starts   its starts
     * @param commands its starts by command
     */
    public record Family(String name, int threads, long starts, List<Count> commands) {
    }
}
