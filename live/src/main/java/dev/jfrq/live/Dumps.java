// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;

import dev.jfrq.cli.Args;
import dev.jfrq.core.jfr.Chunks;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.Durations;

/**
 * The steps a dump and a class histogram take, shared by the one-shot commands of
 * {@link Live} and by {@link Watch}: which recording, where the cursors are, the dump with
 * its cursor move, the span check, and the histogram written and counted.
 */
final class Dumps {

    /**
     * Every time jfrq-live prints is UTC and says so, as jfrq's own header does: a dump is read
     * on other machines and next to other tools, and a clock time without a zone is a guess.
     */
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.ROOT).withZone(ZoneOffset.UTC);
    /**
     * How long the JVM has to answer a class histogram: a full GC of a large heap takes seconds.
     * Measured: the GC before a histogram paused 14.9 to 26.2 ms on 57 to 126 MB of heap in use
     * (docs/LIVE.md section 7); two minutes is unmeasured headroom for heaps a thousand times larger.
     */
    static final Duration HISTO_TIMEOUT = Duration.ofMinutes(2);
    /** Chunk boundaries are why a file and its window differ by a little; more than this is worth a line. */
    private static final Duration SLACK = Duration.ofSeconds(1);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private Dumps() {
    }

    /** {@code 14:03:07.121Z .. 14:05:12.004Z}, with "start" and "now" for the open ends. */
    static String describe(final Window w) {
        return (w.begin() == null ? "the start" : TIME.format(w.begin())) + " .. "
                + (w.end() == null ? "now" : TIME.format(w.end()));
    }

    static Path stateDir(final Args args) {
        return args.option("state").map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".jfrq", "live"));
    }

    /**
     * The recording to act on: {@code --recording} by id, or by a name only one recording
     * has, else the JVM's single running one.
     */
    static RecordingInfo pick(final Jvm jvm, final Args args) throws IOException {
        final List<RecordingInfo> all = jvm.flightRecorder().getRecordings();
        final Optional<String> wanted = args.option("recording");
        if (wanted.isPresent()) {
            final String w = wanted.get();
            final List<RecordingInfo> named = new ArrayList<>();
            for (final RecordingInfo r : all) {
                if (w.equals(Long.toString(r.getId()))) {
                    return r;
                }
                if (w.equals(r.getName())) {
                    named.add(r);
                }
            }
            if (named.size() == 1) {
                return named.getFirst();
            }
            if (named.size() > 1) {
                final StringBuilder sb = new StringBuilder("--recording '" + w + "' names " + named.size()
                        + " recordings; pick one by id:");
                for (final RecordingInfo r : named) {
                    sb.append(' ').append(r.getId()).append(" (").append(r.getState()).append(')');
                }
                throw new Args.UsageException(sb.toString());
            }
            throw new IOException("JVM " + jvm.pid() + " has no recording '" + w + "'; 'jfrq-live " + jvm.pid()
                    + " status' lists them");
        }
        final List<RecordingInfo> running = new ArrayList<>();
        for (final RecordingInfo r : all) {
            if ("RUNNING".equals(r.getState())) {
                running.add(r);
            }
        }
        if (running.isEmpty()) {
            throw new IOException("JVM " + jvm.pid() + " has no running recording: start one with 'jfrq-live "
                    + jvm.pid() + " start', or run the JVM with -XX:StartFlightRecording");
        }
        if (running.size() > 1) {
            final StringBuilder sb = new StringBuilder("JVM " + jvm.pid() + " has " + running.size()
                    + " running recordings; pick one with --recording ID|NAME:");
            for (final RecordingInfo r : running) {
                sb.append(' ').append(r.getId()).append('=').append(r.getName());
            }
            throw new IOException(sb.toString());
        }
        return running.getFirst();
    }

    /**
     * A dump, the chunk headers of its file, the window it was asked for and the cursor after it.
     */
    record Taken(Snapshot snapshot, Chunks chunks, Window window, Cursor cursor) {
    }

    /**
     * Takes one {@code full}, {@code delta} or {@code again} dump of recording {@code r} into
     * {@code file} and moves the cursor kept in {@code stateDir}: what {@code jfrq-live <pid> delta}
     * does before its question, and what each window of {@code watch} is.
     */
    static Taken take(final Jvm jvm, final String command, final RecordingInfo r, final Path stateDir, final Path file,
            final boolean replace, final PrintStream err) throws IOException {
        final FlightRecorderMXBean fr = jvm.flightRecorder();
        final Cursor cursor;
        final Window window;
        final Snapshot snapshot;
        final Chunks chunks;
        // The lock spans reading the cursor to advancing it: two deltas in a row, not two at once.
        try (final Closeable _ = Cursor.lock(stateDir, jvm.pid(), err)) {
            cursor = Cursor.load(stateDir, jvm.pid(), jvm.startTime());
            window = switch (command) {
                case "full" -> Window.EVERYTHING;
                case "delta" -> {
                    if (cursor.next() == null) {
                        throw new Args.UsageException("no cursor for JVM " + jvm.pid() + " yet: run 'full' first");
                    }
                    yield new Window(cursor.next(), null);
                }
                default -> {
                    if (cursor.lastWindow() == null) {
                        throw new Args.UsageException("no window to repeat for JVM " + jvm.pid()
                                + ": run 'full' or 'delta' first");
                    }
                    yield cursor.lastWindow();
                }
            };
            snapshot = Snapshot.take(fr, jvm.pid(), r.getId(), window, file, replace);
            chunks = Chunks.scan(snapshot.file());
            if (!command.equals("again")) {
                // A full dump's window starts where its file does, so an 'again' of it can tell
                // when the JVM has since discarded part of it.
                final Window taken = window.begin() != null ? window
                        : new Window(Instant.ofEpochSecond(0, chunks.startNanos()), null);
                cursor.advance(taken, snapshot.stop());
            }
        }
        return new Taken(snapshot, chunks, window, cursor);
    }

    /**
     * The span check: what the file holds against what was asked, and why they differ when
     * they do. The chunk headers say it all, so the dump is not parsed.
     */
    static void check(final Snapshot snapshot, final Chunks chunks, final Window window, final String command,
            final RecordingInfo r, final Jvm jvm, final PrintStream to) throws IOException {
        if (chunks.complete() == 0) {
            throw new IOException("the dump " + snapshot.file() + " holds no complete chunk");
        }
        final Instant start = Instant.ofEpochSecond(0, chunks.startNanos());
        final Instant end = Instant.ofEpochSecond(0, chunks.endNanos());
        Live.line(to, String.format(Locale.ROOT, "Dumped     %s  %s, %d chunk%s, %s .. %s (%s)", snapshot.file(),
                Bytes.format(snapshot.bytes()), chunks.count(), chunks.count() == 1 ? "" : "s", TIME.format(start),
                TIME.format(end), Durations.format(chunks.endNanos() - chunks.startNanos())));
        if (chunks.isTruncated()) {
            Live.line(to, "WARNING    the file is truncated: it ends inside a chunk; " + chunks.complete()
                    + " complete chunk(s) before it");
        }
        if (chunks.isInProgress()) {
            Live.line(to, "WARNING    the file's last chunk is not finished; jfrq will refuse it");
        }
        final String why = switch (command) {
            case "full" -> "everything the recording kept";
            case "delta" -> "since the previous dump";
            default -> "the previous window again";
        };
        Live.line(to, String.format(Locale.ROOT, "Window     %s (%s)", describe(window), why));
        for (final String note : span(window, start, end, r.getStartTime(), bounds(r))) {
            Live.line(to, note);
        }
        if (command.equals("full") && r.getMaxAge() == 0 && r.getMaxSize() == 0) {
            Live.line(to, unboundedWarning(jvm));
        }
    }

    /**
     * How the file's span {@code [start, end]} departs from {@code window} by more than chunk
     * granularity, and why. A file that starts late lost the window's beginning: to the
     * recording's bounds, or, when the recording itself started after the window began (it
     * was stopped and started again since the cursor was set), to never having recorded it.
     */
    static List<String> span(final Window window, final Instant start, final Instant end,
            final long recordingStartMillis, final String bounds) {
        final List<String> lines = new ArrayList<>();
        if (window.begin() != null) {
            final Duration early = Duration.between(start, window.begin());
            if (early.compareTo(SLACK) > 0) {
                lines.add("Note       the file starts " + Durations.format(early)
                        + " before the window: the JVM hands over whole chunks");
            } else if (early.negated().compareTo(SLACK) > 0) {
                final Instant recordingStart = Instant.ofEpochMilli(recordingStartMillis);
                if (recordingStartMillis != 0 && recordingStart.isAfter(window.begin())) {
                    lines.add("WARNING    the file starts " + Durations.format(early.negated())
                            + " after the window: the recording started at " + TIME.format(recordingStart)
                            + ", after the window began, so it never held that data (stopped and started again?)");
                } else {
                    lines.add("WARNING    the file starts " + Durations.format(early.negated())
                            + " after the window: the JVM had already discarded that data (" + bounds + ")");
                }
            }
        }
        if (window.end() != null) {
            final Duration late = Duration.between(window.end(), end);
            if (late.compareTo(SLACK) > 0) {
                lines.add("Note       the file ends " + Durations.format(late)
                        + " after the window: the JVM hands over whole chunks");
            }
        }
        return lines;
    }

    /**
     * Fetches the JVM's class histogram (a full GC in the JVM first), writes it to {@code file}
     * and says so on {@code out} with its counts: what {@code histo --out} and each histogram of
     * {@code watch} do.
     *
     * @throws IOException the JVM did not answer within {@code timeout}, refused, or the file could not be written
     */
    static Histogram histogram(final Jvm jvm, final Duration timeout, final Path file, final PrintStream out)
            throws IOException {
        final String text = jvm.classHistogram(timeout);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        final Histogram h = Histogram.parse(text, file.toString());
        long instances = 0;
        long bytes = 0;
        for (final long[] counts : h.classes().values()) {
            instances += counts[0];
            bytes += counts[1];
        }
        Live.line(out, String.format(Locale.ROOT, "Histogram  %s  %,d classes, %,d instances, %s, counted after a "
                + "full GC", file, h.classes().size(), instances, Bytes.format(bytes)));
        return h;
    }

    static String bounds(final RecordingInfo r) {
        if (r.getMaxAge() == 0 && r.getMaxSize() == 0) {
            return "no bound";
        }
        final StringBuilder sb = new StringBuilder();
        if (r.getMaxAge() != 0) {
            sb.append("max-age ").append(Durations.format(Duration.ofSeconds(r.getMaxAge())));
        }
        if (r.getMaxSize() != 0) {
            sb.append(sb.isEmpty() ? "" : ", ").append("max-size ").append(Bytes.format(r.getMaxSize()));
        }
        return sb.toString();
    }

    static String unboundedWarning(final Jvm jvm) {
        return "WARNING    the recording has no bound: the JVM keeps every chunk and each 'full' dump is bigger"
                + " than the last; 'jfrq-live " + jvm.pid() + " bound --max-age 10m' keeps the recent ten minutes";
    }
}
