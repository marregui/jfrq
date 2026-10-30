// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jdk.jfr.Recording;
import jdk.management.jfr.FlightRecorderMXBean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.cli.Args;
import dev.jfrq.cli.Main;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop against the JVM the tests run in: the test task sets
 * {@code -Djdk.attach.allowAttachSelf=true}, and everything else is what a user does to
 * another process. The recorder is one per JVM, so the loop test names its recording and
 * addresses it with {@code --recording} in case another test's is running.
 */
class LiveTest {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final String PID = Long.toString(ProcessHandle.current().pid());

    @TempDir
    Path dir;

    record Run(int status, String out, String err) {
    }

    static Run run(final String... argv) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = new Live(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(argv);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aSlowAttachIsAnnouncedOnceAndAFastOrFailedOneNever() throws Exception {
        final ByteArrayOutputStream slow = new ByteArrayOutputStream();
        assertEquals("jvm", Jvm.noticeIfSlow(() -> {
            pause(500);
            return "jvm";
        }, 20, new PrintStream(slow, true, StandardCharsets.UTF_8), "attaching\n"));
        assertEquals("attaching\n", slow.toString(StandardCharsets.UTF_8));

        // Settled before the notice was due, and it stays unsaid after: nothing lands among what
        // the caller prints next.
        final ByteArrayOutputStream fast = new ByteArrayOutputStream();
        assertEquals("jvm", Jvm.noticeIfSlow(() -> "jvm", 50, new PrintStream(fast, true, StandardCharsets.UTF_8),
                "attaching\n"));
        final ByteArrayOutputStream failed = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> Jvm.noticeIfSlow(() -> {
            throw new IOException("no such process");
        }, 50, new PrintStream(failed, true, StandardCharsets.UTF_8), "attaching\n"));
        pause(300);
        assertEquals("", fast.toString(StandardCharsets.UTF_8));
        assertEquals("", failed.toString(StandardCharsets.UTF_8));
    }

    private static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void helpAndVersion() {
        assertEquals(0, run().status());
        assertTrue(run().out().contains("usage: jfrq-live"));
        assertTrue(run("--help").out().contains("delta"));
        assertTrue(run("-h").out().contains("again"));
        assertTrue(run("help").out().contains("bound"));
        assertTrue(run(PID, "status", "--help").out().contains("usage: jfrq-live"));
        assertEquals("jfrq-live " + Main.VERSION + "\n", run("--version").out());
        assertEquals("jfrq-live " + Main.VERSION + "\n", run(PID, "status", "--version").out());
    }

    @Test
    void usageErrorsExitWithTwoBeforeAttaching() {
        final Run pid = run("not-a-pid", "status");
        assertEquals(2, pid.status());
        assertTrue(pid.err().contains("pid"));
        assertEquals(2, run(PID).status());
        final Run unknown = run(PID, "frobnicate");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("unknown command 'frobnicate'"));
        assertEquals(2, run(PID, "status", "--out", "x.jfr").status());
        assertEquals(2, run(PID, "full", "--max-age", "1m").status());
        assertEquals(2, run(PID, "full", "extra").status());
        assertEquals(2, run(PID, "status", "--", "info").status());
        assertEquals(2, run(PID, "full", "--").status());
        assertEquals(2, run(PID, "bound").status());
        assertEquals(2, run(PID, "start", "--max-size", "lots").status());
        // The separator where the command should be is a missing command, not a JVM refusal.
        final Run noCommand = run(PID, "--", "stalls");
        assertEquals(2, noCommand.status());
        assertTrue(noCommand.err().contains("missing command after the pid"), noCommand.err());
        // JFR keeps max-age in whole seconds: 500ms would go out as "no bound".
        final Run subSecond = run(PID, "start", "--max-age", "500ms");
        assertEquals(2, subSecond.status());
        assertTrue(subSecond.err().contains("whole seconds"), subSecond.err());
        // Rounded up, the last fraction of a second of the longest duration would overflow.
        final Run tooLong = run(PID, "start", "--max-age", "9223372036.5s");
        assertEquals(2, tooLong.status());
        assertTrue(tooLong.err().contains("the JVM keeps at most 9223372036s"), tooLong.err());
        assertEquals(2, run(PID, "full", "-x").status());
        assertEquals(2, run(PID, "full", "--out", "a.jfr", "--out", "b.jfr").status());
        assertEquals(2, run("99999999999999999999", "status").status());
    }

    @Test
    void theQuestionIsCheckedBeforeTheDump() throws Exception {
        final Path file = dir.resolve("never.jfr");
        final Path state = dir.resolve("state");
        final Run noThread = run(PID, "full", "--out", file.toString(), "--state", state.toString(), "--", "stalls");
        assertEquals(2, noThread.status());
        assertTrue(noThread.err().contains("stalls needs --thread"), noThread.err());
        final Run badGap = run(PID, "delta", "--out", file.toString(), "--state", state.toString(), "--", "stalls",
                "--thread", "x", "--gap", "50");
        assertEquals(2, badGap.status());
        final Run overDump = run(PID, "full", "--out", file.toString(), "--state", state.toString(), "--", "info",
                "--html", file.toString());
        assertEquals(2, overDump.status());
        assertTrue(overDump.err().contains("is a recording being read"), overDump.err());
        final Run noBaseline = run(PID, "full", "--out", file.toString(), "--state", state.toString(), "--", "alloc",
                "--baseline", dir.resolve("t0.jfr").toString());
        assertEquals(1, noBaseline.status());
        assertTrue(noBaseline.err().contains("no such file or directory: " + dir.resolve("t0.jfr")), noBaseline.err());
        // A dump written over its own baseline would replace the file it is compared with.
        final Path t1 = dir.resolve("t1.jfr");
        Files.writeString(t1, "an earlier dump");
        final Run overBaseline = run(PID, "delta", "--out", t1.toString(), "--state", state.toString(), "--", "alloc",
                "--baseline", t1.toString());
        assertEquals(2, overBaseline.status());
        assertTrue(overBaseline.err().contains("is the file the dump is written to"), overBaseline.err());
        assertEquals("an earlier dump", Files.readString(t1));
        // Nothing was dumped and no cursor was written.
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(state));
    }

    @Test
    void theDefaultDumpNameIsUniqueToTheMillisecond() {
        final Args none = Args.parse(new String[0], Set.of("out"), Set.of());
        final String name = Live.dumpFile(none, "4242", "delta").toString();
        assertTrue(name.matches("4242-delta-\\d{6}\\.\\d{3}Z\\.jfr"), name);
        final Args named = Args.parse(new String[] {"--out", "t1.jfr"}, Set.of("out"), Set.of());
        assertEquals(Path.of("t1.jfr"), Live.dumpFile(named, "4242", "delta"));
    }

    @Test
    void theSpanCheckSaysWhyTheFileStartsLate() {
        final Instant begin = Instant.parse("2026-09-24T10:00:00Z");
        final Instant end = begin.plusSeconds(60);
        final Window window = new Window(begin, null);
        assertEquals(List.of(), Live.span(window, begin, end, begin.minusSeconds(600).toEpochMilli(), "max-age 5m00s"));
        // An 'again' of a full dump whose first chunks have aged out since.
        final List<String> aged = Live.span(window, begin.plusSeconds(20), end, begin.minusSeconds(600).toEpochMilli(),
                "max-age 5m00s");
        assertEquals(1, aged.size());
        assertTrue(aged.getFirst().contains("20.0 s after the window: the JVM had already discarded that data (max-age 5m00s)"),
                aged.getFirst());
        // A delta whose cursor predates the recording: it was stopped and started again.
        final List<String> restarted = Live.span(window, begin.plusSeconds(20), end, begin.plusSeconds(19).toEpochMilli(),
                "no bound");
        assertEquals(1, restarted.size());
        assertTrue(restarted.getFirst().contains("the recording started at " + TIME.format(begin.plusSeconds(19))),
                restarted.getFirst());
        assertFalse(restarted.getFirst().contains("discarded"), restarted.getFirst());
        assertTrue(Live.span(window, begin.minusSeconds(5), end, 0, "no bound").getFirst().contains("before the window"));
        assertTrue(Live.span(new Window(begin, end.minusSeconds(5)), begin, end, 0, "no bound").getFirst()
                .contains("after the window: the JVM hands over whole chunks"));
    }

    @Test
    void failuresFromTheConnectionAreMessagesNotTraces() {
        assertEquals("the connection to the JVM failed: Connection refused",
                Live.failure(new UndeclaredThrowableException(new IOException("Connection refused"))));
        assertEquals("failed: java.lang.UnsupportedOperationException: no",
                Live.failure(new UnsupportedOperationException("no")));
        // A connection that will not close after a good answer is a warning, not a failure.
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Live live = new Live(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        live.release(() -> {
            throw new IOException("reset by peer");
        }, "4242");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("warning: the connection to JVM 4242 did not close cleanly: reset by peer"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anInterruptedDumpLeavesNothingBehind() throws Exception {
        final FlightRecorderMXBean fr = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
        try (final Recording r = new Recording()) {
            r.setName("live-cleanup-" + System.nanoTime());
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            r.start();
            Thread.sleep(20);
            final long clone = fr.cloneRecording(r.getId(), true);
            try {
                final Snapshot.Cleanup cleanup = new Snapshot.Cleanup(fr, PID);
                cleanup.cloned(clone);
                final Path part = cleanup.temporary(dir.resolve("d.jfr"));
                assertTrue(Files.exists(part));
                // What the shutdown hook runs on Ctrl-C or SIGTERM; the JVM answers, so it says nothing.
                final ByteArrayOutputStream err = new ByteArrayOutputStream();
                cleanup.abandon(new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofSeconds(30));
                assertEquals("", err.toString(StandardCharsets.UTF_8));
                assertFalse(Files.exists(part));
                assertTrue(fr.getRecordings().stream().noneMatch(i -> i.getId() == clone), "the clone is still in the JVM");
                // A dump that reaches its file after the hook ran does not create one.
                assertThrows(IOException.class, () -> cleanup.temporary(dir.resolve("d.jfr")));
                try (final var files = Files.list(dir)) {
                    assertEquals(0, files.count());
                }
            } finally {
                if (fr.getRecordings().stream().anyMatch(i -> i.getId() == clone)) {
                    fr.closeRecording(clone);
                }
            }
            r.stop();
        }
        // A cleanup step that fails is suppressed into the failure that started the cleanup.
        final IOException primary = new IOException("the dump failed");
        final Snapshot.Cleanup unknown = new Snapshot.Cleanup(fr, PID);
        assertThrows(IOException.class, () -> {
            unknown.close(null);
            unknown.cloned(Long.MAX_VALUE);
        });
        assertEquals(primary, unknown.close(primary));
        assertEquals(1, primary.getSuppressed().length);
        final Snapshot.Cleanup again = new Snapshot.Cleanup(fr, PID);
        again.cloned(Long.MAX_VALUE);
        assertTrue(again.close(null) instanceof IllegalArgumentException);
        // Closed once, it has nothing left to close.
        assertNull(again.close(null));
    }

    @Test
    void aJvmThatDoesNotAnswerCannotHoldTheProcess() throws Exception {
        // A target stopped with SIGSTOP: the JMX call to close the clone never returns.
        final CountDownLatch never = new CountDownLatch(1);
        final FlightRecorderMXBean wedged = (FlightRecorderMXBean) Proxy.newProxyInstance(
                FlightRecorderMXBean.class.getClassLoader(), new Class<?>[] {FlightRecorderMXBean.class},
                (proxy, method, args) -> {
                    never.await();
                    return null;
                });
        try {
            final Snapshot.Cleanup cleanup = new Snapshot.Cleanup(wedged, "4242");
            cleanup.cloned(17);
            final Path part = cleanup.temporary(dir.resolve("d.jfr"));
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            final long start = System.nanoTime();
            cleanup.abandon(new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofMillis(200));
            final long took = System.nanoTime() - start;
            assertTrue(took < TimeUnit.SECONDS.toNanos(10), "the hook waited " + took + " ns");
            // The partial file goes whatever the JVM does; the clone it keeps is named.
            assertFalse(Files.exists(part));
            assertEquals("jfrq-live: JVM 4242 did not answer in 200 ms; the dump's recording clone 17 is still in it:"
                    + " 'jfrq-live 4242 stop --recording 17' closes it\n", err.toString(StandardCharsets.UTF_8));
        } finally {
            never.countDown();
        }
    }

    @Test
    void aStopWhileTheCloneIsBeingMadeWaitsForItAndClosesIt() throws Exception {
        // The signal lands while cloneRecording is in flight: the hook must not return before
        // the JVM answers, or the clone it makes is left with nothing to close it.
        final List<Long> closed = new CopyOnWriteArrayList<>();
        final FlightRecorderMXBean fr = (FlightRecorderMXBean) Proxy.newProxyInstance(
                FlightRecorderMXBean.class.getClassLoader(), new Class<?>[] {FlightRecorderMXBean.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("closeRecording")) {
                        closed.add((Long) args[0]);
                    }
                    return null;
                });
        final Snapshot.Cleanup cleanup = new Snapshot.Cleanup(fr, "4242");
        cleanup.cloning(3);
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Thread hook = Thread.ofPlatform().start(
                () -> cleanup.abandon(new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofSeconds(30)));
        Thread.sleep(200);
        assertTrue(hook.isAlive(), "the hook returned while the clone was still being made");
        // The JVM answers: the dump's own thread registers the clone, finds the dump stopped and closes it.
        final IOException stopped = assertThrows(IOException.class, () -> cleanup.cloned(17));
        cleanup.close(stopped);
        assertTrue(hook.join(Duration.ofSeconds(10)));
        assertEquals(List.of(17L), closed);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aCloneRequestTheJvmNeverAnswersIsNamed() throws Exception {
        final Snapshot.Cleanup cleanup = new Snapshot.Cleanup((FlightRecorderMXBean) Proxy.newProxyInstance(
                FlightRecorderMXBean.class.getClassLoader(), new Class<?>[] {FlightRecorderMXBean.class},
                (proxy, method, args) -> null), "4242");
        cleanup.cloning(3);
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        cleanup.abandon(new PrintStream(err, true, StandardCharsets.UTF_8), Duration.ofMillis(200));
        assertEquals("jfrq-live: JVM 4242 did not answer the clone request in 200 ms; a clone of recording 3 may be"
                + " left in it: 'jfrq-live 4242 status' lists it and 'jfrq-live 4242 stop --recording ID' closes it\n",
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aStartTheJvmRefusesKeepsTheRefusalWhenTheRollbackFails() {
        // The profile is refused, and closing the half-made recording fails too: the refusal is the answer.
        final FlightRecorderMXBean fr = (FlightRecorderMXBean) Proxy.newProxyInstance(
                FlightRecorderMXBean.class.getClassLoader(), new Class<?>[] {FlightRecorderMXBean.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "newRecording" -> 7L;
                    case "setPredefinedConfiguration" -> throw new IllegalArgumentException("no-such-profile");
                    case "closeRecording" -> throw new IOException("the connection went away");
                    default -> throw new AssertionError(method.getName());
                });
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Live.startRecording(fr, "no-such-profile", Map.of(), Map.of()));
        assertEquals("no-such-profile", refused.getMessage());
        assertEquals(1, refused.getSuppressed().length);
        assertEquals("the connection went away", refused.getSuppressed()[0].getMessage());
    }

    @Test
    void aDefaultNamedDumpNeverReplacesAnother() throws Exception {
        final FlightRecorderMXBean fr = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
        try (final Recording r = new Recording()) {
            r.setName("live-replace-" + System.nanoTime());
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            r.start();
            Thread.sleep(20);
            final Path taken = dir.resolve("taken.jfr");
            Files.writeString(taken, "an earlier dump");
            final IOException refused = assertThrows(IOException.class,
                    () -> Snapshot.take(fr, PID, r.getId(), Window.EVERYTHING, taken, false));
            assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
            assertEquals("an earlier dump", Files.readString(taken));
            final Snapshot replaced = Snapshot.take(fr, PID, r.getId(), Window.EVERYTHING, taken, true);
            assertTrue(replaced.bytes() > 0);
            try (final var files = Files.list(dir)) {
                assertEquals(1, files.count(), "a partial file was left behind");
            }
            r.stop();
        }
    }

    @Test
    void attachFailureExitsWithOne() {
        // No process has this pid: said at once, without the attach notice.
        final Run r = run("999999999", "status");
        assertEquals(1, r.status());
        assertTrue(r.err().contains("cannot attach to 999999999: no such process"), r.err());
        assertFalse(r.err().contains("attaching to JVM"), r.err());
    }

    @Test
    void parseSize() {
        assertEquals(0, Live.parseSize("0"));
        assertEquals(200_000_000L, Live.parseSize("200MB"));
        assertEquals(200_000_000L, Live.parseSize("200m"));
        assertEquals(1_000_000_000L, Live.parseSize(" 1 GB "));
        assertEquals(512_000L, Live.parseSize("512k"));
        assertEquals(3_000_000_000_000L, Live.parseSize("3T"));
        assertEquals(7, Live.parseSize("7B"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("lots"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("1.5GB"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("99999999999999999999"));
        assertThrows(Args.UsageException.class, () -> Live.parseSize("9999999999T"));
    }

    @Test
    void cursorIsPerJvmIncarnation() throws Exception {
        final Cursor fresh = Cursor.load(dir, "1", 100);
        assertNull(fresh.next());
        assertNull(fresh.lastWindow());
        final Instant stop = Instant.parse("2026-09-21T14:05:12.004Z");
        fresh.advance(new Window(Instant.parse("2026-09-21T14:03:07.121Z"), null), stop);

        final Cursor same = Cursor.load(dir, "1", 100);
        assertEquals(stop.plusMillis(1), same.next());
        assertEquals(new Window(Instant.parse("2026-09-21T14:03:07.121Z"), stop), same.lastWindow());

        final Cursor restarted = Cursor.load(dir, "1", 101);
        assertNull(restarted.next());
        assertNull(restarted.lastWindow());

        fresh.advance(Window.EVERYTHING, stop.plusSeconds(60));
        final Cursor afterFull = Cursor.load(dir, "1", 100);
        assertEquals(new Window(null, stop.plusSeconds(60)), afterFull.lastWindow());

        Files.writeString(dir.resolve("2.properties"), "jvm=100\ncursor=yesterday\n");
        assertThrows(IOException.class, () -> Cursor.load(dir, "2", 100));
        Files.writeString(dir.resolve("3.properties"), "jvm=100\ncursor=\\uZZZZ\n");
        final IOException escape = assertThrows(IOException.class, () -> Cursor.load(dir, "3", 100));
        assertTrue(escape.getMessage().contains("is damaged"), escape.getMessage());
        // A save is a rename: nothing but the cursor files is left in the directory.
        try (final var files = Files.list(dir)) {
            assertEquals(Set.of("1.properties", "2.properties", "3.properties"),
                    files.map(f -> f.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    @Test
    void aSecondProcessWaitsForTheCursorLock() throws Exception {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final Process holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), dir.toString(), "77").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            final BufferedReader fromHolder = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                    StandardCharsets.UTF_8));
            assertEquals("locked", fromHolder.readLine());
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            final CompletableFuture<Closeable> mine = CompletableFuture.supplyAsync(() -> {
                try {
                    return Cursor.lock(dir, "77", new PrintStream(err, true, StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(300);
            assertFalse(mine.isDone(), "took a lock another process holds");
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("waiting for another jfrq-live on JVM 77"),
                    err.toString(StandardCharsets.UTF_8));
            holder.getOutputStream().close();
            mine.get(30, TimeUnit.SECONDS).close();
        } finally {
            holder.destroy();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void theLoop() throws Exception {
        final String name = "live-test-" + System.nanoTime();
        final String[] pick = {"--recording", name, "--state", dir.toString()};

        final Run status = run(PID, "status", "--state", dir.toString());
        assertEquals(0, status.status(), status.err());
        // The pid, not pid@host: asking the target for its name costs a hostname lookup (Jvm.attach).
        assertTrue(status.out().contains("JVM        " + PID + ","), status.out());
        assertTrue(status.out().contains("Cursor     none"), status.out());

        final Run missing = run(concat(new String[] {PID, "full"}, pick));
        assertEquals(1, missing.status());
        assertTrue(missing.err().contains("has no recording '" + name + "'"), missing.err());

        final Run start = run(PID, "start", "--name", name, "--max-age", "5m", "--state", dir.toString());
        assertEquals(0, start.status(), start.err());
        try {
            loop(name, pick, start);
        } finally {
            run(PID, "stop", "--recording", name);
        }
    }

    private void loop(final String name, final String[] pick, final Run start) throws Exception {
        assertTrue(start.out().contains(name + " "), start.out());
        assertTrue(start.out().contains("RUNNING"), start.out());
        assertTrue(start.out().contains("max-age 5m00s"), start.out());
        assertFalse(start.out().contains("WARNING"), start.out());
        // What it configured, at the moment the operator can still act on it.
        assertTrue(start.out().contains("Settings   the JDK's 'profile' settings, then: thresholds "), start.out());
        // One name, one recording: --recording NAME must never be a guess.
        final Run duplicate = run(PID, "start", "--name", name, "--state", dir.toString());
        assertEquals(2, duplicate.status());
        assertTrue(duplicate.err().contains("already has a recording named '" + name + "'"), duplicate.err());
        assertTrue(start.out().contains("JavaMonitorEnter 1 ms"), start.out());
        assertTrue(start.out().contains("throttle off for FileRead, FileWrite, SocketRead, SocketWrite"), start.out());
        assertTrue(start.out().contains("ObjectAllocationSample 1000/s"), start.out());
        assertTrue(start.out().contains("sampling ExecutionSample 10 ms, NativeMethodSample 10 ms"), start.out());

        final Run early = run(concat(new String[] {PID, "delta"}, pick));
        assertEquals(2, early.status());
        assertTrue(early.err().contains("run 'full' first"), early.err());
        assertEquals(2, run(concat(new String[] {PID, "again"}, pick)).status());

        work();
        final Path t0 = dir.resolve("t0.jfr");
        final Run full = run(concat(new String[] {PID, "full", "--out", t0.toString()}, pick, "--", "info"));
        assertEquals(0, full.status(), full.err());
        assertTrue(Files.size(t0) > 0);
        assertTrue(full.out().contains("Dumped     " + t0), full.out());
        assertTrue(full.out().contains("Window     the start .. now (everything the recording kept)"), full.out());
        assertTrue(full.out().contains("Cursor     next delta from"), full.out());
        assertTrue(full.out().contains("Recording  t0.jfr"), full.out());
        assertFalse(full.out().contains("WARNING"), full.out());

        final long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        final Cursor cursor = Cursor.load(dir, PID, jvmStart);
        assertNotNull(cursor.next());
        final RecordingInfo i0 = JfrReader.read(t0);
        // The full dump's window begins where its file does, so an 'again' of it is span-checked.
        assertEquals(Instant.ofEpochSecond(0, i0.startNanos()), cursor.lastWindow().begin());
        // A leading zero is the same process and the same cursor.
        final Run zero = run("0" + PID, "status", "--state", dir.toString());
        assertEquals(0, zero.status(), zero.err());
        assertTrue(zero.out().contains("JVM        " + PID + ","), zero.out());
        assertTrue(zero.out().contains("Cursor     next delta from"), zero.out());

        work();
        final Path t1 = dir.resolve("t1.jfr");
        final Run delta = run(concat(new String[] {PID, "delta", "--out", t1.toString()}, pick, "--", "info"));
        assertEquals(0, delta.status(), delta.err());
        assertTrue(delta.out().contains("Window     " + TIME.format(cursor.next()) + " .. now (since the previous dump)"),
                delta.out());
        assertFalse(delta.out().contains("Note"), delta.out());
        assertFalse(delta.out().contains("WARNING"), delta.out());
        final RecordingInfo i1 = JfrReader.read(t1);
        // The delta starts where the previous dump stopped: the chunk the full dump sealed is not in it.
        assertTrue(i1.startNanos() >= i0.endNanos() - 5_000_000L,
                "delta starts " + i1.startNanos() + ", full ended " + i0.endNanos());
        assertTrue(i1.endNanos() > i0.endNanos());

        final Instant afterDelta = Cursor.load(dir, PID, jvmStart).next();
        final Path t1b = dir.resolve("t1b.jfr");
        final Run again = run(concat(new String[] {PID, "again", "--out", t1b.toString()}, pick));
        assertEquals(0, again.status(), again.err());
        assertTrue(again.out().contains("(the previous window again)"), again.out());
        final RecordingInfo i1b = JfrReader.read(t1b);
        assertEquals(i1.startNanos(), i1b.startNanos());
        assertEquals(i1.endNanos(), i1b.endNanos());
        assertEquals(afterDelta, Cursor.load(dir, PID, jvmStart).next(), "again must not move the cursor");

        // A question that answers in JSON gets standard output to itself: a program reads it whole,
        // so the dump's own lines go to standard error.
        final Path t1c = dir.resolve("t1c.jfr");
        final Run json = run(concat(new String[] {PID, "again", "--out", t1c.toString()}, pick, "--", "info", "--json"));
        assertEquals(0, json.status(), json.err());
        assertTrue(json.out().startsWith("{\"tool\":\"jfrq\","), json.out());
        assertTrue(json.out().endsWith("}\n"), json.out());
        assertTrue(json.out().contains("\"command\":\"info\""), json.out());
        assertTrue(json.err().contains("Dumped     " + t1c), json.err());
        assertTrue(json.err().contains("Cursor     next delta from"), json.err());

        // A failed publish must not delete a path the caller already owned. The old direct writer
        // opened this directory, failed, and then deleted it as though it were its partial file.
        final Path existingTarget = dir.resolve("existing-output");
        Files.createDirectory(existingTarget);
        final Run protectedTarget = run(concat(new String[] {PID, "again", "--out", existingTarget.toString()}, pick));
        assertEquals(1, protectedTarget.status());
        assertTrue(Files.isDirectory(existingTarget), "a failed dump removed the existing output target");

        final Run rounded = run(concat(new String[] {PID, "bound", "--max-age", "1500ms"}, pick));
        assertEquals(0, rounded.status(), rounded.err());
        // JFR keeps whole seconds: 1.5 s is sent as 2 s, and the line says so.
        assertTrue(rounded.out().contains(", max-age 2"), rounded.out());
        assertTrue(rounded.out().contains("rounded up to"), rounded.out());

        // "infinity" keeps everything: the recorder's own "no bound", not a huge count of seconds.
        final Run forever = run(concat(new String[] {PID, "bound", "--max-age", "infinity"}, pick));
        assertEquals(0, forever.status(), forever.err());
        assertFalse(forever.out().contains("max-age"), forever.out());

        final Run bound = run(concat(new String[] {PID, "bound", "--max-age", "0", "--max-size", "64MB"}, pick));
        assertEquals(0, bound.status(), bound.err());
        assertTrue(bound.out().contains("max-size 64.0 MB"), bound.out());
        assertFalse(bound.out().contains("max-age"), bound.out());

        final Run after = run(PID, "status", "--state", dir.toString());
        assertTrue(after.out().contains("Cursor     next delta from"), after.out());
        assertTrue(after.out().contains(name), after.out());

        final Run stop = run(concat(new String[] {PID, "stop"}, pick));
        assertEquals(0, stop.status(), stop.err());
        assertTrue(stop.out().contains("Stopped"), stop.out());
        final Run gone = run(concat(new String[] {PID, "stop"}, pick));
        assertEquals(1, gone.status());
    }

    @Test
    void stoppingARecordingWithADestinationIsASuccessThatSaysWhereItWent() throws Exception {
        // -XX:StartFlightRecording=filename=... gives the recording a destination, and the JVM
        // closes such a recording itself once it has written it there: a close after the stop
        // found nothing, and a stop that worked was reported as the JVM refusing it.
        final String name = "live-dest-" + System.nanoTime();
        final Path destination = dir.resolve("dest.jfr");
        final Recording r = new Recording();
        try {
            r.setName(name);
            r.setDestination(destination);
            r.start();
            final Run stop = run(PID, "stop", "--recording", name, "--state", dir.toString());
            assertEquals(0, stop.status(), stop.err());
            assertTrue(stop.out().contains("Stopped    " + r.getId() + "  " + name + "  and the JVM wrote it to "
                    + destination + " and closed it"), stop.out());
            assertTrue(Files.size(destination) > 0);
            assertEquals(1, run(PID, "stop", "--recording", name, "--state", dir.toString()).status());
        } finally {
            r.close();
        }
    }

    @Test
    void aNameTwoRecordingsShareIsAmbiguousAndAStoppedOneIsClosed() throws Exception {
        final String name = "live-twin-" + System.nanoTime();
        try (final Recording a = new Recording(); final Recording b = new Recording()) {
            a.setName(name);
            b.setName(name);
            a.start();
            b.start();
            final Run twins = run(PID, "full", "--recording", name, "--state", dir.toString(), "--out",
                    dir.resolve("t.jfr").toString());
            assertEquals(2, twins.status());
            assertTrue(twins.err().contains("names 2 recordings; pick one by id: " + a.getId() + " (RUNNING) " + b.getId()),
                    twins.err());
            // An id always picks exactly one.
            b.stop();
            final Run closed = run(PID, "stop", "--recording", Long.toString(b.getId()));
            assertEquals(0, closed.status(), closed.err());
            assertTrue(closed.out().startsWith("Closed     " + b.getId()), closed.out());
            final Run stopped = run(PID, "stop", "--recording", name);
            assertEquals(0, stopped.status(), stopped.err());
            assertTrue(stopped.out().startsWith("Stopped    " + a.getId()), stopped.out());
        }
    }

    @Test
    void aMissingSettingsFileIsNamed() {
        final Path jfc = dir.resolve("missing.jfc");
        final Run r = run(PID, "start", "--name", "live-nojfc-" + System.nanoTime(), "--settings", jfc.toString());
        assertEquals(1, r.status());
        assertTrue(r.err().contains("no such file or directory: " + jfc), r.err());
    }

    @Test
    void startWithAJfcFileAndAnUnboundedWarning() throws Exception {
        final String name = "live-jfc-" + System.nanoTime();
        final Path jfc = dir.resolve("tiny.jfc");
        Files.writeString(jfc, """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration version="2.0" label="tiny">
                  <event name="jdk.ThreadSleep">
                    <setting name="enabled">true</setting>
                    <setting name="threshold">0 ms</setting>
                  </event>
                </configuration>
                """);
        final Run start = run(PID, "start", "--name", name, "--settings", jfc.toString(), "--state", dir.toString());
        assertEquals(0, start.status(), start.err());
        try {
            assertTrue(start.out().contains("WARNING    the recording has no bound"), start.out());
            assertTrue(start.out().contains("no bound"), start.out());

            final Run full = run(PID, "full", "--recording", name, "--state", dir.toString(), "--out",
                    dir.resolve("f.jfr").toString());
            assertEquals(0, full.status(), full.err());
            assertTrue(full.out().contains("WARNING    the recording has no bound"), full.out());

            final Run unknownProfile = run(PID, "start", "--name", name + "-x", "--settings", "no-such-profile");
            assertEquals(1, unknownProfile.status());
            assertTrue(unknownProfile.err().contains("the JVM refused"), unknownProfile.err());
        } finally {
            assertEquals(0, run(PID, "stop", "--recording", name).status());
        }
    }

    @Test
    void startSetsWhatStallsAdvisesAndRefusesWhatTheRecorderWouldIgnore() throws Exception {
        final String name = "live-set-" + System.nanoTime();
        final Run start = run(PID, "start", "--name", name, "--max-age", "1m", "--set",
                "jdk.ExecutionSample#period=1ms,jdk.ThreadSleep#threshold=0 ms");
        assertEquals(0, start.status(), start.err());
        try {
            assertTrue(start.out().contains("sampling ExecutionSample 10 ms, NativeMethodSample 10 ms; then --set "
                    + "jdk.ExecutionSample#period=1ms, jdk.ThreadSleep#threshold=0 ms"), start.out());
            final FlightRecorderMXBean fr = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
            final jdk.management.jfr.RecordingInfo r = fr.getRecordings().stream().filter(i -> name.equals(i.getName())).findFirst().orElseThrow();
            assertEquals("1ms", fr.getRecordingSettings(r.getId()).get("jdk.ExecutionSample#period"));
            assertEquals("0 ms", fr.getRecordingSettings(r.getId()).get("jdk.ThreadSleep#threshold"));
            // The recommended settings stay under the ones --set does not name.
            assertEquals("1 ms", fr.getRecordingSettings(r.getId()).get("jdk.ThreadPark#threshold"));
        } finally {
            assertEquals(0, run(PID, "stop", "--recording", name).status());
        }

        // On top of a .jfc too, which is otherwise taken as it is.
        final Path jfc = dir.resolve("sleep.jfc");
        Files.writeString(jfc, """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration version="2.0" label="sleep">
                  <event name="jdk.ThreadSleep">
                    <setting name="enabled">true</setting>
                    <setting name="threshold">20 ms</setting>
                  </event>
                </configuration>
                """);
        final Run fromJfc = run(PID, "start", "--name", name + "-jfc", "--max-age", "1m", "--settings", jfc.toString(),
                "--set", "jdk.ThreadSleep#threshold=0ms");
        assertEquals(0, fromJfc.status(), fromJfc.err());
        try {
            assertTrue(fromJfc.out().contains("taken as it is; then --set jdk.ThreadSleep#threshold=0ms"), fromJfc.out());
        } finally {
            assertEquals(0, run(PID, "stop", "--recording", name + "-jfc").status());
        }

        // The recorder takes all of these without a word; each is a usage error, the first two
        // before any attach.
        for (final String bad : new String[] {"period=1ms", "jdk.ExecutionSample#period=1 msec",
                "jdk.ExecutionSample#period=1ms,jdk.ExecutionSample#period=2ms"}) {
            final Run r = run(PID, "start", "--name", name + "-bad", "--set", bad);
            assertEquals(2, r.status(), bad);
            assertTrue(r.err().contains("--set"), r.err());
            assertFalse(r.err().contains("attaching"), r.err());
        }
        final Run unknown = run(PID, "start", "--name", name + "-bad", "--set", "jdk.NoSuchEvent#period=1ms");
        assertEquals(2, unknown.status());
        assertTrue(unknown.err().contains("--set jdk.NoSuchEvent#period: the recording's settings have no such event "
                + "setting"), unknown.err());
        // Refused after the recording was made: it is closed again, not left behind.
        assertTrue(ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class).getRecordings().stream()
                .noneMatch(i -> (name + "-bad").equals(i.getName())));
    }

    @Test
    void aStartWhoseBoundIsRemovedWarnsToo() {
        // 0 and infinity are the recorder's "no bound": asked for explicitly, still unbounded.
        for (final String[] bound : new String[][] {{"--max-age", "0"}, {"--max-age", "infinity", "--max-size", "0"}}) {
            final String name = "live-nobound-" + System.nanoTime();
            final Run start = run(concat(new String[] {PID, "start", "--name", name, "--settings", "default"}, bound));
            try {
                assertEquals(0, start.status(), start.err());
                assertTrue(start.out().contains("no bound"), start.out());
                assertTrue(start.out().contains("WARNING    the recording has no bound"), start.out());
            } finally {
                assertEquals(0, run(PID, "stop", "--recording", name).status());
            }
        }
        final String name = "live-bound-" + System.nanoTime();
        final Run bounded = run(PID, "start", "--name", name, "--settings", "default", "--max-size", "0", "--max-age", "1m");
        try {
            assertEquals(0, bounded.status(), bounded.err());
            assertFalse(bounded.out().contains("WARNING"), bounded.out());
        } finally {
            assertEquals(0, run(PID, "stop", "--recording", name).status());
        }
    }

    @Test
    void severalRunningRecordingsNeedAChoice() throws Exception {
        final String a = "live-a-" + System.nanoTime();
        final String b = "live-b-" + System.nanoTime();
        assertEquals(0, run(PID, "start", "--name", a, "--max-size", "1MB", "--state", dir.toString()).status());
        assertEquals(0, run(PID, "start", "--name", b, "--max-size", "1MB", "--state", dir.toString()).status());
        try {
            final Run ambiguous = run(PID, "full", "--state", dir.toString(), "--out", dir.resolve("x.jfr").toString());
            assertEquals(1, ambiguous.status());
            assertTrue(ambiguous.err().contains("running recordings; pick one with --recording"), ambiguous.err());
            assertTrue(ambiguous.err().contains(a) && ambiguous.err().contains(b), ambiguous.err());
        } finally {
            run(PID, "stop", "--recording", a);
            run(PID, "stop", "--recording", b);
        }
    }

    @Test
    void inMemoryRecordingsHaveNoChunksToDump() throws Exception {
        final String name = "live-mem-" + System.nanoTime();
        try (final Recording r = new Recording()) {
            r.setName(name);
            r.setToDisk(false);
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            r.start();
            Thread.sleep(50);
            final Run full = run(PID, "full", "--recording", name, "--state", dir.toString(), "--out",
                    dir.resolve("mem.jfr").toString());
            assertEquals(1, full.status(), full.out());
            assertTrue(full.err().contains("holds no data in the window"), full.err());
            assertFalse(Files.exists(dir.resolve("mem.jfr")));
            r.stop();
        }
    }

    private static void work() throws InterruptedException {
        final byte[][] keep = new byte[16][];
        for (int i = 0; i < 400; i++) {
            keep[i % keep.length] = new byte[64 * 1024];
        }
        if (keep[0].length == 0) {
            throw new IllegalStateException();
        }
        Thread.sleep(250);
    }

    private static String[] concat(final String[] a, final String[] b, final String... c) {
        final String[] r = new String[a.length + b.length + c.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        System.arraycopy(c, 0, r, a.length + b.length, c.length);
        return r;
    }
}
