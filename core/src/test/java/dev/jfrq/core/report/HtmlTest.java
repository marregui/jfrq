// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOOP = new ThreadRef(1, "event-loop-1");
    static final ThreadRef HOLDER = new ThreadRef(2, "house<keeper>");
    static final Stack STACK = new Stack(List.of(new Frame("dev.app.A", "b", 1, "JIT compiled")), false);

    static RecordingInfo info() {
        return new RecordingInfo(Path.of("dir", "rec.jfr"), new Interval(0, 2_000 * MS), 1, Map.of(),
                Map.of("jdk.JavaMonitorEnter", Map.of("threshold", "10 ms")), Set.of(), List.of());
    }

    @Test
    void stallsPageHasEverySectionAndEscapesText() {
        final Stall s = new Stall(LOOP, new Interval(100 * MS, 400 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on monitor X held by house<keeper>", STACK, Stall.Evidence.EVENT, 0);
        final Pause gc = new Pause(new Interval(900 * MS, 1000 * MS), PauseKind.GC, "G1 Young");
        final StallReport r = new StallReport(info(), 50 * MS,
                List.of(new StallReport.ThreadSummary(LOOP, 10, 10 * MS, 20 * MS, 1, 300 * MS, 300 * MS)),
                List.of(s), List.of(gc), List.of("a & warning"));
        final String html = Html.stalls(r, 10);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>jfrq stalls · rec.jfr</title>"));
        assertTrue(html.contains("house&lt;keeper&gt;"));
        assertFalse(html.contains("house<keeper>"));
        assertTrue(html.contains("a &amp; warning"));
        for (final String section : List.of("Threads", "By verdict", "Timeline", "Stalls, longest first", "JVM-wide pauses")) {
            assertTrue(html.contains("<h2>" + section), section);
        }
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("BLOCKED_MONITOR"));
        assertTrue(html.contains("G1 Young"));
        assertTrue(html.contains("dev.app.A.b(A.java:1)"));
        assertTrue(html.contains("class=\"legend\""));
        assertTrue(html.endsWith("</html>\n"));
    }

    @Test
    void theUnseenBoundLeadsThePageAndItsReasonsCloseIt() {
        final StallReport r = new StallReport(info(), 50 * MS,
                List.of(new StallReport.ThreadSummary(LOOP, 460, 0, 250 * MS, 0, 0, 0, 750 * MS,
                        StallReport.Sight.NATIVE_SAMPLER, 250 * MS)),
                List.of(), List.of(), List.of("a warning"));
        final String html = Html.stalls(r, 10);
        final int bound = html.indexOf("unexplained stalls under 750 ms cannot be seen on 1 of 1 threads (why: the last section)");
        final int reasons = html.indexOf("<h2>Unseen: why, and the setting that helps</h2>");
        assertTrue(bound >= 0 && bound < html.indexOf("a warning"), html);
        assertTrue(reasons > html.indexOf("<h2>Stalls, longest first"), html);
        assertTrue(html.indexOf(StallReport.UNSEEN_SCOPE) > reasons, html);
        assertTrue(html.indexOf("on 1 of 1 threads, a stall no event explains is seen only from 750 ms") > reasons, html);
    }

    @Test
    void theThreadTableTakesTheSameTopAsTheText() {
        final List<StallReport.ThreadSummary> threads = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            threads.add(new StallReport.ThreadSummary(new ThreadRef(i, "loop-" + i), 10, 10 * MS, 20 * MS, i < 4 ? 1 : 0,
                    i < 4 ? i * 100 * MS : 0, i < 4 ? i * 100 * MS : 0));
        }
        final String html = Html.stalls(new StallReport(info(), 50 * MS, threads, List.of(), List.of(), List.of()), 1);
        final String table = html.substring(html.indexOf("Threads, most stalled first"), html.indexOf("By verdict"));
        assertTrue(table.contains("loop-3"), table);
        assertFalse(table.contains("loop-2") || table.contains("loop-1"), table);
        assertTrue(table.contains("2 more that stalled, 1 thread with no stall"), table);
    }

    @Test
    void stallsTableIsCappedAndTheTimelineKeepsTheLongestBoxes() {
        final List<Stall> many = new ArrayList<>();
        for (int i = 0; i < Html.MIN_LISTED + 1; i++) {
            many.add(new Stall(LOOP, new Interval(i * 10 * MS, i * 10 * MS + 5 * MS), Stall.Verdict.SLEEP, "s" + i,
                    Stack.EMPTY, Stall.Evidence.EVENT, 0));
        }
        final StallReport r = new StallReport(info(), MS,
                List.of(new StallReport.ThreadSummary(LOOP, 0, 0, 0, many.size(), 0, 0)), many, List.of(), List.of());
        final String html = Html.stalls(r, 1);
        assertTrue(html.contains("Stalls, longest first (" + Html.MIN_LISTED + " of " + (Html.MIN_LISTED + 1) + ")"));
        // One box per stall in the SVG while under the cap.
        assertEquals(Html.MIN_LISTED + 1, html.split("<title>SLEEP").length - 1);

        // Past the cap, the longest boxes are drawn and the file stays bounded.
        final List<Stall> flood = new ArrayList<>();
        for (int i = 0; i < Html.MAX_BOXES_PER_ROW + 500; i++) {
            final long length = i < 500 ? 9 * MS : MS;
            flood.add(new Stall(LOOP, new Interval(i * 10 * MS, i * 10 * MS + length), Stall.Verdict.SLEEP, "f" + i,
                    Stack.EMPTY, Stall.Evidence.EVENT, 0));
        }
        final StallReport big = new StallReport(info(), MS,
                List.of(new StallReport.ThreadSummary(LOOP, 0, 0, 0, flood.size(), 0, 0)), flood, List.of(), List.of());
        final String capped = Html.stalls(big, 1);
        assertEquals(Html.MAX_BOXES_PER_ROW, capped.split("<title>SLEEP").length - 1);
        for (int i = 0; i < 500; i++) {
            assertTrue(capped.contains("<title>SLEEP 9.00 ms: f" + i + "</title>"), "long box " + i + " dropped");
        }
    }

    @Test
    void infoPageListsEveryEventType() {
        final RecordingInfo info = new RecordingInfo(Path.of("rec.jfr"), new Interval(0, MS), 2,
                Map.of("jdk.ThreadSleep", 3L, "jdk.SocketRead", 7L),
                Map.of("jdk.SocketRead", Map.of("enabled", "true", "threshold", "1 ms", "throttle", "300/s")),
                Set.of(LOOP), List.of());
        final String html = Html.info(info, ThreadCensus.Result.UNKNOWN);
        assertTrue(html.contains("<title>jfrq info"));
        assertTrue(html.contains("<dt>Chunks</dt><dd>2</dd>"));
        assertTrue(html.contains("jdk.ThreadSleep"));
        assertTrue(html.contains("300/s"), html);
        assertTrue(html.indexOf("jdk.SocketRead") < html.indexOf("jdk.ThreadSleep"), "sorted by count");
    }

    @Test
    void infoPageSaysWhatTheTextSays() {
        // The settings lines and the thread families, which the text report prints and the page left out.
        final RecordingInfo info = new RecordingInfo(Path.of("rec.jfr"), new Interval(0, MS), 1,
                Map.of("jdk.SocketWrite", 3L),
                Map.of("jdk.SocketWrite", Map.of("enabled", "true", "threshold", "1 ms"),
                        "jdk.ExecutionSample", Map.of("enabled", "true", "period", "10 ms"),
                        "jdk.ObjectAllocationSample", Map.of("enabled", "true", "throttle", "1000/s")),
                Set.of(new ThreadRef(1, "pool-3-thread-2"), new ThreadRef(2, "pool-3-thread-1"), LOOP),
                List.of());
        final String html = Html.info(info, ThreadCensus.Result.UNKNOWN);
        assertTrue(html.contains("<dt>Threads</dt><dd>3 seen in events</dd>"), html);
        assertTrue(html.contains("<dt>Sampling</dt><dd>ExecutionSample 10.0 ms</dd>"), html);
        assertTrue(html.contains("<dt>Thresholds</dt><dd>SocketWrite 1.00 ms</dd>"), html);
        assertTrue(html.contains("<dt>Throttled</dt><dd>ObjectAllocationSample 1000/s</dd>"), html);
        assertTrue(html.contains("<dt>Allocation</dt><dd>ObjectAllocationSample 1000/s</dd>"), html);
        assertTrue(html.contains("<h2>Threads (the names --thread matches)</h2>"), html);
        assertTrue(html.contains("<tr><td>pool-N-thread-N*</td><td class=\"n\">2</td><td class=\"n\">2</td>"
                + "<td>pool-3-thread-1</td></tr>"), html);
        // A family of one is named by its thread: "event-loop-N" is not a name --thread can match.
        assertTrue(html.contains("<tr><td>event-loop-1</td><td class=\"n\">1</td><td class=\"n\">1</td><td></td></tr>"),
                html);

        // A file without settings says so once, and has no settings lines to show.
        final RecordingInfo bare = new RecordingInfo(Path.of("rec.jfr"), new Interval(0, MS), 1, Map.of(), Map.of(),
                Set.of(), List.of());
        final String page = Html.info(bare, ThreadCensus.Result.UNKNOWN);
        assertTrue(page.contains("<dt>Settings</dt><dd>unknown: the recording has no jdk.ActiveSetting events</dd>"));
        assertFalse(page.contains("<dt>Sampling</dt>"), page);
        assertFalse(page.contains("Threads (the names"), page);
    }

    @Test
    void fileWarningsAppearOnEveryPage() {
        final RecordingInfo damaged = new RecordingInfo(Path.of("cut.jfr"), new Interval(0, MS), 1, Map.of(), Map.of(), Set.of(),
                List.of("the file is truncated: <cut>"));
        final AllocationReport a = new AllocationReport(damaged, "jdk.ObjectAllocationSample", 0, 0, 0, Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE);
        assertTrue(Html.alloc(a, 5, true, SiteKey.culpritMethod()).contains("<li>the file is truncated: &lt;cut&gt;</li>"));
        assertTrue(Html.locks(new ContentionReport(damaged, List.of()), 5, false).contains("class=\"warn\""));
        final StallReport r = new StallReport(damaged, MS, List.of(), List.of(), List.of(), List.of());
        assertTrue(Html.stalls(r, 5).contains("the file is truncated"));
    }

    @Test
    void unexplainedGapsHaveTheirOwnSectionAndSayWhatIsMissing() {
        final Stall gap = new Stall(LOOP, new Interval(0, 474 * MS), Stall.Verdict.UNEXPLAINED, "no evidence",
                Stack.EMPTY, Stall.Evidence.SILENCE, 0);
        final Stall parked = new Stall(LOOP, new Interval(500 * MS, 671 * MS), Stall.Verdict.PARKED, "parked",
                STACK, Stall.Evidence.EVENT, 3);
        final StallReport r = new StallReport(info(), 50 * MS, List.of(), List.of(gap, parked), List.of(), List.of());
        final String html = Html.stalls(r, 10);
        assertTrue(html.contains("<h2>Stalls, longest first"), html);
        assertTrue(html.contains("<h2>Unexplained gaps, longest first"), html);
        assertTrue(html.indexOf("dev.app.A.b") < html.indexOf("Unexplained gaps"), html);
        assertTrue(html.contains("0 jdk.SocketWrite events in 2.00 s"), html);
    }

    @Test
    void aStackIsOnThePageOnceHoweverManyStallsShareIt() {
        final Stall first = new Stall(LOOP, new Interval(0, 176 * MS), Stall.Verdict.BLOCKED_MONITOR, "a", STACK,
                Stall.Evidence.EVENT, 2);
        final Stall second = new Stall(HOLDER, new Interval(0, 175 * MS), Stall.Verdict.BLOCKED_MONITOR, "b", STACK,
                Stall.Evidence.EVENT, 2);
        final StallReport r = new StallReport(info(), 50 * MS, List.of(), List.of(first, second), List.of(), List.of());
        final String html = Html.stalls(r, 10);
        assertEquals(1, html.split("dev.app.A.b").length - 1, html);
        assertTrue(html.contains("same stack as #1"), html);
    }

    @Test
    void aStallsPageWithNoThreadSaysSoAndStillListsThePauses() {
        final Pause gc = new Pause(new Interval(100 * MS, 400 * MS), PauseKind.GC, "Full (gcId 9)");
        final StallReport r = new StallReport(info(), 50 * MS, List.of(), List.of(), List.of(gc),
                List.of("1 matching thread has no samples and no blocking events, so nothing to judge by (VM Thread)"));
        final String html = Html.stalls(r, 10);
        assertTrue(html.contains("No thread matched that has a sample or a blocking event."), html);
        assertTrue(html.contains("JVM-wide pauses"), html);
        assertTrue(html.contains("Full (gcId 9)"), html);
        assertTrue(html.contains("(VM Thread)"), html);
        assertFalse(html.contains("<h2>Threads</h2>"), html);
    }

    @Test
    void stallsPageWithoutPausesOrWarnings() {
        final StallReport r = new StallReport(info(), 50 * MS, List.of(), List.of(), List.of(), List.of());
        final String html = Html.stalls(r, 10);
        assertFalse(html.contains("JVM-wide pauses"));
        assertFalse(html.contains("class=\"warn\""));
    }

    @Test
    void locksPage() {
        final Wait.LockKey key = new Wait.LockKey("dev.app.Registry", 0xabc, Wait.Kind.MONITOR_ENTER);
        final Wait.LockKey store = new Wait.LockKey("dev.app.Store", 0xdef, Wait.Kind.MONITOR_ENTER);
        final ThreadRef flusher = new ThreadRef(3, "flusher");
        final ContentionReport r = new ContentionReport(info(), List.of(
                new Wait(new Interval(100 * MS, 300 * MS), LOOP, key, HOLDER, STACK),
                new Wait(new Interval(150 * MS, 200 * MS), HOLDER, store, flusher, Stack.EMPTY)));
        final String html = Html.locks(r, 10, false);
        assertTrue(html.contains("Locks by total wait"));
        assertTrue(html.contains("Threads by time blocked"));
        assertTrue(html.contains("Convoys"));
        assertTrue(html.contains("Longest waits"));
        assertTrue(html.contains("dev.app.Registry@abc"));
        assertTrue(html.contains("held by house&lt;keeper&gt;"));
        assertTrue(html.contains("jdk.JavaMonitorEnter 10.0 ms"));
        assertTrue(html.contains("class=\"legend\""));
    }

    @Test
    void locksPageSaysWhyNothingIsListed() {
        final Wait.LockKey key = new Wait.LockKey("dev.app.Registry", 0xabc, Wait.Kind.MONITOR_ENTER);
        final List<Wait> one = List.of(new Wait(new Interval(100 * MS, 300 * MS), LOOP, key, HOLDER, STACK));
        final String none = Html.locks(new ContentionReport(info(), List.of()), 10, false);
        assertTrue(none.contains("<p>No contended monitor enters or parks in the recording"), none);
        assertFalse(none.contains("Total blocked time"), none);
        final String filtered = Html.locks(new ContentionReport(info(), one, 0, name -> name.equals("nobody")), 10,
                false);
        assertTrue(filtered.contains("match the filters (--thread, --min, --lock); 1 in the recording."), filtered);

        // A queue a pool worker idles on for the whole window is the page's one section.
        final Stack noWork = new Stack(List.of(
                new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled")), false);
        final Wait.LockKey queue = new Wait.LockKey("java.util.concurrent.LinkedBlockingQueue", 0x1, Wait.Kind.PARK);
        final String idle = Html.locks(new ContentionReport(info(), List.of(
                new Wait(new Interval(0, 2_000 * MS), LOOP, queue, null, noWork))), 10, false);
        assertTrue(idle.contains("<p>No contention: every wait was a worker waiting for work"), idle);
        assertTrue(idle.contains("<h2>Waiting for work: 1 thread parked on an empty queue"), idle);
        assertFalse(idle.contains("Locks by total wait"), idle);
    }

    @Test
    void lockSitesPrintTheStackToTheDepthTheyAreGroupedAt() {
        final List<Frame> frames = new ArrayList<>();
        for (int f = 0; f < 10; f++) {
            frames.add(new Frame("dev.app.Layer" + f, "call", 1, "JIT compiled"));
        }
        final Wait.LockKey queue = new Wait.LockKey("dev.app.Queue", 0x1, Wait.Kind.PARK);
        final ContentionReport r = new ContentionReport(info(), List.of(
                new Wait(new Interval(100 * MS, 300 * MS), LOOP, queue, null, new Stack(frames, false))));
        final String html = Html.locks(r, 10, true);
        final int from = html.indexOf("<h2>Lock sites by total wait");
        final String sites = html.substring(from, html.indexOf("<h2>", from + 1));
        assertTrue(sites.contains("Layer" + (ContentionReport.SITE_FRAMES - 1) + ".call"), sites);
        assertFalse(sites.contains("Layer" + ContentionReport.SITE_FRAMES + ".call"), sites);
        assertTrue(html.contains("Total blocked time</dt><dd>200 ms across 1 wait</dd>"), html);
    }

    @Test
    void allocAndDiffPages() {
        final AllocationReport a = new AllocationReport(info(), "jdk.ObjectAllocationSample", 1000, 10, 10, Map.of("worker", 950L),
                Map.of("worker", 1000L), Map.of("[B", 1000L), Map.of(STACK, 1000L),
                Map.of("worker", Map.of("[B", 1000L)), Map.of("worker", Map.of(STACK, 1000L)),
                new AllocationReport.Support(Map.of("worker", 7L), Map.of("[B", 7L), Map.of(STACK, 7L)));
        final String html = Html.alloc(a, 10, true, SiteKey.culpritMethod());
        assertTrue(html.contains("By thread"));
        assertTrue(html.contains("<dt>JVM counters</dt><dd>950 B on 1 thread that has one; the estimate for those, over the "
                + "same stretches, is 1.00 KB (+5%), 100.0% of the estimate above</dd>"), html);
        assertTrue(html.contains("<td class=\"n\">950 B</td>"), html);
        assertTrue(html.contains("By class"));
        assertTrue(html.contains("By site"));
        assertTrue(html.contains("byte[]"));
        assertTrue(html.contains("worker"));
        // The hint to use --app, only when it is not in use.
        assertTrue(html.contains("<dt>Packages</dt>") && html.contains("--app PREFIX ranks"), html);
        final String app = Html.alloc(a, 10, true, SiteKey.inPackages(List.of("dev")));
        assertTrue(app.contains("<dt>Packages</dt>"), app);
        assertFalse(app.contains("--app PREFIX ranks"), app);

        final AllocationReport b = new AllocationReport(info(), "jdk.ObjectAllocationSample", 500, 5, 5, Map.of(),
                Map.of("worker", 500L), Map.of("[B", 500L), Map.of(STACK, 500L),
                Map.of(), Map.of(), new AllocationReport.Support(Map.of("worker", 3L), Map.of("[B", 3L),
                Map.of(STACK, 3L)));
        final String diff = Html.allocDiff(new AllocationDiff(a, b), 10, true, SiteKey.culpritMethod());
        assertTrue(diff.contains("<title>jfrq alloc diff"));
        assertTrue(diff.contains("Baseline"));
        assertTrue(diff.contains("-50%"));
        assertTrue(diff.contains("By site"));
        // The samples behind each side, in every table, as in the text report.
        assertTrue(diff.contains("<th>Thread</th><th>Before</th><th>After</th><th>Change</th><th>Samples</th>"), diff);
        assertTrue(diff.contains("<td>worker</td><td class=\"n\">500 B/s</td><td class=\"n\">250 B/s</td>"
                + "<td>-250 B/s (-50%)</td><td>7 -&gt; 3</td>"), diff);
        assertTrue(diff.contains("<td>byte[]</td>"), diff);
        assertTrue(diff.contains("a few hundred percent on a handful of samples is noise"), diff);

        // Sites are what --sites asks for, in the HTML report as in the text one.
        final String noSites = Html.alloc(a, 10, false, SiteKey.culpritMethod());
        assertTrue(noSites.contains("By class"));
        assertFalse(noSites.contains("By site"), noSites);
        assertFalse(noSites.contains("class=\"stack\""), noSites);
        final String noSitesDiff = Html.allocDiff(new AllocationDiff(a, b), 10, false, SiteKey.culpritMethod());
        assertTrue(noSitesDiff.contains("By class"));
        assertFalse(noSitesDiff.contains("By site"), noSitesDiff);
    }

    @Test
    void allocPagesWarnThatVirtualThreadsAreUnderCounted() {
        final AllocationReport vt = new AllocationReport(info(), "jdk.ObjectAllocationSample", 1000, 10, 13, Map.of(),
                Map.of("vt-1", 1000L), Map.of("[B", 1000L), Map.of(STACK, 1000L), Map.of(), Map.of(),
                AllocationReport.Support.NONE, new AllocationReport.Dropped(3, 4_000_000_000L));
        final String html = Html.alloc(vt, 10, false, SiteKey.culpritMethod());
        assertTrue(html.contains("<ul class=\"warn\"><li>allocation on virtual threads is under-counted, and can "
                + "still be over-counted: 3 first samples of virtual threads, 4.00 GB, not counted"), html);
        final String diff = Html.allocDiff(new AllocationDiff(vt, vt), 10, false, SiteKey.culpritMethod());
        assertTrue(diff.contains("<li>allocation on virtual threads is under-counted, and can still be over-counted: "
                + "baseline 3 first samples of virtual threads, 4.00 GB; current 3 first samples"), diff);
        assertEquals(diff.indexOf("allocation on virtual threads"), diff.lastIndexOf("allocation on virtual threads"));
    }

    @Test
    void allocPageWithoutSamplesSaysWhyAndShowsTheCounters() {
        // Every thread sampled once: nothing is left in the estimate, and the counters are the answer.
        final AllocationReport once = new AllocationReport(info(), "jdk.ObjectAllocationSample", 0, 0, 3,
                Map.of("worker", 950L, "main", 20L), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        final String html = Html.alloc(once, 10, true, SiteKey.culpritMethod());
        assertTrue(html.contains("Every thread was sampled once"), html);
        assertTrue(html.contains("<h2>By thread (JVM counters)</h2>"), html);
        assertTrue(html.indexOf("<td>worker</td><td class=\"n\">950 B</td>") < html.indexOf("<td>main</td>"), html);
        assertFalse(html.contains("By site"), html);
        assertFalse(html.contains("<h2>By class</h2>"), html);

        final AllocationReport none = new AllocationReport(info(), "jdk.ObjectAllocationSample", 0, 0, 0, Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE);
        final String empty = Html.alloc(none, 10, true, SiteKey.culpritMethod());
        assertTrue(empty.contains("No allocation events."), empty);
        assertFalse(empty.contains("JVM counters"), empty);
    }

    @Test
    void helpers() {
        assertEquals("new", Html.ratio(Double.POSITIVE_INFINITY));
        assertEquals("+25%", Html.ratio(0.25));
        assertEquals("12.5%", Html.pct(0.125));
        assertEquals("50%", Html.pct(1, 2));
        assertEquals("0%", Html.pct(1, 0));
        assertEquals("a&lt;b&gt;&amp;&quot;c", Html.escape("a<b>&\"c"));
        for (final Stall.Verdict v : Stall.Verdict.values()) {
            assertTrue(Html.colour(v).startsWith("#"));
        }
    }
}
