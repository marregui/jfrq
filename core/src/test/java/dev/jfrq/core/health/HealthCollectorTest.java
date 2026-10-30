// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport.ClassRow;
import dev.jfrq.core.health.HealthReport.Finding;
import dev.jfrq.core.health.HealthReport.Series;
import dev.jfrq.core.health.HealthReport.SiteRow;
import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.report.Json;
import dev.jfrq.core.report.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HealthCollector} on one real recording of a JVM in trouble: two {@code System.gc()}
 * calls, an {@code OutOfMemoryError} constructed the way the JDK's direct-memory code does,
 * fifty {@code Error}s, and a few hundred exceptions made at one site through a factory and
 * a base class of their own. The counts the test controls are exact; what the JVM decides
 * (how many young collections, the CPU) is only checked for shape.
 */
class HealthCollectorTest {

    static final int THROWN = 400;
    static final int ERRORS = 50;
    static final String OOM_MESSAGE = "Cannot reserve 1048576 bytes of direct buffer memory (a test)";
    static Path file;

    @TempDir
    static Path dir;

    static HealthReport report;

    /** An application's own base exception: its constructor is part of every subclass's construction. */
    static class BaseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BaseException(final String message) {
            super(message);
        }
    }

    static final class LeafException extends BaseException {
        private static final long serialVersionUID = 1L;

        private LeafException(final String message) {
            super(message);
        }

        static LeafException of(final int i) {
            return new LeafException("request " + i + " refused");
        }
    }

    /** The site every {@link LeafException} is made at. */
    static int makeTrouble() {
        int made = 0;
        for (int i = 0; i < THROWN; i++) {
            try {
                throw LeafException.of(i);
            } catch (final LeafException e) {
                made++;
            }
        }
        return made;
    }

    @BeforeAll
    static void record() throws Exception {
        file = JfrFixtures.record(dir, "health", HealthCollectorTest::settings, () -> {
            // The throwable count is the difference of the statistics read at the chunk's two ends.
            JfrFixtures.sleep(150);
            assertEquals(THROWN, makeTrouble());
            for (int i = 0; i < ERRORS; i++) {
                assertNotNull(new AssertionError("error " + i));
            }
            assertNotNull(new OutOfMemoryError(OOM_MESSAGE));
            System.gc();
            JfrFixtures.sleep(150);
            System.gc();
            JfrFixtures.sleep(150);
        });
        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        report = health.report();
    }

    static void settings(final Recording r) {
        for (final String type : List.of("jdk.GarbageCollection", "jdk.OldGarbageCollection", "jdk.GCHeapSummary",
                "jdk.JavaErrorThrow", "jdk.EvacuationFailed")) {
            r.enable(type);
        }
        for (final String type : List.of("jdk.GCConfiguration", "jdk.GCHeapConfiguration", "jdk.CPULoad",
                "jdk.ResidentSetSize")) {
            r.enable(type).withPeriod(Duration.ofMillis(50));
        }
        // Read when the chunk begins and when it ends, so the two readings bracket the body
        // whatever the platform's timer does: on Windows the first 50 ms reading can come only
        // after the body has thrown everything, and the threads started need two readings.
        r.enable("jdk.ExceptionStatistics").with("period", "everyChunk");
        r.enable("jdk.JavaThreadStatistics").with("period", "everyChunk");
        r.enable("jdk.JavaExceptionThrow").withStackTrace().with("throttle", "off");
    }

    @Test
    void theFindingsAreTheOnesTheJvmReportedInTheirRankOrder() {
        final List<Finding.Kind> kinds = new ArrayList<>();
        for (final Finding f : report.findings()) {
            kinds.add(f.kind());
        }
        // Whatever else this JVM did (a humongous allocation of its own), these are there, in this order.
        final List<Finding.Kind> expected = List.of(Finding.Kind.OUT_OF_MEMORY, Finding.Kind.FULL_GC,
                Finding.Kind.SYSTEM_GC);
        assertEquals(expected, kinds.stream().filter(expected::contains).toList(), kinds.toString());
        for (int i = 1; i < kinds.size(); i++) {
            assertTrue(kinds.get(i - 1).compareTo(kinds.get(i)) < 0, kinds.toString());
        }
        assertEquals(1, finding(Finding.Kind.OUT_OF_MEMORY).count());
        assertEquals(2, finding(Finding.Kind.SYSTEM_GC).count());
        assertTrue(finding(Finding.Kind.FULL_GC).count() >= 2);
    }

    @Test
    void theMedianTellsAStartUpBurstFromASteadyRate() {
        // Field case: 341 ClassNotFoundExceptions from +5.1 s to +605.6 s read as ~0.3/s all
        // along; half of them were made by +6.0 s. The median says so, a straggler or not.
        final long s = 1_000_000_000L;
        final ClassRow burst = new ClassRow("java.lang.ClassNotFoundException", 341, 0.02, null, 5_111_000_000L,
                5_972_000_000L, 605_594_000_000L);
        assertEquals("+5.111s, +5.972s, +605.594s", HealthReport.when(burst, 0));
        final ClassRow once = new ClassRow("java.lang.OutOfMemoryError", 1, 0.01, null, 7 * s, 7 * s, 7 * s);
        assertEquals("+7.000s", HealthReport.when(once, 0));
    }

    @Test
    void aFindingSaysWhenItHappenedWithinTheRecording() {
        final RecordingInfo info = report.info();
        final Finding gc = finding(Finding.Kind.SYSTEM_GC);
        assertTrue(info.startNanos() <= gc.firstNanos() && gc.firstNanos() < gc.lastNanos()
                && gc.lastNanos() <= info.span().end(), gc.toString());
        assertTrue(gc.text().startsWith("2 collections caused by System.gc(), from +"), gc.text());
        final Finding oom = finding(Finding.Kind.OUT_OF_MEMORY);
        assertEquals(oom.firstNanos(), oom.lastNanos());
        assertTrue(oom.text().startsWith("1 OutOfMemoryError created, at +"), oom.text());
        assertTrue(oom.text().contains("\"" + OOM_MESSAGE + "\""), oom.text());
    }

    @Test
    void theGcAccountAddsUpAndKeepsItsOrder() {
        final HealthReport.Gc gc = report.gc();
        assertEquals(2L, gc.causes().get("System.gc()"));
        long sum = 0;
        long previous = Long.MAX_VALUE;
        for (final long n : gc.collections().values()) {
            assertTrue(n <= previous, gc.collections().toString());
            previous = n;
            sum += n;
        }
        assertEquals(sum, gc.count());
        final long concurrent = gc.collections().getOrDefault(HealthReport.CONCURRENT_CYCLE, 0L);
        assertEquals(gc.count() - concurrent, gc.causes().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(concurrent == 0, gc.causesNote().isEmpty());
        assertTrue(gc.pauseNanos() > 0 && gc.longestPauseNanos() > 0 && gc.longestPauseNanos() <= gc.pauseNanos());
        assertTrue(gc.gcTimeRatio() > 0, "GCConfiguration read");
        assertTrue(gc.maxHeapBytes() > 0, "GCHeapConfiguration read");
    }

    @Test
    void everyTrendTheSettingsAskedForIsThere() {
        final List<String> names = report.trends().stream().map(Series::name).toList();
        // In this order; the CPU load was missing from one run of a recording this short, under a
        // full parallel build, so it is not required.
        final List<String> all = List.of("Heap after GC", "Resident set", "Live threads", "JVM CPU", "Machine CPU");
        assertEquals(all.stream().filter(names::contains).toList(), names);
        assertTrue(names.containsAll(all.subList(0, 3)), names.toString());
        for (final Series s : report.trends()) {
            assertTrue(s.points() >= 1, s.toString());
            assertTrue(s.min() <= s.mean() && s.mean() <= s.max(), s.toString());
            assertTrue(s.min() <= s.start() && s.start() <= s.max() && s.min() <= s.end() && s.end() <= s.max());
            if (s.points() >= 3) {
                assertTrue(s.floorFirst() >= s.min() && s.floorLast() >= s.min(), s.toString());
            } else {
                assertTrue(Double.isNaN(s.floorFirst()) && Double.isNaN(s.floorLast()), s.toString());
            }
        }
        assertTrue(report.threads().peak() >= 1);
        assertTrue(report.threads().started() >= 0);
    }

    @Test
    void everyThrowableMadeIsCountedAndTheSiteIsTheCodeThatMadeIt() {
        final HealthReport.Throwables t = report.throwables();
        assertNull(t.throttle(), "throttle off");
        // Created counts between the first and last statistics reading, which bracket the body.
        assertTrue(t.created() >= THROWN + 2, String.valueOf(t.created()));
        assertTrue(t.rate() > 0);
        final String leaf = LeafException.class.getName();
        final ClassRow row = t.byClass().stream().filter(c -> c.className().equals(leaf)).findFirst().orElseThrow();
        assertEquals(THROWN, row.samples());
        // One of them, in file order, which is not time order.
        assertTrue(row.message().matches("request \\d+ refused"), row.message());
        assertEquals((double) THROWN / t.samples(), row.share(), 1e-9);
        // When the first and the last of the class were made, inside the recording.
        final long start = report.info().startNanos();
        assertTrue(start <= row.firstNanos() && row.firstNanos() <= row.medianNanos()
                && row.medianNanos() <= row.lastNanos() && row.firstNanos() < row.lastNanos()
                && row.lastNanos() <= report.info().span().end(), row.toString());
        assertTrue(HealthReport.when(row, start).matches("\\+\\d+\\.\\d{3}s, \\+\\d+\\.\\d{3}s, \\+\\d+\\.\\d{3}s"),
                HealthReport.when(row, start));
        final ClassRow once = t.byClass().stream().filter(c -> c.className().equals("java.lang.OutOfMemoryError"))
                .findFirst().orElseThrow();
        assertEquals(once.firstNanos(), once.lastNanos());
        assertTrue(HealthReport.when(once, start).matches("\\+\\d+\\.\\d{3}s"), HealthReport.when(once, start));
        final SiteRow site = t.bySite().stream().filter(s -> s.className().equals(leaf)).findFirst().orElseThrow();
        // Past Throwable.<init>, BaseException.<init>, LeafException.<init> and LeafException.of.
        assertEquals(HealthCollectorTest.class.getName() + ".makeTrouble", site.site());
        assertEquals(THROWN, site.samples());
        assertEquals("makeTrouble", site.stack().frames().getFirst().method());
        // An Error is emitted twice, from Throwable's constructor and from Error's: counted once.
        assertEquals(ERRORS, t.byClass().stream().filter(c -> c.className().equals("java.lang.AssertionError"))
                .findFirst().orElseThrow().samples());
        assertEquals(ERRORS, t.errors().get("java.lang.AssertionError"));
        // Error's constructor skips an OutOfMemoryError; Throwable's does not.
        assertFalse(t.errors().containsKey("java.lang.OutOfMemoryError"));
        assertEquals(1, t.byClass().stream().filter(c -> c.className().equals("java.lang.OutOfMemoryError"))
                .findFirst().orElseThrow().samples());
    }

    /**
     * The JDK's running total counts an Error twice too; corrected, it is exactly the number
     * of throwables whose event falls between the first and last reading.
     */
    @Test
    void theCountCreatedIsExactOnceErrorsAreCountedOnce() throws Exception {
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        final List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        for (final RecordedEvent e : events) {
            if (e.getEventType().getName().equals("jdk.ExceptionStatistics")) {
                final long t = e.getStartTime().getEpochSecond() * 1_000_000_000L + e.getStartTime().getNano();
                first = Math.min(first, t);
                last = Math.max(last, t);
            }
        }
        long inside = 0;
        for (final RecordedEvent e : events) {
            final long t = e.getStartTime().getEpochSecond() * 1_000_000_000L + e.getStartTime().getNano();
            if (e.getEventType().getName().equals("jdk.JavaExceptionThrow") && t > first && t <= last
                    && !e.getStackTrace().getFrames().getFirst().getMethod().getType().getName().equals("java.lang.Error")) {
                inside++;
            }
        }
        assertTrue(inside >= THROWN + ERRORS + 1, String.valueOf(inside));
        assertEquals(inside, report.throwables().created());
    }

    /**
     * A JVM whose live set fills its heap, in a process of its own: G1 fails to evacuate,
     * then collects the whole heap. The collections that failed are counted once each,
     * however many events one of them reported, and ranked above the full collections.
     */
    @Test
    void aHeapWithNoRoomLeftIsAFailedEvacuationBeforeAFullCollection() throws Exception {
        final Path file = dir.resolve("squeeze.jfr");
        final Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                "-Xms48m", "-Xmx48m", "-XX:+UseG1GC", "-XX:StartFlightRecording=filename=" + file + ",settings=default",
                "-Xlog:disable", "-Xlog:all=error:stderr", "-cp", System.getProperty("java.class.path"),
                Squeeze.class.getName(), "1500").redirectErrorStream(true).redirectOutput(dir.resolve("squeeze.log").toFile())
                .start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the squeezed JVM finished");
        assertEquals(0, p.exitValue(), () -> read(dir.resolve("squeeze.log")));

        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        final HealthReport squeezed = health.report();
        final Finding failed = squeezed.findings().stream().filter(f -> f.kind() == Finding.Kind.EVACUATION_FAILED)
                .findFirst().orElseThrow(() -> new AssertionError(squeezed.findings().toString()));
        final Set<Long> gcIds = new HashSet<>();
        for (final RecordedEvent e : RecordingFile.readAllEvents(file)) {
            if (e.getEventType().getName().equals("jdk.EvacuationFailed")) {
                gcIds.add(e.getLong("gcId"));
            }
        }
        assertEquals(gcIds.size(), failed.count());
        assertTrue(failed.text().contains(" failed to evacuate, from +"), failed.text());
        assertEquals(Finding.Kind.EVACUATION_FAILED, squeezed.findings().getFirst().kind());
        assertTrue(squeezed.findings().stream().anyMatch(f -> f.kind() == Finding.Kind.FULL_GC));
        // G1's concurrent cycles carry the cause of the pause that started them and are not counted twice.
        final HealthReport.Gc gc = squeezed.gc();
        assertTrue(gc.collections().getOrDefault(HealthReport.CONCURRENT_CYCLE, 0L) > 0, gc.collections().toString());
        assertEquals(gc.count() - gc.collections().get(HealthReport.CONCURRENT_CYCLE),
                gc.causes().values().stream().mapToLong(Long::longValue).sum());
    }

    /**
     * A JVM run with native memory tracking, in a process of its own, with one thread burning
     * CPU: NMT's committed memory is read by category with the total first, and the burning
     * thread's CPU comes from its own readings.
     */
    @Test
    void nativeMemoryAndThreadCpuAreReadFromAJvmThatRecordsThem() throws Exception {
        final Path file = dir.resolve("nmt.jfr");
        final Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                "-XX:NativeMemoryTracking=summary", "-XX:StartFlightRecording=filename=" + file + ",settings=default,"
                + "jdk.NativeMemoryUsage#period=100ms,jdk.NativeMemoryUsageTotal#period=100ms,"
                + "jdk.ThreadCPULoad#period=100ms", "-Xlog:disable", "-Xlog:all=error:stderr",
                "-cp", System.getProperty("java.class.path"), Burn.class.getName(), "1500")
                .redirectErrorStream(true).redirectOutput(dir.resolve("nmt.log").toFile()).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the JVM finished");
        assertEquals(0, p.exitValue(), () -> read(dir.resolve("nmt.log")));

        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        final HealthReport r = health.report();
        final List<Series> nmt = r.nativeMemory();
        assertEquals("Total", nmt.getFirst().name(), nmt.toString());
        assertTrue(nmt.stream().anyMatch(s -> s.name().equals("Java Heap")), nmt.toString());
        for (int i = 0; i < nmt.size(); i++) {
            assertEquals(Series.Unit.BYTES, nmt.get(i).unit());
            if (i > 1) {
                assertTrue(nmt.get(i - 1).end() >= nmt.get(i).end(), "largest at the end first: " + nmt);
            }
        }
        assertTrue(nmt.getFirst().end() > 0);
        final ThreadCpu.Result cpu = r.threadCpu();
        assertTrue(cpu.isKnown());
        final double burner = cpu.byThread().entrySet().stream().filter(e -> e.getKey().name().equals(Burn.THREAD))
                .mapToDouble(Map.Entry::getValue).findFirst().orElseThrow(() -> new AssertionError(cpu.byThread()));
        // One thread of the machine's, busy for most of the window, and nothing above the whole machine.
        assertTrue(burner > 0 && burner <= 1, String.valueOf(burner));
        assertTrue(cpu.share() >= burner && cpu.share() <= 1, cpu.toString());
        assertTrue(r.threadCpuLine().startsWith("Java threads used "), r.threadCpuLine());
        final Map<String, Object> doc = JsonParser.object(Json.health(r, 3, "test"));
        assertEquals(4, list(doc, "nativeMemory").size(), "the total and --top categories");
        assertEquals((long) nmt.size() - 1, doc.get("nativeMemoryCategories"));
        assertEquals(cpu.share(), (double) map(doc, "threadCpu").get("share"), 1e-9);
        assertTrue(Html.health(r, 3).contains("Native memory ("), "the page has the section");
    }

    /** Burns CPU on one named thread for as many milliseconds as it is told. */
    static final class Burn {
        static final String THREAD = "burner";

        public static void main(final String[] args) throws InterruptedException {
            final long millis = Long.parseLong(args[0]);
            final Thread t = new Thread(() -> JfrFixtures.burn(millis), THREAD);
            t.start();
            t.join();
        }
    }

    /** Keeps about 40 MB alive in a 48 MB heap and churns it, for as many milliseconds as it is told. */
    static final class Squeeze {
        public static void main(final String[] args) {
            final long end = System.nanoTime() + Long.parseLong(args[0]) * 1_000_000L;
            final List<byte[]> live = new ArrayList<>();
            final Random random = new Random(1);
            while (System.nanoTime() < end) {
                final byte[] b = new byte[1024 + random.nextInt(4096)];
                if (live.size() < 13_000) {
                    live.add(b);
                } else {
                    live.set(random.nextInt(live.size()), b);
                }
            }
        }
    }

    static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            return e.toString();
        }
    }

    @Test
    void theJsonDocumentCarriesTheReportInUnitsAProgramCanUse() {
        final Map<String, Object> doc = JsonParser.object(Json.health(report, 1, "test"));
        assertEquals("health", doc.get("command"));
        final List<Object> findings = list(doc, "findings");
        assertEquals(report.findings().size(), findings.size());
        for (int i = 0; i < findings.size(); i++) {
            final Map<String, Object> f = object(findings.get(i));
            final Finding expected = report.findings().get(i);
            assertEquals(expected.kind().name(), f.get("kind"));
            assertEquals(expected.count(), f.get("count"));
            if (expected.firstNanos() == Nulls.LONG_NULL) {
                // A finding about the whole window (GC time over the goal, on a slow machine) has no time.
                assertNull(f.get("first"), f.toString());
                assertNull(f.get("firstOffsetNanos"), f.toString());
                assertNull(f.get("lastOffsetNanos"), f.toString());
            } else {
                assertEquals(expected.firstNanos() - report.info().startNanos(), f.get("firstOffsetNanos"));
                assertEquals(expected.lastNanos() - report.info().startNanos(), f.get("lastOffsetNanos"));
                assertTrue(((String) f.get("first")).endsWith("Z"), f.toString());
            }
        }
        final Map<String, Object> gc = map(doc, "gc");
        assertEquals(report.gc().count(), gc.get("collections"));
        assertEquals(2L, map(gc, "byCause").get("System.gc()"));
        assertEquals(List.copyOf(report.gc().collections().keySet()), List.copyOf(map(gc, "byCollector").keySet()));
        assertEquals(report.trends().size(), list(doc, "trends").size());
        final Map<String, Object> t = map(doc, "throwables");
        assertEquals(report.throwables().created(), t.get("created"));
        assertEquals((long) report.throwables().byClass().size(), t.get("classesFound"));
        assertEquals(1, list(t, "byClass").size(), "--top 1");
        final Map<String, Object> firstClass = object(list(t, "byClass").getFirst());
        final ClassRow top = report.throwables().byClass().getFirst();
        assertEquals(top.firstNanos() - report.info().startNanos(), firstClass.get("firstOffsetNanos"));
        assertEquals(top.lastNanos() - report.info().startNanos(), firstClass.get("lastOffsetNanos"));
        assertTrue(((String) firstClass.get("last")).endsWith("Z"), firstClass.toString());
        assertEquals(1, list(t, "bySite").size(), "--top 1");
        assertEquals((long) ERRORS, map(t, "errors").get("java.lang.AssertionError"));
        assertTrue(Html.health(report, 3).contains("caused by System.gc()"));
    }

    /** A recording without one of the events health reads: every number is unknown, not zero. */
    @Test
    void aRecordingWithNothingToReadSaysSoRatherThanZero() throws Exception {
        final Path file = JfrFixtures.record(dir, "empty", r -> {
        }, () -> JfrFixtures.sleep(20));
        final HealthCollector health = new HealthCollector();
        JfrReader.read(file, health);
        final HealthReport empty = health.report();
        assertTrue(empty.findings().isEmpty());
        assertTrue(empty.trends().isEmpty());
        assertEquals("", empty.gc().causesNote());
        final Map<String, Object> doc = JsonParser.object(Json.health(empty, 5, "test"));
        final Map<String, Object> gc = map(doc, "gc");
        assertNull(gc.get("collections"));
        assertNull(gc.get("pauseNanos"));
        assertNull(gc.get("oldCycles"));
        assertNull(gc.get("gcTimeRatio"));
        assertNull(gc.get("maxHeapBytes"));
        assertNull(doc.get("threadsStarted"));
        final Map<String, Object> t = map(doc, "throwables");
        assertNull(t.get("created"));
        assertNull(t.get("createdNanos"));
        assertNull(t.get("perSecond"));
        assertNull(t.get("events"));
        assertNull(t.get("classesFound"));
        final String html = Html.health(empty, 5);
        assertTrue(html.contains(HealthReport.NO_TRENDS), html);
        assertTrue(html.contains("jdk.ExceptionStatistics was not recorded twice"), html);
    }

    @SuppressWarnings("unchecked") // the test knows the document's shape
    static Map<String, Object> object(final Object o) {
        return (Map<String, Object>) o;
    }

    static Map<String, Object> map(final Map<String, Object> doc, final String name) {
        return object(doc.get(name));
    }

    @SuppressWarnings("unchecked") // the test knows the document's shape
    static List<Object> list(final Map<String, Object> doc, final String name) {
        return (List<Object>) doc.get(name);
    }

    @Test
    void aReportIsOnlyThereAfterARecordingWasRead() {
        assertThrows(IllegalStateException.class, () -> new HealthCollector().report());
    }

    @Test
    void aSeriesFormatsInItsOwnUnitAndADashForNothing() {
        final Series bytes = new Series("b", Series.Unit.BYTES, 1, 0, 0, 0, 0, 0, Double.NaN, Double.NaN);
        assertEquals("—", bytes.format(Double.NaN));
        assertEquals("2.00 KB", bytes.format(2000));
        assertEquals("12", new Series("c", Series.Unit.COUNT, 1, 0, 0, 0, 0, 0, 0, 0).format(12));
        assertEquals("12.5%", new Series("f", Series.Unit.FRACTION, 1, 0, 0, 0, 0, 0, 0, 0).format(0.125));
        final HealthReport.Throwables none = new HealthReport.Throwables(Nulls.LONG_NULL, 0, 0, null, List.of(),
                List.of(), Map.of());
        assertTrue(Double.isNaN(none.rate()));
        assertFalse(none.errors().containsKey("x"));
    }

    static Finding finding(final Finding.Kind kind) {
        return report.findings().stream().filter(f -> f.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError(kind + " not in " + report.findings()));
    }
}
