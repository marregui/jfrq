// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serial;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import jdk.management.jfr.RecordingInfo;

import dev.jfrq.cli.Args;
import dev.jfrq.cli.Main;
import dev.jfrq.core.util.Bytes;
import dev.jfrq.core.util.Durations;

/**
 * {@code jfrq-live <pid> watch}: one process that follows a JVM for a set time over one JMX
 * connection. Every {@code --every} it takes a window, the {@code delta} dump since the
 * previous one ({@code full} when the JVM has no cursor yet), and runs each {@code --ask}
 * question on it; every {@code --sample} it reads the JVM's own MBeans into
 * {@code samples.tsv}; with {@code --histo} it takes a class histogram at the start and after
 * each window. It ends by itself after {@code --for}, at once when the JVM exits, or on
 * Ctrl-C, and always with the summary table, on standard output and in {@code summary.txt}.
 *
 * <p>Every call over the connection has a deadline, and none reaches past {@code --for} plus
 * {@link #GRACE}: a JVM that stops answering cannot keep the watch beyond that. A connection
 * that does not answer is opened again once; a second time ends the watch with status 1. A
 * window that fails while the connection is good is a line in the summary, and the next
 * window's delta covers its time; when the recording itself is gone or no longer running, no
 * later window can succeed and the watch ends with status 1. Window slots missed while the
 * watch was held up (a sleeping host, a slow call) are skipped, not taken back to back: the
 * next window's delta covers their time. A watch that ends with a failed window exits 1.
 */
final class Watch {

    /** How long the JVM has to answer one sample or the connection check. */
    static final Duration SAMPLE_TIMEOUT = Duration.ofSeconds(10);
    /** How long the JVM has to answer a class histogram: a full GC of a large heap takes seconds. */
    static final Duration HISTO_TIMEOUT = Duration.ofMinutes(2);
    /** How long past {@code --for} a call in flight may wait for the JVM to answer. */
    static final Duration GRACE = Duration.ofSeconds(30);
    /** How long the JVM has to answer the close of the connection. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);
    /** Why a call failed when the JVM was gone by the time it was looked at. */
    private static final String JVM_EXITED = "the JVM exited";
    /** How long Ctrl-C waits for the window in progress before the summary is written without it. */
    private static final long STOP_WAIT_MILLIS = 10_000;
    /** How often the wait between events checks that the JVM is still there. */
    private static final long ALIVE_CHECK_MILLIS = 500;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    static final String TSV_HEADER = "time_utc\tprocess_cpu_pct\tsystem_cpu_pct\theap_used_bytes\theap_committed_bytes"
            + "\tthreads\topen_fds\tcommitted_virtual_bytes";

    /** What a watch does, all checked before the JVM is attached to. */
    record Plan(String pid, Args args, long everyNanos, long forNanos, long sampleNanos, Path dir, boolean histo,
            List<String[]> asks) {

        int windows() {
            return (int) Math.max(1, (forNanos + everyNanos - 1) / everyNanos);
        }
    }

    /** One window as the summary shows it; {@code bytes} is -1 when the dump failed and {@code failure} says why. */
    private record Row(int index, Instant begin, Instant end, String base, long bytes, long dumpNanos, long tookNanos,
            List<String> answers, String failure) {
    }

    /** One sample line, kept for the summary's ranges; {@code NaN} or -1 where the JVM did not say. */
    private record Point(int window, double processCpuPct, long heapUsedBytes, long threads, long openFds) {
    }

    private final Plan plan;
    private final PrintStream out;
    private final PrintStream err;
    private final Path stateDir;
    private final List<Row> rows = new ArrayList<>();
    private final List<Point> points = new ArrayList<>();
    private final List<Path> histograms = new ArrayList<>();
    /** What the summary must say beyond its table: reconnects, skipped slots, failed histograms. */
    private final List<String> notes = new ArrayList<>();
    private Jvm jvm;
    private RecordingInfo recording;
    private boolean reconnected;
    private BufferedWriter tsv;
    private Jvm.Sample previous;
    private boolean finished;
    /** Set by the shutdown hook, read by the main thread's loop. */
    private volatile boolean stopping;
    /** Counted down by the main thread when its loop is over, awaited by the shutdown hook. */
    private final CountDownLatch looped = new CountDownLatch(1);
    private long startNanos;
    private long endNanos;

    Watch(final Plan plan, final PrintStream out, final PrintStream err) {
        this.plan = plan;
        this.out = out;
        this.err = err;
        this.stateDir = Live.stateDir(plan.args());
    }

    /** Runs the watch to its end and returns the exit status. */
    int run() throws IOException {
        // A JVM that cannot be watched at all is a failure of the command, with no summary to write.
        final Jvm attached = Jvm.attach(plan.pid(), err);
        try {
            recording = Live.pick(attached, plan.args());
            tsv = Files.newBufferedWriter(plan.dir().resolve("samples.tsv"), StandardCharsets.UTF_8);
            tsv.write(TSV_HEADER + "\n");
        } catch (final IOException | RuntimeException | Error e) {
            if (tsv != null) {
                try {
                    tsv.close();
                } catch (final IOException | RuntimeException | Error c) {
                    e.addSuppressed(c);
                }
            }
            try {
                attached.close();
            } catch (final IOException | RuntimeException | Error c) {
                e.addSuppressed(c);
            }
            throw e;
        }
        synchronized (this) {
            jvm = attached;
        }
        final Thread main = Thread.currentThread();
        final Thread hook = new Thread(() -> stop(main), "jfrq-live watch stop");
        Runtime.getRuntime().addShutdownHook(hook);
        String ended;
        int status = 0;
        try {
            Live.line(out, header());
            startNanos = System.nanoTime();
            endNanos = startNanos + plan.forNanos();
            ended = loop(Instant.now());
            final int failed = failedWindows();
            if (failed > 0) {
                ended = String.format(Locale.ROOT, "%s, with %d of %d windows failed", ended, failed, rows.size());
                status = 1;
            }
        } catch (final CutShort e) {
            ended = e.getMessage();
            status = 1;
        } catch (final IOException e) {
            ended = "failed: " + Main.describe(e);
            status = 1;
        } catch (final RuntimeException e) {
            ended = "failed: " + Live.failure(e);
            status = 1;
        } finally {
            looped.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException e) {
                // Shutting down: the hook writes the summary.
            }
        }
        if (!stopping) {
            finish(ended);
        }
        return status;
    }

    /** The events in time order until the end; returns why it ended. */
    private String loop(final Instant startInstant) throws IOException, CutShort {
        int window = 1;
        Instant begin = startInstant;
        sample(1);
        if (plan.histo()) {
            histogram(String.format(Locale.ROOT, "w%0" + width() + "d-%s", 0, STAMP.format(startInstant)));
        }
        long nextSampleNanos = startNanos + plan.sampleNanos();
        long nextWindowNanos = Math.min(startNanos + plan.everyNanos(), endNanos);
        while (true) {
            final long dueNanos = nextSampleNanos - nextWindowNanos < 0 ? nextSampleNanos : nextWindowNanos;
            if (!waitUntil(dueNanos)) {
                return stopping ? "stopped" : "JVM " + plan.pid() + " exited at " + CLOCK.format(Instant.now()) + "Z";
            }
            final long nowNanos = System.nanoTime();
            if (nowNanos - nextSampleNanos >= 0) {
                sample(window);
                // A sample missed while a window was taken is skipped, not made up in a burst.
                while (nextSampleNanos - nowNanos <= 0) {
                    nextSampleNanos += plan.sampleNanos();
                }
            }
            if (nowNanos - nextWindowNanos >= 0) {
                final Instant end = Instant.now();
                window(window, begin, end);
                if (!alive()) {
                    return "JVM " + plan.pid() + " exited at " + CLOCK.format(Instant.now()) + "Z";
                }
                if (nextWindowNanos == endNanos) {
                    return "the time was up";
                }
                final long afterNanos = System.nanoTime();
                final Slot next = nextSlot(nextWindowNanos, afterNanos, plan.everyNanos(), endNanos);
                if (next.skipped() > 0) {
                    note(String.format(Locale.ROOT, "%d window slot%s skipped after window %d: the watch was held up "
                            + "(a sleeping host, a slow call), and window %d%s covers %s time", next.skipped(),
                            next.skipped() == 1 ? " was" : "s were", window, window,
                            next.none() ? "" : " with the next one", next.skipped() == 1 ? "its" : "their"));
                }
                if (next.none()) {
                    if (endNanos - afterNanos > 0) {
                        note("the last " + Durations.format(endNanos - afterNanos) + " before --for ended are in no "
                                + "window, being under half of --every after window " + window
                                + "; the next delta covers them");
                    }
                    return "the time was up";
                }
                window++;
                begin = end;
                nextWindowNanos = next.dueNanos();
            }
        }
    }

    /**
     * When the next window is due: {@code dueNanos} is the slot of the window just taken,
     * {@code afterNanos} when it ended.
     *
     * @param dueNanos the next slot on the {@code --every} grid, or {@code --for}'s end for the last
     * @param skipped  the grid slots before {@code --for}'s end passed over
     * @param none     no window is left: the stretch to the end is under the minimum gap
     */
    record Slot(long dueNanos, int skipped, boolean none) {
    }

    /**
     * The next window's slot. No window starts less than half of {@code --every} after the
     * previous one ended: slots closer than that, missed while the watch was held up (a
     * sleeping host, a slow call), are passed over rather than taken back to back, since each
     * costs a dump (with {@code --histo} a full GC) and histograms taken milliseconds apart
     * hide a class that grows. The grid stays where {@code --every} put it, so the window
     * after a passed slot lasts from half to one and a half {@code --every}; the last window
     * ends at {@code --for}, or there is none when that is under the minimum gap away.
     * Nanosecond times are compared by difference, as {@link System#nanoTime()} requires.
     */
    static Slot nextSlot(final long dueNanos, final long afterNanos, final long everyNanos, final long endNanos) {
        final long minGapNanos = everyNanos / 2;
        long next = dueNanos + everyNanos;
        if (next - afterNanos < minGapNanos) {
            next += ((afterNanos + minGapNanos - next) / everyNanos + 1) * everyNanos;
        }
        if (next - endNanos < 0) {
            return new Slot(next, (int) ((next - dueNanos) / everyNanos - 1), false);
        }
        // Grid slots strictly between the window just taken and the end were passed over.
        final int skipped = endNanos - dueNanos > everyNanos ? (int) ((endNanos - dueNanos - 1) / everyNanos) : 0;
        return new Slot(endNanos, skipped, endNanos - afterNanos < minGapNanos);
    }

    /** What is left before {@code --for} plus {@link #GRACE}: the bound of a call that has none of its own. */
    private Duration untilEnd() {
        return bound(Duration.ofNanos(Long.MAX_VALUE));
    }

    /** {@code cap}, cut to what is left before {@code --for} plus {@link #GRACE}; at least 1 ms. */
    private Duration bound(final Duration cap) {
        final long leftNanos = endNanos + GRACE.toNanos() - System.nanoTime();
        return Duration.ofNanos(Math.max(1_000_000, Math.min(cap.toNanos(), leftNanos)));
    }

    private synchronized void note(final String text) {
        notes.add(text);
    }

    private synchronized int failedWindows() {
        int failed = 0;
        for (final Row r : rows) {
            if (r.failure() != null && !JVM_EXITED.equals(r.failure())) {
                failed++;
            }
        }
        return failed;
    }

    /** Sleeps until {@code dueNanos}; false as soon as the JVM is gone or the watch is stopped. */
    private boolean waitUntil(final long dueNanos) {
        long leftNanos = dueNanos - System.nanoTime();
        while (leftNanos > 0) {
            if (stopping || !alive()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(ALIVE_CHECK_MILLIS, Math.max(1, leftNanos / 1_000_000)));
            } catch (final InterruptedException e) {
                // Only the shutdown hook interrupts the loop, and it has set stopping first.
                Thread.currentThread().interrupt();
                return false;
            }
            leftNanos = dueNanos - System.nanoTime();
        }
        return !stopping && alive();
    }

    private boolean alive() {
        return ProcessHandle.of(Long.parseLong(plan.pid())).map(ProcessHandle::isAlive).orElse(false);
    }

    private int width() {
        return Math.max(2, Integer.toString(plan.windows()).length());
    }

    private String header() {
        return String.format(Locale.ROOT, "Watch      JVM %s, recording %d %s: a window every %s for %s, a sample "
                + "every %s%s; into %s", plan.pid(), recording.getId(), recording.getName(),
                Durations.format(plan.everyNanos()), Durations.format(plan.forNanos()),
                Durations.format(plan.sampleNanos()),
                plan.histo() ? ", a class histogram at the start and per window (a full GC each)" : "",
                plan.dir());
    }

    private void sample(final int window) throws IOException, CutShort {
        final Jvm.Sample s;
        try {
            s = jvm.sample(bound(SAMPLE_TIMEOUT));
        } catch (final IOException | RuntimeException e) {
            recover("a sample", e);
            return;
        }
        double cpuPct = Double.NaN;
        // The machine's load is the JVM's reading since its previous one: the first is 0.0 on macOS, not a measurement.
        final double systemPct = previous == null || s.systemCpuLoad() < 0 ? Double.NaN : 100 * s.systemCpuLoad();
        if (previous != null && s.processCpuNanos() >= 0 && previous.processCpuNanos() >= 0
                && s.atNanos() - previous.atNanos() > 0) {
            // CPU time over wall time, as top and ps count it: 100 is one core busy.
            cpuPct = 100.0 * (s.processCpuNanos() - previous.processCpuNanos()) / (s.atNanos() - previous.atNanos());
        }
        previous = s;
        final String line = String.join("\t", Live.DATE_TIME.format(Instant.now()),
                Double.isNaN(cpuPct) ? "" : pct(cpuPct), Double.isNaN(systemPct) ? "" : pct(systemPct),
                Long.toString(s.heapUsedBytes()),
                Long.toString(s.heapCommittedBytes()), Long.toString(s.threads()), cell(s.openFds()),
                cell(s.committedVirtualBytes()));
        synchronized (this) {
            if (finished) {
                return;
            }
            // Written as it is taken, so a watch that is killed still leaves its samples.
            tsv.write(line + "\n");
            tsv.flush();
            points.add(new Point(window, cpuPct, s.heapUsedBytes(), s.threads(), s.openFds()));
        }
    }

    private static String pct(final double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String cell(final long value) {
        return value < 0 ? "" : Long.toString(value);
    }

    private void histogram(final String base) throws IOException, CutShort {
        final Path file = plan.dir().resolve(base + ".histo.txt");
        final String text;
        try {
            text = jvm.classHistogram(bound(HISTO_TIMEOUT));
        } catch (final IOException | RuntimeException e) {
            final String why = recover("a class histogram", e);
            if (!JVM_EXITED.equals(why)) {
                note("the class histogram " + file.getFileName() + " failed: " + why
                        + "; the growth table has one histogram fewer");
            }
            return;
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
        synchronized (this) {
            histograms.add(file);
        }
        Live.line(out, String.format(Locale.ROOT, "Histogram  %s  %,d classes", file.getFileName(),
                Histogram.parse(text, file.toString()).classes().size()));
    }

    private void window(final int index, final Instant begin, final Instant end) throws IOException, CutShort {
        final long t0Nanos = System.nanoTime();
        final String base = String.format(Locale.ROOT, "w%0" + width() + "d-%s", index, STAMP.format(end));
        final Path file = plan.dir().resolve(base + ".jfr");
        Live.line(out, "");
        Live.line(out, String.format(Locale.ROOT, "Watch %d/%d  %sZ .. %sZ", index, plan.windows(), CLOCK.format(begin),
                CLOCK.format(end)));
        final Live.Taken taken;
        final String command;
        try {
            final Jvm on = jvm;
            final RecordingInfo of = recording;
            // A first window needs a cursor to be a delta: without one it is everything the recording holds.
            command = Cursor.load(stateDir, plan.pid(), Jvm.within(on::startTime, bound(SAMPLE_TIMEOUT),
                    "the window's dump")).next() == null ? "full" : "delta";
            // The dump's own bound is the watch's end: a JVM that stops answering cannot hold it past --for.
            taken = Jvm.within(() -> Live.take(on, command, of, stateDir, file, true, err), untilEnd(),
                    "the window's dump");
        } catch (final IOException | RuntimeException e) {
            final String why;
            try {
                why = recover("the window's dump", e);
            } catch (final CutShort c) {
                synchronized (this) {
                    rows.add(new Row(index, begin, end, base, -1, 0, System.nanoTime() - t0Nanos, List.of(),
                            "the watch ended during it"));
                }
                throw c;
            }
            synchronized (this) {
                rows.add(new Row(index, begin, end, base, -1, 0, System.nanoTime() - t0Nanos, List.of(), why));
            }
            if (!JVM_EXITED.equals(why)) {
                requireRunning();
            }
            return;
        }
        final long dumpNanos = System.nanoTime() - t0Nanos;
        Live.check(taken.snapshot(), taken.chunks(), taken.window(), command, recording, jvm, out);
        if (plan.histo()) {
            histogram(base);
        }
        final List<String> answers = new ArrayList<>();
        final Map<String, Integer> seen = new HashMap<>();
        for (final String[] question : plan.asks()) {
            final int n = seen.merge(question[0], 1, Integer::sum);
            final String name = base + "." + question[0] + (n == 1 ? "" : "-" + n)
                    + (Main.answersInJson(question) ? ".json" : ".txt");
            final long q0Nanos = System.nanoTime();
            final int status;
            try (final PrintStream to = new PrintStream(Files.newOutputStream(plan.dir().resolve(name)), true,
                    StandardCharsets.UTF_8)) {
                // The answer and any complaint go to one file, so the file says why it is short.
                status = Main.run(Live.questionOn(question, file), to, to);
            }
            answers.add(String.format(Locale.ROOT, "%s (exit %d, %s)", name, status,
                    Durations.format(System.nanoTime() - q0Nanos)));
        }
        if (!answers.isEmpty()) {
            Live.line(out, "Answers    " + String.join(", ", answers));
        }
        synchronized (this) {
            rows.add(new Row(index, begin, end, base, taken.snapshot().bytes(), dumpNanos, System.nanoTime() - t0Nanos,
                    answers, null));
        }
    }

    /**
     * What a failed call means. The JVM gone ends the watch at the next check; a call that
     * failed on a good connection is the call's own failure, returned as the reason; a
     * connection that does not answer is opened again, once, unless the watch is past
     * {@code --for} or stopping, which ends it.
     */
    private String recover(final String what, final Exception e) throws CutShort {
        final String why = e instanceof final IOException io ? Main.describe(io)
                : e instanceof final RuntimeException r ? Live.failure(r) : e.toString();
        if (!alive()) {
            return JVM_EXITED;
        }
        if (connected()) {
            Live.line(err, "jfrq-live: " + what + ": " + why);
            return why;
        }
        if (reconnected || stopping || System.nanoTime() - endNanos >= 0) {
            throw new CutShort("JVM " + plan.pid() + " no longer answers"
                    + (reconnected ? " (connected again once)" : "") + ": " + why);
        }
        reconnected = true;
        Live.line(err, "jfrq-live: JVM " + plan.pid() + " did not answer " + what + " (" + why
                + "); connecting again");
        final Jvm old;
        synchronized (this) {
            old = jvm;
            jvm = null;
        }
        try {
            old.close(CLOSE_TIMEOUT);
        } catch (final IOException | RuntimeException c) {
            Live.line(err, "jfrq-live: warning: the connection that did not answer did not close: " + c);
        }
        try {
            final Jvm again = Jvm.within(() -> Jvm.attach(plan.pid(), err), untilEnd(), "the attach");
            synchronized (this) {
                jvm = again;
            }
            recording = Jvm.within(() -> Live.pick(again, plan.args()), bound(SAMPLE_TIMEOUT), "the recordings' list");
        } catch (final IOException | RuntimeException a) {
            throw new CutShort("JVM " + plan.pid() + " did not answer " + what + " and could not be connected to "
                    + "again: " + (a instanceof final IOException io ? Main.describe(io) : a.toString()));
        }
        note("connected again at " + CLOCK.format(Instant.now()) + "Z after JVM " + plan.pid() + " did not answer "
                + what + " (" + why + "); samples.tsv has no rows for that time");
        return why + " (connected again)";
    }

    private boolean connected() {
        try {
            Jvm.within(jvm::startTime, bound(SAMPLE_TIMEOUT), "a connection check");
            return true;
        } catch (final IOException | UndeclaredThrowableException e) {
            return false;
        }
    }

    /**
     * Ends the watch when its recording is gone or no longer running: no later window could
     * succeed, so going on would claim time it does not cover. A check that itself fails
     * leaves the window's own failure as the reason.
     */
    private void requireRunning() throws CutShort {
        final long id = recording.getId();
        final List<RecordingInfo> all;
        try {
            final Jvm on = jvm;
            all = Jvm.within(() -> on.flightRecorder().getRecordings(), bound(SAMPLE_TIMEOUT), "the recordings' list");
        } catch (final IOException | RuntimeException e) {
            return;
        }
        for (final RecordingInfo r : all) {
            if (r.getId() == id) {
                if (!"RUNNING".equals(r.getState())) {
                    throw new CutShort("recording " + id + " " + r.getName() + " is " + r.getState()
                            + "; no later window can be taken");
                }
                return;
            }
        }
        throw new CutShort("recording " + id + " " + recording.getName() + " was stopped and closed; no later "
                + "window can be taken");
    }

    /** A watch cut short by something other than the JVM's exit or Ctrl-C; it exits 1. */
    static final class CutShort extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        CutShort(final String message) {
            super(message);
        }
    }

    /**
     * Ctrl-C or {@code SIGTERM}: the window in progress gets {@link #STOP_WAIT_MILLIS} to end
     * (a dump in flight is abandoned by its own hook), then the summary is written.
     */
    void stop(final Thread main) {
        stopping = true;
        main.interrupt();
        try {
            // Not a join: once the loop is done the main thread calls System.exit, which blocks
            // for as long as this hook runs.
            if (!looped.await(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                Live.line(err, "jfrq-live: the window in progress did not end in " + Durations.format(
                        Duration.ofMillis(STOP_WAIT_MILLIS)) + "; the summary is written without it");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finish("stopped");
    }

    /** Writes the summary once, closes the samples file and the connection; idempotent. */
    synchronized void finish(final String ended) {
        if (finished) {
            return;
        }
        finished = true;
        final String summary = summary(ended);
        out.print(summary);
        out.flush();
        try {
            Files.writeString(plan.dir().resolve("summary.txt"), summary, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Live.line(err, "jfrq-live: the summary could not be written: " + Main.describe(e));
        }
        if (tsv != null) {
            try {
                tsv.close();
            } catch (final IOException e) {
                Live.line(err, "jfrq-live: samples.tsv did not close cleanly: " + Main.describe(e));
            }
        }
        // A JVM that is gone has nothing to say goodbye to: closing would only wait on RMI.
        if (jvm != null && alive()) {
            try {
                jvm.close(CLOSE_TIMEOUT);
            } catch (final IOException | RuntimeException e) {
                Live.line(err, "jfrq-live: warning: the connection to JVM " + plan.pid() + " did not close: "
                        + (e instanceof final IOException io ? Main.describe(io) : e.toString()));
            }
        }
    }

    private String summary(final String ended) {
        final StringBuilder sb = new StringBuilder("\n");
        sb.append(String.format(Locale.ROOT, "Summary    %d window%s, %d sample%s in %s; ended after %s: %s\n",
                rows.size(), rows.size() == 1 ? "" : "s", points.size(), points.size() == 1 ? "" : "s", plan.dir(),
                Durations.format(startNanos == 0 ? 0 : System.nanoTime() - startNanos), ended));
        sb.append(String.format(Locale.ROOT, "\n  %-3s %-20s %-18s %9s %9s %9s  %-15s %-21s %-11s %s\n", "#",
                "window (UTC)", "file", "dump", "took", "dumping", "process CPU %", "heap used", "threads", "fds"));
        for (final Row r : rows) {
            final List<Point> in = new ArrayList<>();
            for (final Point p : points) {
                if (p.window() == r.index()) {
                    in.add(p);
                }
            }
            sb.append(String.format(Locale.ROOT, "  %-3d %-20s %-18s %9s %9s %9s  %-15s %-21s %-11s %s\n", r.index(),
                    CLOCK.format(r.begin()) + " .. " + CLOCK.format(r.end()), r.base() + ".jfr",
                    r.bytes() < 0 ? "failed" : Bytes.format(r.bytes()), Durations.format(r.tookNanos()),
                    r.bytes() < 0 ? "-" : Durations.format(r.dumpNanos()), cpuRange(in), heapRange(in),
                    range(in, Point::threads), range(in, Point::openFds)));
        }
        for (final Row r : rows) {
            if (r.failure() != null) {
                final Row later = rows.stream().filter(o -> o.index() > r.index() && o.bytes() >= 0).findFirst()
                        .orElse(null);
                sb.append(String.format(Locale.ROOT, "  window %d has no dump (%s); %s\n", r.index(), r.failure(),
                        later == null ? "no later window covers its time"
                                : "window " + later.index() + "'s delta covers its time"));
            }
            for (final String a : r.answers()) {
                if (!a.contains("(exit 0,")) {
                    sb.append("  window ").append(r.index()).append(": ").append(a).append('\n');
                }
            }
        }
        for (final String n : notes) {
            sb.append("  ").append(n).append('\n');
        }
        if (histograms.size() >= 2) {
            final List<Histogram> read = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            try {
                for (final Path h : histograms) {
                    read.add(Histogram.read(h));
                    labels.add(h.getFileName().toString());
                }
                sb.append('\n').append(Histogram.table(labels, read, 15));
            } catch (final IOException e) {
                sb.append("\nGrowth     not computed: ").append(Main.describe(e)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String cpuRange(final List<Point> in) {
        double lo = Double.NaN;
        double hi = Double.NaN;
        for (final Point p : in) {
            if (!Double.isNaN(p.processCpuPct())) {
                lo = Double.isNaN(lo) ? p.processCpuPct() : Math.min(lo, p.processCpuPct());
                hi = Double.isNaN(hi) ? p.processCpuPct() : Math.max(hi, p.processCpuPct());
            }
        }
        return Double.isNaN(lo) ? "-" : pct(lo) + " .. " + pct(hi);
    }

    private static String heapRange(final List<Point> in) {
        long lo = Long.MAX_VALUE;
        long hi = -1;
        for (final Point p : in) {
            lo = Math.min(lo, p.heapUsedBytes());
            hi = Math.max(hi, p.heapUsedBytes());
        }
        return hi < 0 ? "-" : Bytes.format(lo) + " .. " + Bytes.format(hi);
    }

    private static String range(final List<Point> in, final ToLongFunction<Point> value) {
        long lo = Long.MAX_VALUE;
        long hi = -1;
        for (final Point p : in) {
            final long v = value.applyAsLong(p);
            if (v >= 0) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        return hi < 0 ? "-" : lo + " .. " + hi;
    }
}
