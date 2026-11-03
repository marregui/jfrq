// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.cli.Args;
import dev.jfrq.core.report.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `watch` and `histo` against the JVM the tests run in, and `watch` against a child JVM that exits under it. Sizes are
/// asserted in counts of windows, files and rows, never in time: a late runner makes a window late, or, when it ends
/// less than half of `--every` before the next slot, passes that slot over (`Watch.nextSlot`), which the summary notes.
class WatchTest {

    private static final String PID = Long.toString(ProcessHandle.current().pid());
    /// A window file's stamp: the UTC minute the window ended.
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Pattern ENDED_AFTER = Pattern.compile("ended after ([0-9.]+) s: ");

    @TempDir
    Path dir;

    /// A short watch leaves every window's dump, answer and histogram, the samples and the summary, and ends by itself.
    @Test
    void aWatchLeavesItsFilesAndEndsByItself() throws Exception {
        final String name = "watch-test-" + System.nanoTime();
        final LiveTest.Run start = LiveTest.run(PID, "start", "--name", name, "--max-age", "5m", "--state",
                dir.toString());
        assertEquals(0, start.status(), start.err());
        try {
            final Path out = dir.resolve("w");
            final LiveTest.Run watch = LiveTest.run(PID, "watch", "--every", "2s", "--for", "5s", "--sample", "1s",
                    "--histo", "--ask", "info", "--ask=info --json", "--recording", name, "--state", dir.toString(),
                    "--out", out.toString());
            assertEquals(0, watch.status(), watch.err());
            final List<String> files = new ArrayList<>();
            try (final Stream<Path> list = Files.list(out)) {
                list.forEach(p -> files.add(p.getFileName().toString()));
            }
            assertTrue(files.contains("samples.tsv"), files.toString());
            assertTrue(files.contains("summary.txt"), files.toString());
            assertEquals(1, count(files, "w00-", ".histo.txt"), files.toString());
            final int windows = count(files, "", ".jfr");
            assertTrue(windows >= 1, files.toString());
            assertEquals(windows, count(files, "", ".info.txt"), files.toString());
            // The second question with the same command gets a file of its own, named for what it holds.
            assertEquals(windows, count(files, "", ".info-2.json"), files.toString());
            for (final String f : files) {
                if (f.endsWith(".info-2.json")) {
                    final String answer = Files.readString(out.resolve(f), StandardCharsets.UTF_8);
                    assertEquals("info", JsonParser.object(answer).get("command"), answer);
                }
            }
            assertEquals(windows + 1, count(files, "", ".histo.txt"), files.toString());
            final List<String> samples = Files.readAllLines(out.resolve("samples.tsv"), StandardCharsets.UTF_8);
            assertEquals(Watch.TSV_HEADER, samples.getFirst());
            assertTrue(samples.size() >= 3, samples.toString());
            final String[] firstRow = samples.get(1).split("\t", -1);
            assertEquals(8, firstRow.length, samples.get(1));
            // CPU is a rate over the time since the previous sample: the first row has none.
            assertEquals("", firstRow[1], samples.get(1));
            assertEquals("", firstRow[2], samples.get(1));
            assertFalse(samples.get(2).split("\t", -1)[1].isEmpty(), samples.get(2));
            final String summary = Files.readString(out.resolve("summary.txt"), StandardCharsets.UTF_8);
            assertTrue(watch.out().endsWith(summary), watch.out());
            assertTrue(summary.contains(": the time was up"), summary);
            for (int w = 1; w <= windows; w++) {
                assertTrue(summary.contains("\n  " + w + "   "), summary);
            }
            // Window 1 ending over a second late passes the slot at 4 s over; the summary says where its time went.
            assertTrue(windows >= 2 || summary.contains("in no window"), summary);
            assertTrue(summary.contains("Growth     " + (windows + 1) + " histograms"), summary);
            assertTrue(Files.readString(out.resolve(first(files, ".info.txt")), StandardCharsets.UTF_8)
                    .contains("Recording"));
            // The cursor moved with the windows, so a delta after the watch continues where it stopped.
            assertTrue(LiveTest.run(PID, "status", "--state", dir.toString()).out().contains("Cursor     next delta"));
        } finally {
            LiveTest.run(PID, "stop", "--recording", name);
        }
    }

    /// Stopped early (what the shutdown hook does on Ctrl-C), a watch still writes its summary, once.
    @Test
    void aStoppedWatchWritesItsSummary() throws Exception {
        final String name = "watch-stop-" + System.nanoTime();
        assertEquals(0, LiveTest.run(PID, "start", "--name", name, "--state", dir.toString()).status());
        try {
            final Path out = dir.resolve("s");
            final Args args = Args.parse(new String[] {"--every", "1h", "--for", "1h", "--sample", "1s", "--recording",
                name, "--state", dir.toString(), "--out", out.toString()}, Set.of("every", "for", "sample",
                "recording", "state", "out"), Set.of("histo"));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                final Watch watch = new Watch(Live.plan(PID, args, List.of()), stream, stream);
                final AtomicInteger status = new AtomicInteger(-1);
                final Thread thread = Thread.ofPlatform().name("watch-under-test").start(() -> {
                    try {
                        status.set(watch.run());
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                awaitLines(out.resolve("samples.tsv"), 3);
                watch.stop(thread);
                thread.join(30_000);
                assertEquals(0, status.get());
            }
            final String text = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains(": stopped\n"), text);
            assertEquals(text.indexOf("Summary"), text.lastIndexOf("Summary"), text);
            assertTrue(Files.readString(out.resolve("summary.txt"), StandardCharsets.UTF_8).contains("0 windows"));
        } finally {
            LiveTest.run(PID, "stop", "--recording", name);
        }
    }

    /// A JVM that exits ends the watch at once, with status 0 and the reason in the summary.
    @Test
    void aJvmThatExitsEndsTheWatch() throws Exception {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final Process child = new ProcessBuilder(java, "-XX:StartFlightRecording=name=child,maxage=1m", "-cp",
                System.getProperty("java.class.path"), LockHolder.class.getName(), dir.toString(), "79")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            // The recorder says it started on standard output first.
            try (final BufferedReader fromChild = new BufferedReader(new InputStreamReader(child.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line = fromChild.readLine();
                while (line != null && !line.equals("locked")) {
                    line = fromChild.readLine();
                }
                assertEquals("locked", line);
            }
            final Path out = dir.resolve("c");
            final CompletableFuture<LiveTest.Run> watch = CompletableFuture.supplyAsync(() -> LiveTest.run(
                    Long.toString(child.pid()), "watch", "--every", "1h", "--for", "1h", "--sample", "1s", "--state",
                    dir.toString(), "--out", out.toString()));
            awaitLines(out.resolve("samples.tsv"), 2);
            child.getOutputStream().close();
            final LiveTest.Run run = watch.get(60, TimeUnit.SECONDS);
            assertEquals(0, run.status(), run.err());
            assertTrue(run.out().contains(": JVM " + child.pid() + " exited at "), run.out());
        } finally {
            child.destroy();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        }
    }

    /// A recording stopped under the watch ends it with status 1 and says so: no later window could succeed.
    @Test
    void aRecordingStoppedUnderTheWatchEndsIt() throws Exception {
        final String name = "watch-gone-" + System.nanoTime();
        assertEquals(0, LiveTest.run(PID, "start", "--name", name, "--state", dir.toString()).status());
        final Path out = dir.resolve("g");
        final CompletableFuture<LiveTest.Run> watch = CompletableFuture.supplyAsync(() -> LiveTest.run(PID, "watch",
                "--every", "1s", "--for", "1m", "--sample", "1s", "--recording", name, "--state", dir.toString(),
                "--out", out.toString()));
        awaitLines(out.resolve("samples.tsv"), 2);
        assertEquals(0, LiveTest.run(PID, "stop", "--recording", name).status());
        final LiveTest.Run run = watch.get(60, TimeUnit.SECONDS);
        assertEquals(1, run.status(), run.out() + run.err());
        assertTrue(run.out().contains("no later window can be taken"), run.out());
    }

    /// The next window's slot: on the grid when on time; slots under half of --every after the previous window
    /// ended are passed over, mid-run and at the end, so no two windows (and no two histograms) come back to back.
    @Test
    void windowsNeverComeBackToBack() {
        final long every = 10;
        final long end = 100;
        // On time: the next slot, nothing skipped.
        assertEquals(new Watch.Slot(30, 0, false), Watch.nextSlot(20, 21, every, end));
        // Held up from slot 20 to 54: slots 30, 40 and 50 are passed over; 60 is the first 5 or more after 54.
        assertEquals(new Watch.Slot(60, 3, false), Watch.nextSlot(20, 54, every, end));
        // Held up to 57: 60 is under half of --every away, so it goes too.
        assertEquals(new Watch.Slot(70, 4, false), Watch.nextSlot(20, 57, every, end));
        // The last window ends at --for, when that is at least half of --every away.
        assertEquals(new Watch.Slot(100, 0, false), Watch.nextSlot(90, 91, every, end));
        // A hold-up across the end: no last window, rather than one milliseconds after the late one.
        assertEquals(new Watch.Slot(100, 0, true), Watch.nextSlot(90, 97, every, end));
        assertEquals(new Watch.Slot(100, 1, true), Watch.nextSlot(80, 104, every, end));
        // --for not a multiple of --every: a last stretch of 7 is a window, one of 3 is not.
        assertEquals(new Watch.Slot(97, 0, false), Watch.nextSlot(90, 90, every, 97));
        assertEquals(new Watch.Slot(93, 0, true), Watch.nextSlot(90, 90, every, 93));
        // Times near the end of the long range compare by difference, as nanoTime requires.
        final long base = Long.MAX_VALUE - 15;
        assertEquals(new Watch.Slot(base + 10, 0, false), Watch.nextSlot(base, base + 1, every, base + 100));
    }

    /// A last stretch under half of `--every` gets no window, but the watch samples on and ends at `--for`, not after
    /// the last window.
    @Test
    void theWatchLastsForItsFullTime() throws Exception {
        final String name = "watch-tail-" + System.nanoTime();
        assertEquals(0, LiveTest.run(PID, "start", "--name", name, "--state", dir.toString()).status());
        try {
            final Path out = dir.resolve("t");
            final LiveTest.Run watch = LiveTest.run(PID, "watch", "--every", "2s", "--for", "2.9s", "--sample", "500ms",
                    "--recording", name, "--state", dir.toString(), "--out", out.toString());
            assertEquals(0, watch.status(), watch.err());
            final String summary = Files.readString(out.resolve("summary.txt"), StandardCharsets.UTF_8);
            assertTrue(summary.contains("Summary    1 window, "), summary);
            assertTrue(summary.contains(": the time was up\n"), summary);
            assertTrue(summary.contains("before --for ended are in no window"), summary);
            final Matcher ended = ENDED_AFTER.matcher(summary);
            assertTrue(ended.find(), summary);
            assertTrue(Double.parseDouble(ended.group(1)) >= 2.9, summary);
        } finally {
            LiveTest.run(PID, "stop", "--recording", name);
        }
    }

    /// A step after the dump that fails (here the answer's file cannot be created) keeps the window: the cursor is past
    /// its dump, so its row, its dump and the failure are in the summary, and the watch exits 1.
    @Test
    void aFailureAfterTheDumpKeepsTheWindow() throws Exception {
        final String name = "watch-after-" + System.nanoTime();
        assertEquals(0, LiveTest.run(PID, "start", "--name", name, "--state", dir.toString()).status());
        try {
            final Path out = dir.resolve("a");
            final Args args = Args.parse(new String[] {"--every", "1s", "--for", "1s", "--recording", name, "--state",
                dir.toString(), "--out", out.toString()}, Set.of("every", "for", "sample", "recording", "state", "out"),
                Set.of("histo"));
            final Watch.Plan plan = Live.plan(PID, args, List.<String[]>of(new String[] {"info"}));
            // A directory where the answer's file would go, for every minute the one window can end in.
            final Instant now = Instant.now();
            for (int m = 0; m < 3; m++) {
                Files.createDirectories(out.resolve("w01-" + STAMP.format(now.plusSeconds(60L * m)) + ".info.txt"));
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final int status;
            try (final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                status = new Watch(plan, stream, stream).run();
            }
            final String text = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, status, text);
            final String summary = Files.readString(out.resolve("summary.txt"), StandardCharsets.UTF_8);
            assertTrue(summary.contains("Summary    1 window, "), summary);
            assertTrue(summary.contains("window 1 failed after its dump ("), summary);
            assertTrue(summary.contains(", with 1 of 1 windows failed"), summary);
            final List<String> files = new ArrayList<>();
            try (final Stream<Path> list = Files.list(out)) {
                list.forEach(p -> files.add(p.getFileName().toString()));
            }
            assertEquals(1, count(files, "w01-", ".jfr"), files.toString());
        } finally {
            LiveTest.run(PID, "stop", "--recording", name);
        }
    }

    /// A runtime failure that ends the watch is described once: `failed: ` and the exception, never the prefix twice.
    @Test
    void aRuntimeFailureEndsTheWatchWithOneDescription() throws Exception {
        final String name = "watch-boom-" + System.nanoTime();
        assertEquals(0, LiveTest.run(PID, "start", "--name", name, "--state", dir.toString()).status());
        try {
            final Path out = dir.resolve("b");
            final Args args = Args.parse(new String[] {"--every", "1h", "--for", "1h", "--recording", name, "--state",
                dir.toString(), "--out", out.toString()}, Set.of("every", "for", "sample", "recording", "state", "out"),
                Set.of("histo"));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final int status;
            try (final PrintStream stream = new FailsOnTheHeader(bytes)) {
                status = new Watch(Live.plan(PID, args, List.of()), stream, stream).run();
            }
            final String text = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(1, status, text);
            assertTrue(text.contains(": failed: java.lang.IllegalStateException: boom\n"), text);
            assertFalse(text.contains("failed: failed:"), text);
        } finally {
            LiveTest.run(PID, "stop", "--recording", name);
        }
    }

    /// A stream that fails once, on the watch's header line: a runtime failure inside the watch.
    private static final class FailsOnTheHeader extends PrintStream {

        private boolean failed;

        FailsOnTheHeader(final ByteArrayOutputStream bytes) {
            super(bytes, true, StandardCharsets.UTF_8);
        }

        @Override
        public void print(final String s) {
            if (!failed && s != null && s.startsWith("Watch      JVM")) {
                failed = true;
                throw new IllegalStateException("boom");
            }
            super.print(s);
        }
    }

    /// A JVM that cannot be attached to fails the command with status 1, and no summary claims a watch that never ran.
    @Test
    void aJvmThatCannotBeAttachedIsAFailureWithoutASummary() throws IOException {
        final Path out = dir.resolve("none");
        final LiveTest.Run run = LiveTest.run("999999999", "watch", "--every", "1s", "--for", "2s", "--out",
                out.toString());
        assertEquals(1, run.status());
        assertTrue(run.err().contains("no such process"), run.err());
        assertEquals("", run.out());
        assertFalse(Files.exists(out.resolve("summary.txt")));
    }

    /// `histo` writes the JVM's class histogram where `--out` says, or prints it.
    @Test
    void histoTakesOneHistogram() throws Exception {
        final Path file = dir.resolve("h.txt");
        final LiveTest.Run run = LiveTest.run(PID, "histo", "--out", file.toString());
        assertEquals(0, run.status(), run.err());
        assertTrue(run.out().startsWith("Histogram  " + file + "  "), run.out());
        assertTrue(Histogram.read(file).instances("java.lang.String") > 0);
        final LiveTest.Run printed = LiveTest.run(PID, "histo");
        assertEquals(0, printed.status(), printed.err());
        assertTrue(Histogram.parse(printed.out(), "the printed histogram").instances("java.lang.String") > 0);
    }

    /// Everything wrong with a watch's command line is a usage error before the JVM is attached to.
    @Test
    void watchUsageErrorsBeforeAttaching() throws IOException {
        final String out = dir.resolve("u").toString();
        assertEquals(2, LiveTest.run(PID, "watch", "--every", "1s", "--out", out).status());
        assertEquals(2, LiveTest.run(PID, "watch", "--every", "1s", "--for", "infinity", "--out", out).status());
        assertEquals(2, LiveTest.run(PID, "watch", "--every", "1s", "--for", "2s", "--ask", "frobnicate", "--out", out)
                .status());
        assertEquals(2, LiveTest.run(PID, "watch", "--every", "1s", "--for", "2s", "--ask", "stalls 'x", "--out", out)
                .status());
        assertEquals(2, LiveTest.run(PID, "watch", "--every", "1s", "--for", "2s", "--ask", " ", "--out", out)
                .status());
        assertEquals(2, LiveTest.run(PID, "watch", "--every", "1s", "--for", "2s", "--ask").status());
        assertEquals(2, LiveTest.run(PID, "delta", "--ask", "info").status());
        assertEquals(2, LiveTest.run(PID, "histo", "--recording", "x").status());
        Files.createDirectories(dir.resolve("full"));
        Files.writeString(dir.resolve("full").resolve("old.jfr"), "x", StandardCharsets.UTF_8);
        final LiveTest.Run used = LiveTest.run(PID, "watch", "--every", "1s", "--for", "2s", "--out",
                dir.resolve("full").toString());
        assertEquals(2, used.status());
        assertTrue(used.err().contains("already holds files"), used.err());
    }

    /// A question is split as the shell would split it, quotes keeping spaces and the other quote.
    @Test
    void questionsAreSplitAsTheShellWould() {
        assertEquals(List.of("stalls", "--thread", "AWT-EventQueue-*"),
                Live.words("stalls --thread 'AWT-EventQueue-*'"));
        assertEquals(List.of("locks", "--thread", "a b", "it's"), Live.words("  locks --thread \"a b\" \"it's\" "));
        assertEquals(List.of("x", ""), Live.words("x ''"));
        assertThrows(Args.UsageException.class, () -> Live.words("x \"y"));
    }

    /// A remote call that does not answer in time is a failure that says so; one that fails says why.
    @Test
    void aCallWithoutAnAnswerIsBounded() throws IOException {
        final IOException late = assertThrows(IOException.class, () -> Jvm.within(() -> {
            LockSupport.parkNanos(10_000_000_000L);
            return "late";
        }, Duration.ofMillis(50), "a sample"));
        assertTrue(late.getMessage().contains("did not answer a sample in 50.0 ms"), late.getMessage());
        final IOException reset = new IOException("connection reset");
        final IOException failed = assertThrows(IOException.class, () -> Jvm.within(() -> {
            throw reset;
        }, Duration.ofSeconds(5), "a sample"));
        assertSame(reset, failed);
        assertThrows(IllegalStateException.class, () -> Jvm.within(() -> {
            throw new IllegalStateException("refused");
        }, Duration.ofSeconds(5), "a sample"));
        assertEquals("ok", Jvm.within(() -> "ok", Duration.ofSeconds(5), "a sample"));
    }

    private static int count(final List<String> files, final String prefix, final String suffix) {
        int n = 0;
        for (final String f : files) {
            if (f.startsWith(prefix) && f.endsWith(suffix)) {
                n++;
            }
        }
        return n;
    }

    private static String first(final List<String> files, final String suffix) {
        for (final String f : files) {
            if (f.endsWith(suffix)) {
                return f;
            }
        }
        throw new AssertionError(suffix + " not in " + files);
    }

    /// Polls until `file` has `n` lines, failing after a minute: a condition, not a guess at a time.
    private static void awaitLines(final Path file, final int n) throws IOException {
        final long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (lines(file) < n) {
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new AssertionError(file + " did not reach " + n + " lines in a minute");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }

    private static int lines(final Path file) throws IOException {
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
    }
}
