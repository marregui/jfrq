// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.procs;

import java.util.List;

import dev.jfrq.core.jfr.RecordingInfo;

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

    /** Threads and sites listed under each command, and commands beside each family, by text and HTML. */
    public static final int SHOWN = 3;

    /** What a command line with no first word is called. */
    public static final String EMPTY = "(empty)";

    /** {@code count} starts as a rate over the recording's span, per minute; 0 for a span of zero. */
    public double perMinute(final long count) {
        final long spanNanos = info.span().duration();
        return spanNanos == 0 ? 0 : count * 60_000_000_000.0 / spanNanos;
    }

    /**
     * {@code folder-poll 400, kit-jobs 21, 2 more 3}: the first {@link #SHOWN} of {@code counts} and the
     * rest summed, as text and HTML print the threads, sites and commands of a row.
     */
    public static String listed(final List<Count> counts) {
        final StringBuilder sb = new StringBuilder();
        long rest = 0;
        for (int i = 0; i < counts.size(); i++) {
            final Count c = counts.get(i);
            if (i < SHOWN) {
                sb.append(i > 0 ? ", " : "").append(c.name()).append(' ').append(c.starts());
            } else {
                rest += c.starts();
            }
        }
        if (rest > 0) {
            sb.append(", ").append(counts.size() - SHOWN).append(" more ").append(rest);
        }
        return sb.toString();
    }

    /**
     * One command: the basename of the first word of its command line.
     *
     * @param command the name, {@code git}, or {@link #EMPTY}
     * @param starts  how many times it was started
     * @param example the earliest command line under this name, whole
     * @param threads its starts by thread family
     * @param sites   its starts by the frame the site key names in the starting stack
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

    /** A name, a family, a command or a frame, and how many starts it accounts for. */
    public record Count(String name, long starts) {
    }
}
