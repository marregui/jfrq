// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport;
import dev.jfrq.core.health.HealthReport.Series;
import dev.jfrq.core.health.ThreadCpu;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Glob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTML and JSON of what {@code health} and {@code info} gained: native memory, the
 * threads' CPU, the warnings, several recordings side by side, and how threads were started.
 * Hand-built reports, so every section is there on demand.
 */
class HealthAndStartsRenderingTest {

    static final long S = 1_000_000_000L;
    static final long MB = 1_000_000L;

    static RecordingInfo info(final String file) {
        return new RecordingInfo(Path.of("runs", file), new Interval(10 * S, 110 * S), 1, Map.of(),
                Map.of("jdk.JavaExceptionThrow", Map.of("enabled", "false")), Set.of(), List.of("a file warning"));
    }

    static Series bytes(final String name, final double start, final double end) {
        return new Series(name, Series.Unit.BYTES, 9, start, end, start, end, end, start, end);
    }

    /** A report with every new section filled, and as many NMT categories as asked. */
    static HealthReport report(final String file, final int categories) {
        final List<Series> nmt = new ArrayList<>();
        nmt.add(bytes("Total", 1200 * MB, 1800 * MB));
        for (int i = 0; i < categories; i++) {
            nmt.add(bytes("Category " + i, 100 * MB - i, 200 * MB - i));
        }
        return new HealthReport(info(file), List.of(), new HealthReport.Gc(Map.of("G1New", 4L),
                Map.of("G1 Evacuation Pause", 4L), 0, S / 50, 30 * MB, 12, Nulls.LONG_NULL, Nulls.LONG_NULL),
                List.of(bytes(HealthReport.HEAP_AFTER_GC, 20 * MB, 400 * MB)), new HealthReport.Threads(7, 30),
                new HealthReport.Throwables(500, 50 * S, 0, null, List.of(), List.of(), Map.of()),
                new ThreadCpu.Result(Map.of(), 0.086, 100, 10), nmt, List.of("the JVM's figure is wrong"));
    }

    @Test
    void healthCarriesNativeMemoryThreadCpuAndItsWarnings() {
        final HealthReport r = report("n1.jfr", 3);
        final String html = Html.health(r, 2);
        assertTrue(html.contains("<li>the JVM's figure is wrong</li>"), html);
        assertTrue(html.contains("Native memory ("), html);
        assertTrue(html.contains("Category 1") && !html.contains("Category 2"), "the total and --top categories");
        assertTrue(html.contains("Java threads used 8.6% of the JVM's CPUs"), html);
        assertTrue(html.contains("The floor of heap after GC rose by"), html);
        assertTrue(html.contains(HealthReport.ENABLE_THROWS), html);

        final Map<String, Object> doc = JsonParser.object(Json.health(r, 2, "test"));
        assertEquals(List.of("the JVM's figure is wrong"), doc.get("warnings"));
        assertEquals(3, JsonTest.list(doc, "nativeMemory").size());
        assertEquals("Total", JsonTest.list(doc, "nativeMemory").getFirst().get("series"));
        assertEquals(3L, doc.get("nativeMemoryCategories"));
        final Map<String, Object> cpu = JsonTest.map(doc, "threadCpu");
        assertEquals(0.086, (double) cpu.get("share"), 1e-12);
        assertEquals(10L, cpu.get("readingsLeftOut"));
        assertEquals(100L, cpu.get("readings"));
        assertEquals(HealthReport.ENABLE_THROWS, JsonTest.map(doc, "throwables").get("enableWith"));
    }

    @Test
    void severalRecordingsAreOneTableAndOneDocument() {
        final List<HealthReport> reports = List.of(report("n1.jfr", 0), report("edge.jfr", 0));
        final String html = Html.healthCompared(reports);
        assertTrue(html.contains("<title>jfrq health of 2 recordings"), html);
        assertTrue(html.indexOf("<td>n1.jfr</td>") < html.indexOf("<td>edge.jfr</td>"), html);
        assertTrue(html.contains("Heap after GC, floors"), html);
        // Each recording's own warnings once, in its own section; the page is about none of them alone.
        assertEquals(2, html.split("<li>a file warning</li>", -1).length - 1, html);
        assertTrue(!html.contains("<dt>Recording</dt><dd>runs/n1.jfr</dd>\n<dt>Span"), html);
        assertTrue(html.contains("<title>jfrq health of 2 recordings · n1.jfr, edge.jfr</title>"), html);

        final Map<String, Object> doc = JsonParser.object(Json.healthCompared(reports, 5, "test"));
        assertEquals("health", doc.get("command"));
        assertNull(doc.get("recording"));
        final List<Map<String, Object>> each = JsonTest.list(doc, "reports");
        assertEquals(2, each.size());
        assertEquals("edge.jfr", JsonTest.map(each.get(1), "recording").get("file"));
        assertEquals(List.of("the JVM's figure is wrong"), each.get(1).get("warnings"));
    }

    @Test
    void infoSaysHowTheThreadsWereStarted() {
        final ThreadRef parent = new ThreadRef(9, "submitter");
        final Stack stack = new Stack(List.of(new Frame("java.lang.Thread", "start", 1, false),
                new Frame("com.example.Pool", "grow", 2, false)), false);
        final List<ThreadCensus.Start> starts = List.of(
                new ThreadCensus.Start(20 * S, new ThreadRef(1, "w-1"), parent, stack, false),
                new ThreadCensus.Start(21 * S, new ThreadRef(2, "w-2"), null, Stack.EMPTY, true));
        final ThreadCensus.Result census = new ThreadCensus.Result(null, null, null, null, null, Set.of(), starts,
                ThreadCpu.Result.UNKNOWN);
        final RecordingSummary.Starts s = RecordingSummary.starts(census, Glob.of("w-*"), 2);
        final RecordingInfo info = info("rec.jfr");
        final String html = Html.info(info, census, s);
        assertTrue(html.contains("Starts of w-*"), html);
        assertTrue(html.contains("2 starts of 2 threads"), html);
        assertTrue(html.contains("Created by: " + RecordingSummary.CREATOR_RULE), html);
        assertTrue(html.contains("com.example.Pool.grow"), html);

        final Map<String, Object> doc = JsonParser.object(Json.info(info, census, s, "test"));
        final Map<String, Object> js = JsonTest.map(doc, "starts");
        assertEquals("w-*", js.get("glob"));
        assertEquals(2L, js.get("starts"));
        assertEquals(1L, js.get("attached"));
        assertEquals(10L * S, js.get("firstOffsetNanos"));
        assertEquals(2L, js.get("creatorsFound"));
        // A tie of one start each is broken by site name, and '<' sorts before a letter.
        assertEquals(RecordingSummary.ATTACHED, JsonTest.list(js, "creators").getFirst().get("site"));
        assertEquals(List.of(), JsonTest.list(js, "creators").getFirst().get("parents"));
        assertEquals(List.of("submitter"), JsonTest.list(js, "creators").get(1).get("parents"));
        assertEquals(1, JsonTest.list(JsonTest.map(JsonParser.object(Json.info(info, census,
                RecordingSummary.starts(census, Glob.of("w-*"), 1), "test")), "starts"), "creators").size(), "--top 1");
        // One second apart: one in any 100 ms, and one in any second, since a window is half open.
        assertEquals(1L, JsonTest.list(js, "peaks").getFirst().get("count"));
        assertEquals(1L, JsonTest.list(js, "peaks").get(1).get("count"));

        final RecordingSummary.Starts none = RecordingSummary.starts(census, Glob.of("x-*"), 1);
        final Map<String, Object> empty = JsonTest.map(JsonParser.object(Json.info(info, census, none, "test")), "starts");
        assertNull(empty.get("first"));
        assertNull(empty.get("firstOffsetNanos"));
        assertNull(JsonTest.list(empty, "peaks").getFirst().get("start"));
        assertTrue(Html.info(info, census, none).contains("no jdk.ThreadStart of a thread matching x-*"));
    }

    /** Seven starting threads, in name order: five entries once a pool is folded, one past the four shown. */
    static final List<ThreadRef> PARENTS = List.of(new ThreadRef(7, "Signal Dispatcher"), new ThreadRef(6, "alpha"),
            new ThreadRef(5, "main"), new ThreadRef(4, "pool-10-thread-1"), new ThreadRef(3, "pool-2-thread-1"),
            new ThreadRef(2, "pool-2-thread-2"), new ThreadRef(1, "reaper"));

    /** One site that each of {@code parents} ran once, in the order given. */
    static ThreadCensus.Result startedFrom(final List<ThreadRef> parents) {
        final Stack stack = new Stack(List.of(new Frame("java.lang.Thread", "start", 1, false),
                new Frame("com.example.Pool", "grow", 2, false)), false);
        final List<ThreadCensus.Start> starts = new ArrayList<>();
        for (int i = 0; i < parents.size(); i++) {
            starts.add(new ThreadCensus.Start((20 + i) * S, new ThreadRef(100 + i, "w-" + i), parents.get(i), stack,
                    false));
        }
        return new ThreadCensus.Result(null, null, null, null, null, Set.of(), starts, ThreadCpu.Result.UNKNOWN);
    }

    @Test
    void theStartingThreadsPrintInNameOrderWhicheverOrderTheyStartedIn() {
        // The parents were a set salted per JVM: the same file listed them, and folded them, differently per run.
        final RecordingInfo info = info("rec.jfr");
        final ThreadCensus.Result forward = startedFrom(PARENTS);
        final ThreadCensus.Result backward = startedFrom(PARENTS.reversed());
        final String html = Html.info(info, forward, RecordingSummary.starts(forward, Glob.of("w-*"), 5));
        assertEquals(html, Html.info(info, backward, RecordingSummary.starts(backward, Glob.of("w-*"), 5)));
        assertTrue(html.contains("Signal Dispatcher, alpha, main, pool-N-thread-N* (3 threads) (+1 more)"), html);
        final List<String> names = PARENTS.stream().map(ThreadRef::name).sorted().toList();
        for (final ThreadCensus.Result census : List.of(forward, backward)) {
            final Map<String, Object> js = JsonTest.map(JsonParser.object(Json.info(info, census,
                    RecordingSummary.starts(census, Glob.of("w-*"), 5), "test")), "starts");
            assertEquals(names, JsonTest.list(js, "creators").getFirst().get("parents"));
        }
        // Under the cap nothing folds, and the order is still the names'.
        final ThreadCensus.Result few = startedFrom(List.of(PARENTS.get(4), PARENTS.get(1), PARENTS.get(2)));
        assertTrue(Html.info(info, few, RecordingSummary.starts(few, Glob.of("w-*"), 5))
                .contains("alpha, main, pool-2-thread-1"));
    }
}
