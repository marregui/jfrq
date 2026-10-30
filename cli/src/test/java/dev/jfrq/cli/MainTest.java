// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.report.JsonParser;
import dev.jfrq.core.stalls.Stall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every command against one small recording made once for the class: a named loop
 * thread that idles, sleeps, burns CPU and blocks on a monitor, plus an allocating thread.
 */
class MainTest {

    @TempDir
    static Path dir;
    static Path recording;

    /** A loop idle in a Java method so the sampler sees its idle point. */
    static final class Loop {
        static void idle() {
            final long deadline = System.nanoTime() + 300 * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    @BeforeAll
    static void record() throws Exception {
        recording = dir.resolve("cli.jfr");
        final Object lock = new Object();
        // A wait that times out inside the fixture's threads is recorded here and failed on below:
        // an assertion thrown on another thread would only end that thread.
        final Queue<String> stuck = new ConcurrentLinkedQueue<>();
        try (final Recording r = new Recording()) {
            r.enable("jdk.ActiveSetting");
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.ObjectAllocationSample").with("throttle", "2000/s").withStackTrace();
            // For health.
            r.enable("jdk.GarbageCollection");
            r.enable("jdk.GCConfiguration");
            r.enable("jdk.ExceptionStatistics").withPeriod(Duration.ofMillis(100));
            r.enable("jdk.JavaExceptionThrow").withStackTrace();
            r.setDestination(recording);
            r.start();

            final Thread allocator = new Thread(() -> {
                final byte[][] keep = new byte[32][];
                for (int i = 0; i < 3_000; i++) {
                    keep[i % keep.length] = new byte[32 * 1024];
                }
                if (keep[0].length == 0) {
                    throw new IllegalStateException();
                }
            }, "alloc-cli");
            allocator.start();

            final Thread loop = new Thread(() -> {
                try {
                    Loop.idle();
                    Thread.sleep(150);
                    Loop.idle();
                    final CountDownLatch held = new CountDownLatch(1);
                    final Thread me = Thread.currentThread();
                    final Thread holder = new Thread(() -> {
                        synchronized (lock) {
                            held.countDown();
                            // Hold on until the loop is blocked on the lock, then a gap's worth more: a
                            // fixed head start lost the race on a Windows runner, where a 20 ms sleep took
                            // 126 ms and the loop found the lock free.
                            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                            while (me.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                                Thread.onSpinWait();
                            }
                            sleep();
                        }
                        sleep();
                    }, "holder-cli");
                    holder.start();
                    if (!held.await(30, TimeUnit.SECONDS)) {
                        stuck.add("holder-cli never took the lock");
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    holder.join(Duration.ofSeconds(30));
                    if (holder.isAlive()) {
                        stuck.add("holder-cli never finished");
                    }
                    Loop.idle();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "loop-cli");
            loop.start();
            loop.join(Duration.ofSeconds(60));
            assertFalse(loop.isAlive(), "loop-cli never finished");
            allocator.join(Duration.ofSeconds(60));
            assertFalse(allocator.isAlive(), "alloc-cli never finished");
            assertTrue(stuck.isEmpty(), stuck.toString());
            // For health, once the threads the other commands judge are done: a collection a
            // heap this size would not otherwise need, and one throwable made at a known site.
            System.gc();
            assertNotNull(new IllegalStateException("made for health"));
            Thread.sleep(150);
            r.stop();
        }
    }

    static void sleep() {
        try {
            Thread.sleep(150);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Run(int status, String out, String err) {
    }

    /** Standard output that is gone: a closed descriptor, a full disk, a reader that quit. */
    static final class Broken extends OutputStream {
        @Override
        public void write(final int b) throws IOException {
            throw new IOException("Broken pipe");
        }
    }

    /** An error no reader should swallow or rename. */
    static final class Boom extends Error {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static Run run(final String... argv) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = new Main(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(argv);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theStandardStreamsAreUtf8OnEveryPlatform() {
        // Windows' default is a code page: JSON piped to a file must be UTF-8 all the same.
        assertEquals(StandardCharsets.UTF_8, Main.utf8(FileDescriptor.out).charset());
    }

    @Test
    void helpAndVersion() {
        assertEquals(0, run().status());
        assertTrue(run().out().contains("usage: jfrq"));
        assertTrue(run("--help").out().contains("stalls"));
        assertTrue(run("-h").out().contains("locks"));
        assertTrue(run("help").out().contains("alloc"));
        assertTrue(run("info", "--help").out().contains("usage: jfrq"));
        // -h after the command is help too, not a recording named "-h".
        final Run h = run("info", "-h");
        assertEquals(0, h.status(), h.err());
        assertTrue(h.out().contains("usage: jfrq"), h.out());
        assertEquals(0, run("stalls", recording.toString(), "-h").status());
        assertEquals("jfrq " + Main.VERSION + "\n", run("--version").out());
        assertEquals("jfrq " + Main.VERSION + "\n", run("info", recording.toString(), "--version").out());
    }

    @Test
    void usageErrorsExitWithTwo() {
        final Run unknown = run("frobnicate", "x.jfr");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("unknown command 'frobnicate'"));
        assertEquals(2, run("info").status());
        assertEquals(2, run("info", recording.toString(), "--bogus").status());
        assertEquals(2, run("stalls", recording.toString()).status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", " ").status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", "x", "--gap", "never").status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", "x", "--idle", "(").status());
        assertEquals(2, run("alloc", recording.toString(), "--top", "-1").status());
        assertEquals(2, run("locks", recording.toString(), "--min", "abc").status());
        assertEquals(2, run("locks", recording.toString(), "--thread", "").status());
        assertEquals(2, run("alloc", recording.toString(), "--sites", "--app", " ,").status());
        // Each command's grouping option belongs to it alone.
        assertEquals(2, run("locks", recording.toString(), "--app", "dev.jfrq").status());
        assertEquals(2, run("alloc", recording.toString(), "--by-site").status());
        // Options belong to their command, and durations need a unit.
        final Run misplaced = run("locks", recording.toString(), "--gap", "1s");
        assertEquals(2, misplaced.status());
        assertTrue(misplaced.err().contains("unknown option --gap"), misplaced.err());
        assertEquals(2, run("info", recording.toString(), "--top", "3").status());
        assertEquals(2, run("stalls", recording.toString(), "--thread", "x", "--baseline", "y").status());
        final Run bare = run("stalls", recording.toString(), "--thread", "x", "--gap", "50");
        assertEquals(2, bare.status());
        assertTrue(bare.err().contains("needs a unit"), bare.err());
        assertEquals(2, run("info", recording.toString(), "extra.jfr").status());
        final Run directory = run("info", dir.toString());
        assertEquals(2, directory.status());
        assertTrue(directory.err().contains("is a directory"), directory.err());

        // A quoted "50 " is a slip, not 50 of anything.
        final Run spaced = run("stalls", recording.toString(), "--thread", "x", "--gap", "50ms ");
        assertEquals(2, spaced.status());
        assertTrue(spaced.err().contains("spaces around it"), spaced.err());
        // A single-dash token is a mistyped option, not a recording that does not exist.
        final Run dash = run("info", "-x", recording.toString());
        assertEquals(2, dash.status());
        assertTrue(dash.err().contains("unknown option -x"), dash.err());
        // Which of two values was meant is a guess.
        final Run twice = run("alloc", recording.toString(), "--top", "3", "--top", "4");
        assertEquals(2, twice.status());
        assertTrue(twice.err().contains("--top given twice"), twice.err());
        // An empty --html is found before the analysis, not after the report is printed.
        final Run emptyHtml = run("stalls", recording.toString(), "--thread", "loop-*", "--html=");
        assertEquals(2, emptyHtml.status());
        assertTrue(emptyHtml.err().contains("--html needs a value"), emptyHtml.err());
        assertEquals("", emptyHtml.out());
    }

    @Test
    void theReportNeverReplacesARecordingBeingRead() throws Exception {
        final Path copy = dir.resolve("copy.jfr");
        Files.copy(recording, copy);
        final long size = Files.size(copy);
        final Run self = run("info", copy.toString(), "--html", copy.toString());
        assertEquals(2, self.status());
        assertTrue(self.err().contains("is a recording being read"), self.err());
        assertEquals(size, Files.size(copy));
        // The same file by another name is the same file.
        final Run dotted = run("locks", copy.toString(), "--html", dir.resolve(".").resolve("copy.jfr").toString());
        assertEquals(2, dotted.status());
        final Run baseline = run("alloc", recording.toString(), "--baseline", copy.toString(), "--html", copy.toString());
        assertEquals(2, baseline.status());
        assertEquals(size, Files.size(copy));
        assertEquals("", baseline.out());
    }

    @Test
    void aStandardOutputThatCannotBeWrittenIsAFailure() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = new Main(new PrintStream(new Broken(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(new String[] {"info", recording.toString()});
        assertEquals(1, status);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot write the report to standard output"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aQuestionIsCheckedWithoutItsRecording() throws Exception {
        final Path later = dir.resolve("not-yet.jfr");
        Main.check(new String[] {"stalls", "--thread", "x", "--gap", "10ms"}, later);
        Main.check(new String[] {"info"}, later);
        assertThrows(Args.UsageException.class, () -> Main.check(new String[] {"stalls"}, later));
        assertThrows(Args.UsageException.class, () -> Main.check(new String[] {"stalls", "--thread", "x", "--gap", "50"}, later));
        assertThrows(Args.UsageException.class, () -> Main.check(new String[] {"frobnicate"}, later));
        assertThrows(Args.UsageException.class, () -> Main.check(new String[] {"info", "extra"}, later));
        assertThrows(Args.UsageException.class, () -> Main.check(new String[] {"locks", "--html", later.toString()}, later));
        assertThrows(NoSuchFileException.class, () -> Main.check(new String[] {"alloc", "--baseline",
                dir.resolve("gone.jfr").toString()}, later));
        // The file about to be written cannot be the baseline: the dump would replace it before it is read.
        final Path t1 = dir.resolve("t1.jfr");
        Files.writeString(t1, "an earlier dump");
        final Args.UsageException overBaseline = assertThrows(Args.UsageException.class,
                () -> Main.check(new String[] {"alloc", "--baseline", t1.toString()}, t1));
        assertTrue(overBaseline.getMessage().contains("--baseline " + t1 + " is the file the dump is written to"),
                overBaseline.getMessage());
        assertThrows(Args.UsageException.class, () -> Main.check(new String[] {"alloc", "--baseline",
                dir.resolve(".").resolve("t1.jfr").toString()}, t1));
        Main.check(new String[] {"alloc", "--baseline", t1.toString()}, later);
    }

    @Test
    void aFailedDiffNamesTheFileAndKeepsBothFailures() throws Exception {
        final Path a = dir.resolve("junk-a.jfr");
        final Path b = dir.resolve("junk-b.jfr");
        Files.writeString(a, "definitely not a recording, but long enough to have a header");
        Files.writeString(b, "not a recording either, and also long enough to have a header");
        final IOException both = assertThrows(IOException.class, () -> Main.readBoth(a, new Nothing(), b, new Nothing()));
        assertTrue(both.getMessage().startsWith(a + ": "), both.getMessage());
        assertEquals(1, both.getSuppressed().length);
        assertTrue(both.getSuppressed()[0].getMessage().startsWith(b + ": "), both.getSuppressed()[0].getMessage());
        // An Error is not a damaged file: it leaves as itself.
        assertThrows(Boom.class, () -> Main.readBoth(recording, new Exploding(), recording, new Nothing()));

        final Run cli = run("alloc", recording.toString(), "--baseline", b.toString());
        assertEquals(1, cli.status());
        assertTrue(cli.err().contains(b.toString()), cli.err());
    }

    /** A sink that wants nothing: the read is all that is being tested. */
    static class Nothing implements JfrReader.Sink {
        @Override
        public Set<String> eventTypes() {
            return Set.of("jdk.ThreadSleep");
        }

        @Override
        public void accept(@Transient final RecordedEvent event) {
        }
    }

    static final class Exploding extends Nothing {
        @Override
        public void begin(final Interner interner) {
            throw new Boom();
        }
    }

    @Test
    void damagedRecordingsExitWithOneAndSayWhy() throws Exception {
        final byte[] bytes = Files.readAllBytes(recording);
        final Path cut = dir.resolve("cut.jfr");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length / 2));
        final Run r = run("stalls", cut.toString(), "--thread", "loop-*");
        assertEquals(1, r.status(), r.out());
        assertTrue(r.err().contains("truncated"), r.err());
        assertFalse(r.out().contains("No thread matched"), r.out());

        final Path junk = dir.resolve("junk.jfr");
        Files.writeString(junk, "definitely not a recording, but long enough to have a header");
        final Run j = run("info", junk.toString());
        assertEquals(1, j.status());
        assertTrue(j.err().contains("not a Flight Recorder file"), j.err());

        ByteBuffer.wrap(bytes).putLong(8, 0);
        final Path live = dir.resolve("live.jfr");
        Files.write(live, bytes);
        final Run l = run("alloc", live.toString());
        assertEquals(1, l.status());
        assertTrue(l.err().contains("still being written"), l.err());
    }

    @Test
    void unwritableHtmlTargetIsReportedAsSuch() throws Exception {
        final Run r = run("info", recording.toString());
        assertEquals(0, r.status());
        // Found before the analysis: nothing on standard output.
        final Run bad = run("locks", recording.toString(), "--html", dir.resolve("no-such-dir").resolve("x.html").toString());
        assertEquals(2, bad.status());
        assertTrue(bad.err().contains("no such directory"), bad.err());
        assertEquals("", bad.out());
        assertEquals(2, run("locks", recording.toString(), "--html", dir.toString()).status());
        // A file that refuses the write fails at the write, and says it was the report.
        final Path readOnly = dir.resolve("read-only.html");
        Files.writeString(readOnly, "");
        assertTrue(readOnly.toFile().setWritable(false));
        try {
            final Run refused = run("info", recording.toString(), "--html", readOnly.toString());
            assertEquals(1, refused.status());
            assertTrue(refused.err().contains("cannot write HTML report " + readOnly + ": permission denied"), refused.err());
            assertFalse(refused.err().contains("cannot read recording"), refused.err());
        } finally {
            assertTrue(readOnly.toFile().setWritable(true));
        }
    }

    @Test
    void missingRecordingExitsWithOne() {
        final Run r = run("info", dir.resolve("missing.jfr").toString());
        assertEquals(1, r.status());
        assertTrue(r.err().contains("no such file or directory: " + dir.resolve("missing.jfr")), r.err());
        assertEquals(1, run("alloc", recording.toString(), "--baseline", "nope.jfr").status());
    }

    @Test
    void timingGoesToStderr() {
        final Run r = run("stalls", recording.toString(), "--thread", "*", "--timing");
        assertEquals(0, r.status(), r.err());
        // Parsing and analysing are separate numbers: the analysis runs inside the read,
        // in the sink's finish(), and one number could not say which of the two was slow.
        assertTrue(r.err().contains("timing: parse"), r.err());
        assertTrue(r.err().contains("timing: analyse"), r.err());
        assertTrue(r.err().contains("timing: render"), r.err());
        assertFalse(r.out().contains("timing:"));
    }

    @Test
    void info() throws Exception {
        final Path html = dir.resolve("info.html");
        final Run withHtml = run("info", recording.toString(), "--html", html.toString());
        assertEquals(0, withHtml.status(), withHtml.err());
        assertTrue(Files.readString(html).contains("<title>jfrq info"));
        assertTrue(Files.readString(html).contains("jdk.ThreadSleep"));
        final Run r = run("info", recording.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("Recording  cli.jfr"));
        assertTrue(r.out().contains("Chunks     1"), r.out());
        assertTrue(r.out().contains("Sampling   ExecutionSample 10.0 ms"));
        assertTrue(r.out().contains("jdk.ThreadSleep"));
        assertTrue(r.out().contains("Event type"));
    }

    @Test
    void everyCommandAnswersInJsonWithTheNumbersTheTextHas() throws Exception {
        final Map<String, Object> info = JsonParser.object(json("info", recording.toString()));
        assertEquals("info", info.get("command"));
        assertEquals("cli.jfr", map(info, "recording").get("file"));

        final Run stallsText = run("stalls", recording.toString(), "--thread", "loop-*");
        final Map<String, Object> stalls = JsonParser.object(json("stalls", recording.toString(), "--thread", "loop-*"));
        assertTrue(stallsText.out().contains("STALLS >= 50.0 ms: " + stalls.get("stallsFound") + " found"),
                stallsText.out() + stalls);
        assertEquals(1L, stalls.get("threadsMatched"));

        final Map<String, Object> locks = JsonParser.object(json("locks", recording.toString()));
        final String locksText = run("locks", recording.toString()).out();
        assertTrue(locksText.contains("across " + locks.get("waits") + " wait"), locksText + locks);
        JsonParser.object(json("locks", recording.toString(), "--by-site"));

        final Map<String, Object> alloc = JsonParser.object(json("alloc", recording.toString(), "--sites"));
        assertEquals("alloc", alloc.get("command"));
        assertTrue(alloc.containsKey("sites"));
        final Map<String, Object> diff = JsonParser.object(json("alloc", recording.toString(), "--baseline",
                recording.toString()));
        assertEquals(0L, map(diff, "change").get("bytesPerSecondChange"));

        final Map<String, Object> health = JsonParser.object(json("health", recording.toString()));
        final String healthText = run("health", recording.toString()).out();
        assertTrue(healthText.contains("Collections  " + map(health, "gc").get("collections") + " ("),
                healthText + health);
    }

    @Test
    void health() throws Exception {
        final Path html = dir.resolve("health.html");
        final Run r = run("health", recording.toString(), "--top", "2", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        for (final String section : List.of("FINDINGS", "GC", "TRENDS", "THROWABLES CREATED")) {
            assertTrue(r.out().contains("\n" + section), section + " in " + r.out());
        }
        assertTrue(r.out().contains("caused by System.gc()"), r.out());
        assertTrue(r.out().contains("java.lang.IllegalStateException"), r.out());
        assertTrue(Files.readString(html).contains("<title>jfrq health"));
        assertEquals(2, run("health", recording.toString(), "--sites").status());
        // The native memory rows are --top categories and the total: + 1 must not overflow.
        final String max = String.valueOf(Integer.MAX_VALUE);
        assertEquals(0, run("health", recording.toString(), "--top", max).status());
        assertEquals(0, run("health", recording.toString(), "--top", max, "--json").status());
        assertEquals(0, run("health", recording.toString(), "--top", max, "--html", html.toString()).status());
    }

    /** The command's standard output with --json appended, which must be the JSON document alone. */
    private String json(final String... argv) {
        final String[] withJson = Arrays.copyOf(argv, argv.length + 1);
        withJson[argv.length] = "--json";
        final Run r = run(withJson);
        assertEquals(0, r.status(), r.err());
        return r.out();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Map<String, Object> doc, final String name) {
        return (Map<String, Object>) doc.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Map<String, Object> doc, final String name) {
        return (List<Object>) doc.get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void alloc() throws Exception {
        final Path html = dir.resolve("alloc.html");
        final Run r = run("alloc", recording.toString(), "--top", "3", "--sites", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("BY THREAD"));
        assertTrue(r.out().contains("alloc-cli"));
        assertTrue(r.out().contains("BY CLASS"));
        assertTrue(r.out().contains("byte[]"));
        assertTrue(r.out().contains("BY SITE"));
        assertTrue(r.err().contains("HTML report written"));
        assertTrue(Files.readString(html).contains("<title>jfrq alloc"));

        final Run plain = run("alloc", recording.toString());
        assertFalse(plain.out().contains("BY SITE"));

        // --app attributes each stack to the test's own frames instead of to the JDK method
        // that allocated, so the rows are named after this file rather than after the JDK.
        final Run app = run("alloc", recording.toString(), "--sites", "--app", "dev.jfrq");
        assertEquals(0, app.status(), app.err());
        assertTrue(app.out().contains("dev.jfrq."), app.out());
    }

    @Test
    void locksBySite() {
        final Run r = run("locks", recording.toString(), "--by-site");
        assertEquals(0, r.status(), r.err());
        // The fixture's loop thread waited on holder-cli's monitor, so there is a site to rank.
        assertTrue(r.out().contains("\nLOCK SITES BY TOTAL WAIT ("), r.out());
        assertTrue(r.out().contains("loop-cli"), r.out());
        assertFalse(r.out().contains("\nLOCKS BY TOTAL WAIT\n"), r.out());
    }

    @Test
    void allocDiff() throws Exception {
        final Path html = dir.resolve("diff.html");
        final Run r = run("alloc", recording.toString(), "--baseline", recording.toString(), "--sites", "--html",
                html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("Baseline   cli.jfr"));
        // A recording against itself: every site is unchanged, and both sides carry the same
        // samples, which is what says the row is a real comparison and not one side only.
        assertTrue(r.out().contains("BY SITE ("), r.out());
        // --app groups the diff the same way it groups a single report.
        final Run byApp = run("alloc", recording.toString(), "--baseline", recording.toString(), "--app", "dev.jfrq");
        assertEquals(0, byApp.status(), byApp.err());
        assertTrue(byApp.out().contains("dev.jfrq."), byApp.out());
        assertFalse(r.out().contains("WARNING"), r.out());

        // A damaged baseline is said so on the diff, text and HTML.
        final byte[] bytes = Files.readAllBytes(recording);
        final Path cut = dir.resolve("cut-baseline.jfr");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length + 20));
        final Path cutHtml = dir.resolve("cut-diff.html");
        final Run damaged = run("alloc", recording.toString(), "--baseline", cut.toString(), "--html", cutHtml.toString());
        assertEquals(0, damaged.status(), damaged.err());
        assertTrue(damaged.out().contains("WARNING    baseline: the file is truncated"), damaged.out());
        assertTrue(Files.readString(cutHtml).contains("baseline: the file is truncated"));
        assertTrue(r.out().contains("Change     0 B/s (+0%)"), r.out());
        assertTrue(r.out().contains("BY SITE"));
        assertTrue(Files.readString(html).contains("<title>jfrq alloc diff"));
    }

    @Test
    void locks() throws Exception {
        final Path html = dir.resolve("locks.html");
        final Run r = run("locks", recording.toString(), "--top", "5", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("LOCKS BY TOTAL WAIT"), r.out());
        assertTrue(r.out().contains("java.lang.Object@"), r.out());
        assertTrue(r.out().contains("loop-cli"), r.out());
        assertTrue(r.out().contains("held by holder-cli"), r.out());
        assertTrue(r.out().contains("LONGEST WAITS"));
        assertTrue(Files.readString(html).contains("Locks by total wait"));

        final Run filtered = run("locks", recording.toString(), "--thread", "nobody-*", "--min", "1ms");
        assertEquals(0, filtered.status());
        assertTrue(filtered.out().contains("No contended monitor enters or parks"));
        // The documented default is accepted explicitly.
        final Run zero = run("locks", recording.toString(), "--min", "0");
        assertEquals(0, zero.status(), zero.err());
        assertTrue(zero.out().contains("held by holder-cli"), zero.out());
    }

    @Test
    void stalls() throws Exception {
        final Path html = dir.resolve("stalls.html");
        final Run r = run("stalls", recording.toString(), "--thread", "loop-*", "--gap", "50ms",
                "--idle", ".*MainTest\\$Loop\\.idle", "--top", "10", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("Threads    1 matched"), r.out());
        // The names, samples and cadence are the PER THREAD table's job, not a header line's.
        assertTrue(r.out().contains("PER THREAD (most stalled first; cadence"), r.out());
        // Summary first: the totals and who stalled come before the evidence.
        assertTrue(r.out().indexOf("BY VERDICT") < r.out().indexOf("PER THREAD"), r.out());
        assertTrue(r.out().indexOf("PER THREAD") < r.out().indexOf("STALLS >="), r.out());
        assertTrue(r.out().contains("Native cadence"), r.out());
        assertTrue(r.out().contains("loop-cli"), r.out());
        assertTrue(r.out().contains("SLEEP"), r.out());
        assertTrue(r.out().contains("BLOCKED_MONITOR"), r.out());
        // Event-based stalls carry no evidence tag; the less exact kinds do.
        assertFalse(r.out().contains("Thread.sleep ["), r.out());
        final ThreadRef t = new ThreadRef(1, "t");
        final Interval i = new Interval(0, 1);
        assertEquals("", Text.evidence(new Stall(t, i, Stall.Verdict.SLEEP, "",
                Stack.EMPTY, Stall.Evidence.EVENT, 0)));
        assertEquals(" [samples]", Text.evidence(new Stall(t, i, Stall.Verdict.BUSY,
                "", Stack.EMPTY, Stall.Evidence.SAMPLES, 3)));
        assertEquals(" [silence]", Text.evidence(new Stall(t, i,
                Stall.Verdict.UNEXPLAINED, "", Stack.EMPTY,
                Stall.Evidence.SILENCE, 0)));
        assertTrue(r.out().contains("held by holder-cli"), r.out());
        assertTrue(r.out().contains("BY VERDICT"));
        assertTrue(r.out().contains("PER THREAD"));
        assertTrue(Files.readString(html).contains("<svg"));

        final Run none = run("stalls", recording.toString(), "--thread", "nobody");
        assertEquals(0, none.status());
        assertTrue(none.out().contains("No thread matched"));

        final Run defaults = run("stalls", recording.toString(), "--thread", "loop-cli");
        assertEquals(0, defaults.status());
        assertTrue(defaults.out().contains("Gap        50.0 ms"));
    }

    /** A recording of three threads one spawner starts, with their starts and CPU readings. */
    static Path spawnRecording() throws Exception {
        final Path file = dir.resolve("spawn.jfr");
        if (Files.exists(file)) {
            return file;
        }
        try (final Recording r = new Recording()) {
            r.enable("jdk.ActiveSetting");
            r.enable("jdk.ThreadStart").withStackTrace();
            r.enable("jdk.ThreadEnd");
            r.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(50));
            r.setDestination(file);
            r.start();
            final Thread spawner = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    final Thread t = new Thread(() -> {
                        final long end = System.nanoTime() + 150 * 1_000_000L;
                        while (System.nanoTime() < end) {
                            Thread.onSpinWait();
                        }
                    }, "spawned-" + i);
                    t.start();
                    try {
                        t.join(Duration.ofSeconds(30));
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "spawner");
            spawner.start();
            spawner.join(Duration.ofSeconds(60));
            assertFalse(spawner.isAlive(), "spawner never finished");
            r.stop();
        }
        return file;
    }

    @Test
    void infoThreadSaysHowTheMatchingThreadsWereStartedAndByWhom() throws Exception {
        final Path file = spawnRecording();
        final Path html = dir.resolve("spawn.html");
        final Run r = run("info", file.toString(), "--thread", "spawned-*", "--top", "1", "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("\nSTARTS OF spawned-*\n"), r.out());
        assertTrue(r.out().contains("Starts   3 starts of 3 threads, from +"), r.out());
        assertTrue(r.out().contains("Busiest  "), r.out());
        assertTrue(r.out().contains("CREATED BY ("), r.out());
        assertTrue(r.out().contains("started from spawner"), r.out());
        assertTrue(r.out().contains("MainTest.lambda$spawnRecording"), r.out());
        // The families table has the threads' CPU, and says what the column leaves out.
        assertTrue(r.out().contains("  CPU: the share of the JVM's CPUs"), r.out());
        final String page = Files.readString(html);
        assertTrue(page.contains("Starts of spawned-*"), page);
        assertTrue(page.contains("started it"), page);

        final Map<String, Object> doc = JsonParser.object(json("info", file.toString(), "--thread", "spawned-*"));
        final Map<String, Object> starts = map(doc, "starts");
        assertEquals(3L, starts.get("starts"));
        assertEquals(0L, starts.get("attached"));
        final List<Object> creators = list(starts, "creators");
        assertEquals(1, creators.size());
        assertEquals(List.of("spawner"), object(creators.getFirst()).get("parents"));
        assertEquals(2, list(starts, "peaks").size());
        assertTrue(map(doc, "threadCpu").containsKey("share"));
        // Without --thread the field is there, and null.
        assertTrue(JsonParser.object(json("info", file.toString())).containsKey("starts"));
        assertEquals(null, JsonParser.object(json("info", file.toString())).get("starts"));

        final Run none = run("info", file.toString(), "--thread", "nobody");
        assertEquals(0, none.status());
        assertTrue(none.out().contains("no jdk.ThreadStart of a thread matching nobody in the recording"), none.out());
        assertEquals(2, run("info", file.toString(), "--thread", " ").status());
        final Run topAlone = run("info", file.toString(), "--top", "3");
        assertEquals(2, topAlone.status());
        assertTrue(topAlone.err().contains("info takes --top only with --thread"), topAlone.err());
    }

    @Test
    void healthOfSeveralRecordingsIsOneTableInTheOrderGiven() throws Exception {
        final Path other = spawnRecording();
        final Path html = dir.resolve("compared.html");
        final Run r = run("health", recording.toString(), other.toString(), "--html", html.toString());
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().startsWith("HEALTH OF 2 RECORDINGS ("), r.out());
        assertTrue(r.out().indexOf("  cli.jfr ") < r.out().indexOf("  spawn.jfr "), r.out());
        for (final String column : List.of("Recording", "Heap after GC, floors", "Java threads CPU", "Throwables/s")) {
            assertTrue(r.out().contains(column), column + " in " + r.out());
        }
        // Each recording's findings under its name: the fixture's System.gc() is one.
        assertTrue(r.out().contains("\ncli.jfr\n"), r.out());
        assertTrue(r.out().contains("caused by System.gc()"), r.out());
        assertTrue(r.out().contains("\nspawn.jfr\n  no findings\n"), r.out());
        assertTrue(Files.readString(html).contains("<title>jfrq health of 2 recordings"));

        final Map<String, Object> doc = JsonParser.object(json("health", recording.toString(), other.toString(),
                "--top", "1"));
        assertEquals("health", doc.get("command"));
        final List<Object> reports = list(doc, "reports");
        assertEquals(2, reports.size());
        assertEquals("cli.jfr", map(object(reports.get(0)), "recording").get("file"));
        assertEquals("spawn.jfr", map(object(reports.get(1)), "recording").get("file"));
        assertTrue(object(reports.get(0)).containsKey("findings"));

        // The second recording is checked like the first, and protected like it.
        assertEquals(1, run("health", recording.toString(), dir.resolve("gone.jfr").toString()).status());
        final Run over = run("health", recording.toString(), other.toString(), "--html", other.toString());
        assertEquals(2, over.status());
        assertTrue(over.err().contains("is a recording being read"), over.err());
        assertEquals(2, run("locks", recording.toString(), other.toString()).status());
        final Run twice = run("health", recording.toString(), dir.resolve(".").resolve("cli.jfr").toString());
        assertEquals(2, twice.status());
        assertTrue(twice.err().contains("is given twice"), twice.err());
        // Twice among the others, not only the first again.
        final Run twiceAfter = run("health", recording.toString(), other.toString(), other.toString());
        assertEquals(2, twiceAfter.status());
        assertTrue(twiceAfter.err().contains(other + " is given twice"), twiceAfter.err());
        // Under jfrq-live, a recording that is the dump's own target is that, whether or not it exists yet.
        final Path t2 = dir.resolve("t2.jfr");
        Files.writeString(t2, "an earlier dump");
        final Path t3 = dir.resolve("t3-not-yet.jfr");
        for (final Path target : List.of(t2, t3)) {
            final Args.UsageException dumped = assertThrows(Args.UsageException.class,
                    () -> Main.check(new String[] {"health", target.toString()}, target));
            assertTrue(dumped.getMessage().contains("is the file the dump is written to"), dumped.getMessage());
        }
        Main.check(new String[] {"health", recording.toString()}, t2);
    }

    @Test
    void aDirectoryOfChunksIsPointedAtJfrAssemble() throws Exception {
        final Path repository = Files.createDirectories(dir.resolve("2026_10_01_12_00_00_4242"));
        Files.copy(recording, repository.resolve("2026_10_01_12_00_00.jfr"));
        final Run r = run("health", repository.toString());
        assertEquals(2, r.status());
        // One chunk: the one the JVM was writing, which nothing can read.
        assertTrue(r.err().contains("its newest chunk is the one it was writing and cannot be read, and it holds no "
                + "other"), r.err());
        Files.copy(recording, repository.resolve("2026_10_01_12_05_00.jfr"));
        final Run two = run("health", repository.toString());
        assertTrue(two.err().contains("join the 2 chunks with jfr assemble " + repository + " out.jfr"), two.err());
        // The repository a recording was told to use holds one such directory per JVM.
        final Path configured = Files.createDirectories(dir.resolve("repo"));
        final Path older = Files.createDirectories(configured.resolve("2026_10_01_11_00_00_4241"));
        Files.copy(recording, older.resolve("2026_10_01_11_00_00.jfr"));
        Files.createDirectories(configured.resolve("2026_10_01_12_00_00_4242"));
        Files.copy(recording, configured.resolve("2026_10_01_12_00_00_4242").resolve("2026_10_01_12_00_00.jfr"));
        final Run nested = run("info", configured.toString());
        assertEquals(2, nested.status());
        assertTrue(nested.err().contains("and it holds no other (it holds 2 JVMs' directories; that is the last by "
                + "name)"), nested.err());
        Files.copy(recording, older.resolve("2026_10_01_11_05_00.jfr"));
        final Run olderOne = run("info", older.toString());
        assertTrue(olderOne.err().contains("jfr assemble " + older + " out.jfr"), olderOne.err());
        // A directory of finished recordings, or of run directories, is not one recording's chunks.
        final Path runs = Files.createDirectories(dir.resolve("runs").resolve("135510"));
        Files.copy(recording, runs.resolve("n2.jfr"));
        for (final Path notChunks : List.of(runs, runs.getParent())) {
            final Run r2 = run("health", notChunks.toString());
            assertEquals(2, r2.status());
            assertFalse(r2.err().contains("jfr assemble"), r2.err());
        }
        final Path empty = Files.createDirectories(dir.resolve("empty-dir"));
        final Run plain = run("info", empty.toString());
        assertEquals(2, plain.status());
        assertFalse(plain.err().contains("jfr assemble"), plain.err());
        // A file named like a JVM's directory is not one.
        final Path named = Files.createDirectories(dir.resolve("named"));
        Files.writeString(named.resolve("2026_10_01_12_00_00_4242"), "not a directory");
        assertFalse(run("health", named.toString()).err().contains("jfr assemble"));
    }

    @Test
    void anUnreadableDirectoryGetsThePlainMessage() throws Exception {
        final Path locked = Files.createDirectories(dir.resolve("locked").resolve("2026_10_01_13_00_00_4243"));
        Files.copy(recording, locked.resolve("2026_10_01_13_00_00.jfr"));
        final Path parent = Files.createDirectories(dir.resolve("locked-parent"));
        Assumptions.assumeTrue(locked.toFile().setReadable(false) && parent.toFile().setReadable(false),
                "the file system cannot take read permission away");
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "running with permission to read everything");
            for (final Path p : List.of(locked, parent)) {
                final Run r = run("info", p.toString());
                assertEquals(2, r.status());
                assertTrue(r.err().contains(p + " is a directory, not a recording\n"), r.err());
            }
        } finally {
            locked.toFile().setReadable(true);
            parent.toFile().setReadable(true);
        }
    }

    @Test
    void readingSeveralRecordingsNamesEveryFileThatFailed() throws Exception {
        final Path a = dir.resolve("junk-c.jfr");
        Files.writeString(a, "definitely not a recording, but long enough to have a header");
        final IOException failed = assertThrows(IOException.class, () -> Main.readAll(List.of(recording, a, a),
                List.of(new Nothing(), new Nothing(), new Nothing())));
        assertTrue(failed.getMessage().startsWith(a + ": "), failed.getMessage());
        assertEquals(1, failed.getSuppressed().length);
    }
}
