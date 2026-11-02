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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.cli.Args;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `watch` and `histo` against the JVM the tests run in, and `watch` against a child JVM that exits under it. Sizes are
/// asserted in counts of windows, files and rows, never in time: a late runner makes a window late, not missing.
class WatchTest {

    private static final String PID = Long.toString(ProcessHandle.current().pid());

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
            assertTrue(windows >= 2, files.toString());
            assertEquals(windows, count(files, "", ".info.txt"), files.toString());
            // The second question with the same command gets a file of its own, named for what it holds.
            assertEquals(windows, count(files, "", ".info-2.json"), files.toString());
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
            assertTrue(summary.contains("ended: the time was up"), summary);
            assertTrue(summary.contains("\n  1   "), summary);
            assertTrue(summary.contains("\n  2   "), summary);
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
            final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
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
            final String text = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("ended: stopped"), text);
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
            final BufferedReader fromChild = new BufferedReader(new InputStreamReader(child.getInputStream(),
                    StandardCharsets.UTF_8));
            String line = fromChild.readLine();
            while (line != null && !line.equals("locked")) {
                line = fromChild.readLine();
            }
            assertEquals("locked", line);
            final Path out = dir.resolve("c");
            final CompletableFuture<LiveTest.Run> watch = CompletableFuture.supplyAsync(() -> LiveTest.run(
                    Long.toString(child.pid()), "watch", "--every", "1h", "--for", "1h", "--sample", "1s", "--state",
                    dir.toString(), "--out", out.toString()));
            awaitLines(out.resolve("samples.tsv"), 2);
            child.getOutputStream().close();
            final LiveTest.Run run = watch.get(60, TimeUnit.SECONDS);
            assertEquals(0, run.status(), run.err());
            assertTrue(run.out().contains("ended: JVM " + child.pid() + " exited at "), run.out());
        } finally {
            child.destroy();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
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
        final IOException failed = assertThrows(IOException.class, () -> Jvm.within(() -> {
            throw new IOException("connection reset");
        }, Duration.ofSeconds(5), "a sample"));
        assertEquals("connection reset", failed.getMessage());
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
