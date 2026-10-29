// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.sun.management.ThreadMXBean;
import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllocationTest {

    static final long S = 1_000_000_000L;
    /** Where the allocating tasks write, so the allocation is not optimised away. */
    static volatile Object sink;
    static final Stack SITE_A = new Stack(List.of(new Frame("dev.app.A", "alloc", 1, "JIT compiled")), false);
    static final Stack SITE_B = new Stack(List.of(new Frame("dev.app.B", "alloc", 2, "JIT compiled")), false);

    static RecordingInfo info(final String name, final long seconds) {
        return new RecordingInfo(Path.of(name), new Interval(0, seconds * S), 1, Map.of(), Map.of(), Set.of(), List.of());
    }

    static AllocationReport report(final String name, final long seconds, final Map<String, Long> byThread, final Map<String, Long> byClass,
                                   final Map<Stack, Long> bySite) {
        final long total = byThread.values().stream().mapToLong(Long::longValue).sum();
        return new AllocationReport(info(name, seconds), "jdk.ObjectAllocationSample", total, 100, 100, Map.of(), byThread, byClass,
                bySite, Map.of("worker", byClass), Map.of("worker", bySite), support(byThread, byClass, bySite));
    }

    /** One sample per key unless a test says otherwise: enough for the counts to be present. */
    static AllocationReport.Support support(final Map<String, Long> byThread, final Map<String, Long> byClass,
                                            final Map<Stack, Long> bySite) {
        final Map<String, Long> threads = new HashMap<>();
        byThread.forEach((k, _) -> threads.put(k, 1L));
        final Map<String, Long> classes = new HashMap<>();
        byClass.forEach((k, _) -> classes.put(k, 1L));
        final Map<Stack, Long> sites = new HashMap<>();
        bySite.forEach((k, _) -> sites.put(k, 1L));
        return new AllocationReport.Support(threads, classes, sites);
    }

    @Test
    void estimateIsMeasuredAgainstTheJvmCounters() {
        final AllocationReport none = report("a.jfr", 1, Map.of("worker", 1000L), Map.of("[B", 1000L), Map.of());
        assertFalse(none.hasCounters());
        assertEquals(0.0, none.estimateError(), 0.0);
        assertTrue(none.counted("worker").isEmpty());
        // Only the counted threads take part in the comparison: the short-lived one is not held against it.
        final AllocationReport some = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample", 1570, 100, 100,
                Map.of("worker", 1000L), Map.of("worker", 1070L, "short-lived", 500L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        assertTrue(some.hasCounters());
        assertEquals(1000, some.countedBytes());
        assertEquals(1070, some.estimatedOnCountedThreads());
        assertEquals(0.07, some.estimateError(), 1e-9);
        assertTrue(some.isEstimateErrorMaterial());
        assertEquals(1000L, some.counted("worker").orElseThrow());
        assertTrue(some.counted("short-lived").isEmpty());
        // The +7 % is a statement about 68 % of the estimate: the short-lived thread has no counter.
        assertEquals(1070.0 / 1570.0, some.countedCoverage(), 1e-9);
        assertEquals(0.0, none.countedCoverage(), 0.0);
        // A counter on a thread that barely allocates says nothing about the estimate.
        final AllocationReport noise = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample", 1_000_000, 100, 100,
                Map.of("main", 20L), Map.of("main", 480L, "worker", 999_520L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        assertTrue(noise.hasCounters());
        assertFalse(noise.isEstimateErrorMaterial());
        // What decides it is the estimate on the counted threads, not their counters: this one's counter grew
        // by 1 GB, but it was sampled for 1 MB of a 201 MB estimate, so the -100 % is about half a percent of it.
        final AllocationReport minority = new AllocationReport(info("a.jfr", 1), "jdk.ObjectAllocationSample",
                201_000_000, 100, 100, Map.of("counted", 1_000_000_000L),
                Map.of("counted", 1_000_000L, "pool-1", 200_000_000L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        assertEquals(1_000_000.0 / 201_000_000.0, minority.countedCoverage(), 1e-9);
        assertFalse(minority.isEstimateErrorMaterial());
    }

    @Test
    void aVirtualThreadLosesItsFirstSampleAndTheReportSaysHowMuch() {
        // A virtual thread's first sample is weighed by its carrier's allocation since the carrier
        // was last sampled, which for a carrier new to the recording is its whole history.
        final ThreadRef worker = new ThreadRef(1, "worker");
        final ThreadRef vt1 = new ThreadRef(2, "vt-1", true);
        final ThreadRef vt2 = new ThreadRef(3, "vt-2", true);
        final AllocationCollector c = new AllocationCollector();
        c.sample(worker, 2 * S, 400, "[B", SITE_A);
        c.sample(worker, S, 100_000, "[B", SITE_A);
        c.sample(vt1, 4 * S, 700, null, SITE_B);
        c.sample(vt1, 3 * S, 5_000_000, null, SITE_B);
        c.sample(vt2, 3 * S, 900, "[B", SITE_B);
        c.finish(info("a.jfr", 10));
        final AllocationReport r = c.report();

        assertEquals(5, r.events());
        assertEquals(2, r.samples());
        assertEquals(1_100, r.totalBytes());
        // Each thread lost its earliest sample by time, not by arrival.
        assertEquals(Map.of("worker", 400L, "vt-1", 700L), r.byThread());
        assertEquals(Map.of(SITE_A, 400L, SITE_B, 700L), r.bySite());
        assertEquals(1, r.support().thread("vt-1"));
        // The platform thread's first sample is not in the count: only the virtual threads' are.
        assertEquals(new AllocationReport.Dropped(2, 5_000_900), r.virtualFirsts());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().getFirst().contains("2 first samples of virtual threads, 5.00 MB, not counted"),
                r.warnings().getFirst());
    }

    @Test
    void aLaterVirtualSampleHeavierThanEverythingCountedIsCarrierHistory() {
        // A virtual thread that resumed on a carrier not sampled in the recording carries that
        // carrier's history on a later sample; no sample can weigh more than every counter grew by.
        final ThreadRef carrier = new ThreadRef(1, "ForkJoinPool-1-worker-1");
        final ThreadRef vt1 = new ThreadRef(2, "vt-1", true);
        final AllocationCollector c = new AllocationCollector();
        c.counter(carrier, S, 1_000_000_000L);
        c.counter(carrier, 9 * S, 1_010_000_000L);
        c.sample(vt1, 2 * S, 300, "[B", SITE_B);
        c.sample(vt1, 3 * S, 4_000_000, "[B", SITE_B);
        c.sample(vt1, 4 * S, 900_000_000L, "[B", SITE_A);
        c.sample(vt1, 5 * S, 9_000_000, "[B", SITE_B);
        c.finish(info("a.jfr", 10));
        final AllocationReport r = c.report();

        // The first sample is a first, the 900 MB one history, the two under the 10 MB counted stay.
        assertEquals(13_000_000, r.totalBytes());
        assertEquals(2, r.samples());
        assertEquals(Map.of(SITE_B, 13_000_000L), r.bySite());
        assertEquals(new AllocationReport.Dropped(1, 300), r.virtualFirsts());
        assertEquals(new AllocationReport.Dropped(1, 900_000_000L), r.carrierHistory());
        assertTrue(r.warnings().getFirst().contains("and 1 later one heavier than the recording's counted allocation, "
                + "900 MB"), r.warnings().getFirst());
        assertEquals("", r.droppedNote());
    }

    @Test
    void withoutCountersEveryLaterVirtualSampleIsKept() {
        // The bound is the counters' total; a recording without them has none, and nothing more is left out.
        final ThreadRef vt1 = new ThreadRef(2, "vt-1", true);
        final AllocationCollector c = new AllocationCollector();
        c.sample(vt1, 2 * S, 300, "[B", SITE_B);
        c.sample(vt1, 4 * S, 900_000_000L, "[B", SITE_A);
        c.finish(info("a.jfr", 10));
        assertEquals(900_000_000L, c.report().totalBytes());
        assertEquals(AllocationReport.Dropped.NONE, c.report().carrierHistory());
    }

    @Test
    void theCounterIsSetAgainstTheSamplesOfItsOwnStretch() {
        // Field case: counters are read at chunk ends. A pool thread that started after the first
        // chunk and allocated before its first reading had those bytes in the estimate and not in
        // its counter; set against the whole file that read as an estimate 28 % high.
        final ThreadRef steady = new ThreadRef(1, "steady");
        final ThreadRef late = new ThreadRef(2, "late");
        final ThreadRef gone = new ThreadRef(3, "gone");
        final AllocationCollector c = new AllocationCollector();
        // Running all along, read at both ends: its first sample dropped, one before the first
        // reading kept in the estimate but outside the counter's stretch.
        c.sample(steady, 0, 50, "[B", SITE_A);
        c.sample(steady, S / 2, 70, "[B", SITE_A);
        c.counter(steady, S, 1_000);
        c.sample(steady, 2 * S, 3_000, "[B", SITE_A);
        c.counter(steady, 10 * S, 4_000);
        // Started at 1 s, first read at 5 s: its counter was zero at its start, and its first
        // sample, all of it in the window, is kept.
        c.started(late, S);
        c.sample(late, 2 * S, 200, "[B", SITE_A);
        c.sample(late, 3 * S, 100, "[B", SITE_A);
        c.counter(late, 5 * S, 300);
        c.counter(late, 10 * S, 300);
        // Read twice, then allocated a lot past its last reading.
        c.counter(gone, 0, 0);
        c.sample(gone, S, 100, "[B", SITE_B);
        c.sample(gone, 3 * S, 100, "[B", SITE_B);
        c.counter(gone, 4 * S, 100);
        c.sample(gone, 6 * S, 900, "[B", SITE_B);
        c.finish(info("a.jfr", 10));
        final AllocationReport r = c.report();

        assertEquals(Map.of("steady", 3_000L, "late", 300L, "gone", 100L), r.countedByThread());
        assertEquals(Map.of("steady", 3_000L, "late", 300L, "gone", 100L), r.estimatedWhileCounted());
        assertEquals(3_400, r.estimatedOnCountedThreads());
        assertEquals(0.0, r.estimateError());
        assertEquals(Map.of("steady", 3_070L, "late", 300L, "gone", 1_000L), r.byThread());
        // A row shows its counter only when the counter's stretch holds nearly all of the row.
        assertEquals(Optional.of(3_000L), r.counted("steady"));
        assertEquals(Optional.of(300L), r.counted("late"));
        assertEquals(Optional.empty(), r.counted("gone"));
        // Two first samples left out, and the line says why the count is short of the events.
        assertEquals(6, r.samples());
        assertEquals(8, r.events());
        assertEquals("; the first sample of each of 2 threads not seen starting in the recording is left out, "
                + "as its weight can reach back before it", r.droppedNote());
    }

    @Test
    void aThreadStartAfterTheThreadWasSeenIsNotItsBirth() {
        // Field case: the JVM that starts a recording writes main's ThreadStart 5.13 s in, after
        // main's first counter reading and first sample. Taken at its word, main's start-up
        // allocation stayed in the estimate and its counter ran from zero.
        final ThreadRef main = new ThreadRef(1, "main");
        final AllocationCollector c = new AllocationCollector();
        c.counter(main, 0, 20_000_000);
        c.sample(main, 0, 20_000_000, "[B", SITE_A);
        c.sample(main, 2 * S, 400, "[B", SITE_A);
        c.started(main, 5 * S);
        c.counter(main, 10 * S, 20_000_400);
        c.finish(info("a.jfr", 10));
        final AllocationReport r = c.report();
        assertEquals(Map.of("main", 400L), r.byThread());
        assertEquals(Map.of("main", 400L), r.countedByThread());
        assertEquals(Map.of("main", 400L), r.estimatedWhileCounted());
        assertTrue(r.droppedNote().contains("the first sample of 1 thread not seen starting"), r.droppedNote());
    }

    @Test
    void theTlabEventsAreSetAgainstTheirCounterOverItsOwnStretchToo() {
        // Born at 1 s, read once at 5 s, then allocating on to 8 s: the counter speaks for the
        // first 500 bytes only, and the row's 1.4 KB is not what it counted.
        final ThreadRef worker = new ThreadRef(1, "worker");
        final AllocationCollector c = new AllocationCollector();
        c.started(worker, S);
        c.tlab(worker, 2 * S, 500, "[B", SITE_A);
        c.counter(worker, 5 * S, 500);
        c.tlab(worker, 8 * S, 900, "[B", SITE_A);
        c.finish(info("a.jfr", 10));
        final AllocationReport r = c.report();
        assertEquals(AllocationCollector.IN_TLAB + " + " + AllocationCollector.OUTSIDE_TLAB, r.source());
        assertEquals(Map.of("worker", 1_400L), r.byThread());
        assertEquals(Map.of("worker", 500L), r.countedByThread());
        assertEquals(Map.of("worker", 500L), r.estimatedWhileCounted());
        assertEquals(Optional.empty(), r.counted("worker"));
        assertEquals("", r.droppedNote());
    }

    @Test
    void withoutVirtualThreadsThereIsNoWarning() {
        final AllocationCollector c = new AllocationCollector();
        c.sample(new ThreadRef(1, "worker"), S, 400, "[B", SITE_A);
        c.sample(new ThreadRef(1, "worker"), 2 * S, 400, "[B", SITE_A);
        c.finish(info("a.jfr", 10));
        assertEquals(AllocationReport.Dropped.NONE, c.report().virtualFirsts());
        assertEquals(List.of(), c.report().warnings());
    }

    @Test
    void virtualThreadsDoNotReportTheirCarriersHistory(@TempDir final Path dir) throws Exception {
        // The carriers allocate a gigabyte before the recording, with the sampler off, so none of
        // them has been sampled since; inside it, virtual threads allocate a sixteenth of that. A
        // virtual thread's first sample on a carrier carries the carrier's gigabyte.
        final int tasks = 64;
        allocateOnVirtualThreads(tasks, 16 << 20);
        final ThreadMXBean threads =
                (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long[] allocated = new long[2];
        final Path file = JfrFixtures.record(dir, "vthreads",
                r -> r.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s"), () -> {
                    allocated[0] = threads.getTotalThreadAllocatedBytes();
                    allocateOnVirtualThreads(tasks, 1 << 20);
                    allocated[1] = threads.getTotalThreadAllocatedBytes();
                });
        final AllocationCollector collector = new AllocationCollector();
        JfrReader.read(file, collector);
        final AllocationReport report = collector.report();

        // Every platform thread's allocation while the recording ran, carriers included: an
        // upper bound on what the virtual threads allocated in it.
        final long truth = allocated[1] - allocated[0];
        long estimate = 0;
        for (final Map.Entry<String, Long> e : report.byThread().entrySet()) {
            if (e.getKey().startsWith("vt-")) {
                estimate += e.getValue();
            }
        }
        assertTrue(estimate <= 2 * truth, "estimated " + estimate + " for " + truth + " allocated");
        // The history was there to be counted, and the report says it was not.
        assertTrue(report.virtualFirsts().samples() > 0);
        assertTrue(report.virtualFirsts().bytes() > truth, report.virtualFirsts() + " against " + truth);
        assertTrue(report.warnings().getFirst().startsWith("allocation on virtual threads is under-counted"));
    }

    private static void allocateOnVirtualThreads(final int tasks, final int bytesPerTask) {
        final ThreadFactory factory = Thread.ofVirtual().name("vt-", 0).factory();
        try (final ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            for (int i = 0; i < tasks; i++) {
                executor.submit(() -> {
                    for (int k = 0; k < bytesPerTask / 1024; k++) {
                        sink = new byte[1024 - 16];
                    }
                });
            }
        }
    }

    @Test
    void everyPathThroughOneMethodIsOneRow() {
        // The same application method reached down three different library paths, and
        // allocating on two of its own lines: one logical site, which ranked one row per
        // path reads as three rows of a third the size.
        final AllocationReport r = report("a.jfr", 1, Map.of("worker", 600L), Map.of(),
                Map.of(parsePath("java.lang.String", "substring", 453), 300L,
                        parsePath("java.util.Arrays", "copyOfRange", 453), 200L,
                        parsePath("java.lang.String", "substring", 454), 100L));

        final List<AllocationReport.SiteRow> folded = r.sites(SiteKey.culpritMethod(), 10);
        assertEquals(1, folded.size());
        final AllocationReport.SiteRow row = folded.getFirst();
        assertEquals("org.lib.NodeId.parse", row.label());
        assertEquals(600, row.bytes());
        assertEquals(1.0, row.share(), 1e-9);
        assertEquals(3, row.stacks());
        // The support is the whole row's, not the representative stack's: one sample each
        // above, so a row that sums three stacks rests on three samples, not on one.
        assertEquals(3, row.samples());
        // The stack printed under the row is the biggest contributor, not an arbitrary one.
        assertEquals(300, r.bySite().get(row.stack()));
        // The raw map still keeps them apart, which is what --baseline matches on.
        assertEquals(3, r.sitesByStack(10).size());
    }

    @Test
    void appPrefixesAttributeToTheCallerThatOwnsThePath() {
        // Two library methods on the same application call site. Ranked by what allocated,
        // they are two rows in a library nobody here can change; ranked by --app they are
        // one row naming the line that called it.
        final Frame caller = new Frame("com.app.Node", "id", 99, "JIT compiled");
        final Stack parse = new Stack(List.of(new Frame("java.lang.String", "substring", 2904, "JIT compiled"),
                new Frame("org.lib.NodeId", "parse", 453, "JIT compiled"), caller), false);
        final Stack uint = new Stack(List.of(new Frame("org.lib.UInteger", "valueOf", 131, "JIT compiled"),
                new Frame("org.lib.NodeId", "parse", 459, "JIT compiled"), caller), false);
        final AllocationReport r = report("a.jfr", 1, Map.of("worker", 300L), Map.of(),
                Map.of(parse, 200L, uint, 100L));

        assertEquals(2, r.sites(SiteKey.culpritMethod(), 10).size());
        final List<AllocationReport.SiteRow> byApp = r.sites(SiteKey.inPackages(List.of("com.app")), 10);
        assertEquals(1, byApp.size());
        assertEquals("com.app.Node.id", byApp.getFirst().label());
        assertEquals(300, byApp.getFirst().bytes());
        assertEquals(2, byApp.getFirst().samples());
        // A stack that never enters the named packages keeps its own name rather than vanishing.
        final List<AllocationReport.SiteRow> noMatch = r.sites(SiteKey.inPackages(List.of("com.nothing")), 10);
        assertEquals(2, noMatch.size());
        assertEquals("org.lib.NodeId.parse", noMatch.getFirst().label());
    }

    @Test
    void packageRootsNameWhatAppCanBePointedAt() {
        final Stack app = new Stack(List.of(new Frame("com.app.Node", "id", 99, "JIT compiled")), false);
        final Stack lib = new Stack(List.of(new Frame("org.lib.NodeId", "parse", 453, "JIT compiled")), false);
        final Stack jdk = new Stack(List.of(new Frame("java.lang.String", "substring", 2904, "JIT compiled")), false);
        final AllocationReport r = report("a.jfr", 1, Map.of("worker", 600L), Map.of(),
                Map.of(app, 300L, lib, 200L, jdk, 100L));

        final List<AllocationReport.Row<String>> roots = r.packageRoots(10);
        // Ranked by bytes, and a stack that is JDK code all the way down names no package.
        assertEquals(List.of("com.app", "org.lib"), roots.stream().map(AllocationReport.Row::key).toList());
        assertEquals(300, roots.getFirst().bytes());
    }

    /** A stack allocating inside {@code org.lib.NodeId.parse} on {@code line}, reached through {@code type.method}. */
    private static Stack parsePath(final String type, final String method, final int line) {
        return new Stack(List.of(new Frame(type, method, 2904, "JIT compiled"),
                new Frame("org.lib.NodeId", "parse", line, "JIT compiled"),
                new Frame("com.app.Node", "id", 99, "JIT compiled")), false);
    }

    @Test
    void ranksAndShares() {
        final AllocationReport r = report("a.jfr", 10,
                Map.of("worker", 800L, "loop", 200L),
                Map.of("[B", 900L, "java.lang.Long", 100L),
                Map.of(SITE_A, 700L, SITE_B, 300L));
        assertEquals(1000, r.totalBytes());
        assertEquals(100.0, r.rate());
        assertEquals(10.0, r.seconds());
        final List<AllocationReport.Row<String>> threads = r.threads(10);
        assertEquals("worker", threads.getFirst().key());
        assertEquals(0.8, threads.getFirst().share(), 1e-9);
        assertEquals(800, threads.getFirst().bytes());
        assertEquals(1, r.threads(1).size());
        assertEquals("[B", r.classes(10).getFirst().key());
        assertEquals(SITE_A, r.sitesByStack(10).getFirst().key());
        assertEquals(SITE_A, r.sitesOf("worker", 1).getFirst().key());
        assertEquals("[B", r.classesOf("worker", 1).getFirst().key());
        assertTrue(r.classesOf("nobody", 5).isEmpty());
        assertTrue(r.sitesOf("nobody", 5).isEmpty());
    }

    @Test
    void zeroLengthRecordingDoesNotDivideByZero() {
        final AllocationReport r = report("z.jfr", 0, Map.of("t", 10L), Map.of(), Map.of());
        assertTrue(r.rate() > 0);
        final AllocationReport empty = report("e.jfr", 5, Map.of(), Map.of(), Map.of());
        assertEquals(0, empty.totalBytes());
        assertTrue(empty.threads(5).isEmpty());
    }

    @Test
    void diffComparesRatesNotTotals() {
        final AllocationReport before = report("before.jfr", 10,
                Map.of("worker", 1000L, "gone", 500L),
                Map.of("[B", 1500L),
                Map.of(SITE_A, 1500L));
        final AllocationReport after = report("after.jfr", 20,
                Map.of("worker", 1000L, "new", 400L),
                Map.of("[B", 1000L, "java.lang.Long", 400L),
                Map.of(SITE_A, 1000L, SITE_B, 400L));
        final AllocationDiff diff = new AllocationDiff(before, after);

        final AllocationDiff.Delta<String> total = diff.total();
        assertEquals(150.0, total.beforeRate(), 1e-9);
        assertEquals(70.0, total.afterRate(), 1e-9);
        assertEquals(-80.0, total.delta(), 1e-9);
        assertEquals(-80.0 / 150.0, total.ratio(), 1e-9);

        final List<AllocationDiff.Delta<String>> threads = diff.threads(10);
        assertEquals(3, threads.size());
        // Sorted by absolute change: worker 100 -> 50 (-50), gone 50 -> 0 (-50), new 0 -> 20 (+20).
        assertEquals(20.0, threads.get(2).delta(), 1e-9);
        assertEquals("new", threads.get(2).key());
        assertEquals(Double.POSITIVE_INFINITY, threads.get(2).ratio());
        final AllocationDiff.Delta<String> gone = threads.stream().filter(d -> d.key().equals("gone")).findFirst().orElseThrow();
        assertEquals(-1.0, gone.ratio(), 1e-9);
        assertEquals(2, diff.threads(2).size());

        assertEquals("[B", diff.classes(10).getFirst().key());
        assertEquals(SITE_A, diff.sitesByStack(10).getFirst().key());
        assertEquals(0.0, new AllocationDiff.Delta<>("x", 0.0, 0.0).ratio());
        assertEquals(before, diff.baseline());
        assertEquals(after, diff.current());
    }

    @Test
    void aSiteThatMovedIsOneRowOfTheDiff() {
        // The same method sampled down two paths, gone by the second recording. Compared per
        // stack it is two rows and neither is the change; compared by the fold it is one.
        final Stack viaSubstring = parsePath("java.lang.String", "substring", 453);
        final Stack viaCopy = parsePath("java.util.Arrays", "copyOfRange", 454);
        final AllocationReport before = report("before.jfr", 1, Map.of("worker", 900L), Map.of(),
                Map.of(viaSubstring, 600L, viaCopy, 300L));
        final AllocationReport after = report("after.jfr", 1, Map.of("worker", 0L), Map.of(), Map.of());
        final AllocationDiff diff = new AllocationDiff(before, after);

        assertEquals(2, diff.sitesByStack(10).size());
        final List<AllocationDiff.Delta<AllocationDiff.Site>> folded = diff.sites(SiteKey.culpritMethod(), 10);
        assertEquals(1, folded.size());
        final AllocationDiff.Delta<AllocationDiff.Site> row = folded.getFirst();
        assertEquals("org.lib.NodeId.parse", row.key().label());
        assertEquals(900.0, row.beforeRate(), 1e-9);
        assertEquals(0.0, row.afterRate(), 1e-9);
        assertEquals(-1.0, row.ratio(), 1e-9);
        // Both sides' support, so a change on three samples cannot read as a change on 900.
        assertEquals(2, row.key().beforeSamples());
        assertEquals(0, row.key().afterSamples());
        // The stack under the row comes from the side that still has the site; here only one does.
        assertEquals(viaSubstring, row.key().stack());

        // A site only the current recording has is compared against zero and keeps its stack.
        final AllocationDiff appeared = new AllocationDiff(after, before);
        final AllocationDiff.Delta<AllocationDiff.Site> grown = appeared.sites(SiteKey.culpritMethod(), 10).getFirst();
        assertEquals(0.0, grown.beforeRate(), 1e-9);
        assertEquals(900.0, grown.afterRate(), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, grown.ratio());
        assertEquals(viaSubstring, grown.key().stack());
    }
}
