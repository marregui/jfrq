// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.alloc.AllocationDiff;
import dev.jfrq.core.alloc.AllocationReport;
import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.health.HealthReport;
import dev.jfrq.core.health.ThreadCpu;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.ContentionReport;
import dev.jfrq.core.locks.Wait;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.report.Html;
import dev.jfrq.core.report.Json;
import dev.jfrq.core.report.JsonParser;
import dev.jfrq.core.report.RecordingSummary;
import dev.jfrq.core.report.ThreadCensus;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.Stall;
import dev.jfrq.core.stalls.StallReport;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.util.Glob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The renderers on hand-built reports, where a recording cannot produce the case on demand. */
class TextTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOGBACK = new ThreadRef(1, "logback-4");
    static final ThreadRef HOLDER = new ThreadRef(2, "housekeeper");
    static final Wait.LockKey REGISTRY = new Wait.LockKey("dev.app.Registry", 0xabc, Wait.Kind.MONITOR_ENTER);

    /** A window of one second, as a {@code jfrq-live delta} dump gives it. */
    static RecordingInfo window() {
        return new RecordingInfo(Path.of("delta.jfr"), new Interval(1_000 * MS, 2_000 * MS), 1, Map.of(), Map.of(),
                Set.of(), List.of());
    }

    /**
     * One thread's mailbox over three addresses, two collections moving it between park 32 and
     * 33 and between 65 and 66: 100 parks of 60 ms, 10 ms apart, in a 10 s window.
     */
    static ContentionReport movedMailbox() {
        final ThreadRef loop = new ThreadRef(1, "dispatcher-1");
        final Stack mailbox = new Stack(List.of(new Frame("dev.app.DefaultMailbox", "await", 95, "JIT compiled"),
                new Frame("dev.app.Dispatcher", "run", 80, "JIT compiled")), false);
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final long from = i * 70L;
            waits.add(new Wait(new Interval(from * MS, (from + 60) * MS), loop,
                    new Wait.LockKey("dev.app.DefaultMailbox", i < 33 ? 0x10 : i < 66 ? 0x11 : 0x12, Wait.Kind.PARK),
                    null, mailbox));
        }
        final LongList pauses = new LongList();
        for (final long ms : new long[] {2_305, 2_310, 4_615, 4_620}) {
            pauses.add(ms * MS);
        }
        return new ContentionReport(new RecordingInfo(Path.of("moved.jfr"), new Interval(0, 10_000 * MS), 1, Map.of(),
                Map.of(), Set.of(), List.of()), waits, 0, _ -> true, IdleMatcher.forWorkWaits(), _ -> true, pauses);
    }

    static Wait blocked(final long fromMs, final long toMs) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), LOGBACK, REGISTRY, HOLDER, Stack.EMPTY);
    }

    @Test
    void locksSaysWhenAWaitWasCountedOnlyForThePartInsideTheWindow() {
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(400, 1_900))), 15, false);
        assertTrue(text.contains("Note       1 wait began before the recording or outlived it; "
                + "only the part inside it is counted"), text);
        // The whole wait is 1.5 s; 900 ms of it is inside the window, and that is what is reported.
        assertTrue(text.contains("Blocked    900 ms across 1 wait\n"), text);
        assertTrue(text.contains("90.0%"), text);
        assertFalse(text.contains("1.50 s"), text);
    }

    @Test
    void aConvoyIsDatedByItsOffsetAndItsTimeOfDay() {
        // logback-4 waits on the registry the housekeeper holds, while the housekeeper waits on the
        // store: a convoy whose head began 200 ms into the window, at 00:00:01.200Z.
        final Wait.LockKey store = new Wait.LockKey("dev.app.Store", 0xdef, Wait.Kind.MONITOR_ENTER);
        final Wait head = blocked(1_200, 1_500);
        final Wait link = new Wait(new Interval(1_250 * MS, 1_400 * MS), HOLDER, store,
                new ThreadRef(3, "flusher"), Stack.EMPTY);
        final String convoys = section(Text.locks(new ContentionReport(window(), List.of(head, link)), 15, false),
                "CONVOYS");
        assertTrue(convoys.contains("  +0.200s 00:00:01.200Z  logback-4 waited 300 ms for dev.app.Registry"), convoys);
        assertTrue(convoys.contains("-> housekeeper waited 150 ms for dev.app.Store"), convoys);
    }

    @Test
    void allocSaysHowMuchOfTheEstimateTheJvmCountersCover() {
        // 1 070 of 1 570 estimated bytes are on the one thread with a counter: the +7 % is about 68 % of the report.
        final AllocationReport r = new AllocationReport(new RecordingInfo(Path.of("a.jfr"),
                new Interval(0, 1_000 * MS), 1, Map.of(), Map.of(), Set.of(), List.of()),
                "jdk.ObjectAllocationSample", 1570, 100, 100, Map.of("worker", 1000L),
                Map.of("worker", 1070L, "short-lived", 500L), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        final String text = Text.alloc(r, 15, false, SiteKey.culpritMethod());
        assertTrue(text.contains("on the 1 thread that has one; the estimate for those, over the same stretches, is 1.07 KB (+7%), 68.2% of the estimate above"), text);
    }

    @Test
    void waiterListsAreCappedAndCounted() {
        final List<Wait> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Wait(new Interval((1_000 + i) * MS, (1_010 + i) * MS), new ThreadRef(i, "worker-" + i),
                    REGISTRY, HOLDER, Stack.EMPTY));
        }
        final String text = Text.locks(new ContentionReport(window(), many), 15, false);
        // One pool's workers are one entry, named as info names the family; the threads still
        // have their own rows in THREADS BY TIME BLOCKED, which --top governs.
        assertTrue(text.contains("  worker-N* (20 threads)  "), text);
        // Threads of different families are named, four of them, and the rest counted.
        final List<Wait> mixed = new ArrayList<>();
        final String[] names = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta"};
        for (int i = 0; i < names.length; i++) {
            mixed.add(new Wait(new Interval((1_000 + i) * MS, (1_010 + i) * MS), new ThreadRef(i, names[i]),
                    REGISTRY, HOLDER, Stack.EMPTY));
        }
        final String capped = Text.locks(new ContentionReport(window(), mixed), 15, false);
        assertTrue(capped.contains(" (+2 more)"), capped);
        // The HTML report folds and caps the same way.
        assertTrue(Html.locks(new ContentionReport(window(), many), 15, false).contains("worker-N* (20 threads)"));
        // Names that fit are all printed, pool or not.
        final List<Wait> two = List.of(
                new Wait(new Interval(1_000 * MS, 1_010 * MS), new ThreadRef(1, "event-loop-3-1"), REGISTRY, HOLDER, Stack.EMPTY),
                new Wait(new Interval(1_001 * MS, 1_011 * MS), new ThreadRef(2, "event-loop-3-2"), REGISTRY, HOLDER, Stack.EMPTY));
        final String named = Text.locks(new ContentionReport(window(), two), 15, false);
        assertTrue(named.contains("event-loop-3-1, event-loop-3-2") || named.contains("event-loop-3-2, event-loop-3-1"),
                named);
        assertFalse(named.contains("event-loop-N-N*"), named);
    }

    @Test
    void aDiffOfTwoFilesOfOneNameNamesThemByPath() {
        final AllocationReport r1 = new AllocationReport(new RecordingInfo(Path.of("r1", "exit.jfr"),
                new Interval(0, 1_000 * MS), 1, Map.of(), Map.of(), Set.of(), List.of()),
                "jdk.ObjectAllocationSample", 1000, 1, 1, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        final AllocationReport r2 = new AllocationReport(new RecordingInfo(Path.of("r2", "exit.jfr"),
                new Interval(0, 1_000 * MS), 1, Map.of(), Map.of(), Set.of(), List.of()),
                "jdk.ObjectAllocationSample", 1000, 1, 1, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE);
        final String diff = Text.allocDiff(new AllocationDiff(r1, r2), 15, false, SiteKey.culpritMethod());
        assertTrue(diff.startsWith("Baseline   " + Path.of("r1", "exit.jfr") + "  "), diff);
        assertTrue(diff.contains("\nCurrent    " + Path.of("r2", "exit.jfr") + "  "), diff);
        // Different names need no path.
        final String named = Text.allocDiff(new AllocationDiff(r1, new AllocationReport(new RecordingInfo(
                Path.of("r2", "other.jfr"), new Interval(0, 1_000 * MS), 1, Map.of(), Map.of(), Set.of(), List.of()),
                "jdk.ObjectAllocationSample", 1000, 1, 1, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                AllocationReport.Support.NONE)), 15, false, SiteKey.culpritMethod());
        assertTrue(named.startsWith("Baseline   exit.jfr  "), named);
    }

    @Test
    void infoListsThreadFamiliesAndDerivesTheThresholdLine() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1_000 * MS), 1,
                Map.of("jdk.SocketWrite", 3L),
                Map.of("jdk.SocketWrite", Map.of("enabled", "true", "threshold", "1 ms"),
                        "jdk.ObjectAllocationSample", Map.of("enabled", "true", "throttle", "1000/s")),
                Set.of(new ThreadRef(1, "milo-shared-thread-pool-17"), new ThreadRef(2, "milo-shared-thread-pool-3"),
                        new ThreadRef(3, "main"), new ThreadRef(4, "event-loop-1")),
                List.of());
        final String text = Text.info(info, ThreadCensus.Result.UNKNOWN);

        // SocketWrite is outside the old fixed whitelist, and its 1 ms threshold was in force.
        assertTrue(text.contains("Thresholds SocketWrite 1.00 ms"), text);
        assertTrue(text.contains("Throttled  ObjectAllocationSample 1000/s"), text);
        assertTrue(text.contains("Threads    4 seen in events"), text);
        assertTrue(text.contains("milo-shared-thread-pool-N*"), text);
        assertTrue(text.contains("main"), text);
        // A family of one is named by its thread: "event-loop-N" is not a name --thread can match.
        assertTrue(text.contains("  event-loop-1 "), text);
        assertFalse(text.contains("event-loop-N"), text);
    }

    @Test
    void locksSaysNothingWhenEveryWaitIsInsideTheWindow() {
        final String text = Text.locks(new ContentionReport(window(), List.of(blocked(1_100, 1_400))), 15, false);
        assertFalse(text.contains("Note"), text);
        assertTrue(text.contains("Blocked    300 ms across 1 wait\n"), text);
    }

    /** The part of a report under one heading, up to the blank line that ends it. */
    static String section(final String text, final String heading) {
        final int from = text.indexOf(heading);
        assertTrue(from >= 0, "no section " + heading + " in\n" + text);
        final int to = text.indexOf("\n\n", from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    static int occurrences(final String text, final String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void thresholdLineLeavesOutTheZeroesAndKeepsAStableOrder() {
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1_000 * MS), 1,
                Map.of("jdk.RetransformClasses", 2L),
                Map.of("jdk.SocketWrite", Map.of("enabled", "true", "threshold", "1 ms"),
                        "jdk.JavaMonitorEnter", Map.of("enabled", "true", "threshold", "10 ms"),
                        "jdk.RetransformClasses", Map.of("enabled", "true", "threshold", "0 ns"),
                        "jdk.GCPhasePause", Map.of("enabled", "true", "threshold", "0 ns")),
                Set.of(), List.of());
        final String text = Text.info(info, ThreadCensus.Result.UNKNOWN);
        // A threshold of zero suppresses nothing, so it is not a threshold a reader chose.
        assertTrue(text.contains("Thresholds JavaMonitorEnter 10.0 ms, SocketWrite 1.00 ms\n"), text);
        // It is still a fact about the file, and the per-type table below is where it belongs.
        // The per-type table keeps the "jdk." prefix; the settings lines above strip it.
        final String table = section(text, "Event type");
        assertTrue(table.contains("jdk.RetransformClasses") && table.contains("0 ns"), table);
    }

    @Test
    void locksPrintsOneStackForTheLocksThatShareIt() {
        // One mailbox per worker is one lock per worker and a single stack between them: printed
        // once per lock it was 66 lines of a 177-line report.
        final Stack mailbox = new Stack(List.of(new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
                new Frame("dev.app.Dispatcher", "run", 31, "JIT compiled")), false);
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waits.add(new Wait(new Interval(1_100 * MS, (1_200 + i * 10) * MS), new ThreadRef(i, "dispatcher-" + i),
                    new Wait.LockKey("dev.app.DefaultMailbox", 0x10 + i, Wait.Kind.PARK), HOLDER, mailbox));
        }
        final String where = section(Text.locks(new ContentionReport(window(), waits), 15, false), "WHERE THEY WAITED");
        assertEquals(1, occurrences(where, "DefaultMailbox.awaitNextMessage"), where);
        assertTrue(where.contains("3 locks with this stack, longest 120 ms"), where);
        // The locks it stands for are named, one per line because a name is a class and an
        // address, so a --lock glob can still be aimed at one of them.
        assertTrue(where.contains("\n    dev.app.DefaultMailbox@10\n"), where);
        assertTrue(where.contains("\n    dev.app.DefaultMailbox@12\n"), where);
    }

    @Test
    void anIdleProcessIsToldItIsIdle() {
        // A node doing nothing: every park is a worker on its own queue. The report has no
        // contention in it, and the old answer blamed --thread and --min, which nothing had
        // set, and then dropped the one section that held the whole window's content.
        final Stack noWork = new Stack(List.of(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
                new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1130, "JIT compiled")), false);
        final List<Wait> parks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parks.add(new Wait(new Interval(1_000 * MS, 2_000 * MS), new ThreadRef(i, "worker-" + i),
                    new Wait.LockKey("java.util.concurrent.LinkedBlockingQueue", 0x20 + i, Wait.Kind.PARK),
                    null, noWork));
        }
        final String text = Text.locks(new ContentionReport(window(), parks), 15, false);
        assertTrue(text.contains("No contention: every wait was a worker waiting for work"), text);
        assertFalse(text.contains("--thread"), text);
        assertTrue(text.contains("WAITING FOR WORK (not contention: 3 threads parked on an empty queue"), text);
        assertTrue(text.contains("LinkedBlockingQueue"), text);
    }

    /** Three pool workers idle on their own queues for the whole window. */
    static List<Wait> idleWorkers() {
        final Stack noWork = new Stack(List.of(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
                new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
                new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled")), false);
        final List<Wait> parks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parks.add(new Wait(new Interval(1_000 * MS, 2_000 * MS), new ThreadRef(i, "worker-" + i),
                    new Wait.LockKey("java.util.concurrent.LinkedBlockingQueue", 0x20 + i, Wait.Kind.PARK),
                    null, noWork));
        }
        return parks;
    }

    @Test
    void anIdleAnswerUnderFiltersSaysItIsAboutWhatMatched() {
        // logback-4 waited 300 ms for the registry; --thread 'worker-*' left it out. What is
        // left is only idle workers, and "every wait was a worker waiting for work" would be
        // a claim about the recording that the recording contradicts.
        final List<Wait> waits = new ArrayList<>(idleWorkers());
        waits.add(blocked(1_100, 1_400));
        final String text = Text.locks(new ContentionReport(window(), waits, 0, name -> name.startsWith("worker-")),
                15, false);
        assertTrue(text.contains("No contention: every wait that matches the filters (--thread, --min, --lock) was "
                + "a worker waiting for work, not a thread held up by another; 4 in the recording.\n"), text);
        assertFalse(text.contains("every wait was a worker"), text);
        assertTrue(text.contains("WAITING FOR WORK"), text);
    }

    @Test
    void textAndHtmlGiveTheSameAnswerWhenThereIsNoContention() {
        final List<ContentionReport> reports = List.of(
                new ContentionReport(window(), List.of()),
                new ContentionReport(window(), idleWorkers()),
                new ContentionReport(window(), List.of(blocked(1_100, 1_400)), 0, _ -> false),
                new ContentionReport(window(), List.of(blocked(1_100, 1_400)), 500 * MS, _ -> true));
        for (final ContentionReport r : reports) {
            final String answer = r.noContention();
            final String text = Text.locks(r, 15, false);
            final String html = Html.locks(r, 15, false);
            assertTrue(text.contains("\n" + answer + "\n"), text);
            assertTrue(html.contains("<p>" + answer + "</p>"), html);
            // An empty page is not a report of nothing: no zero totals, no empty tables.
            assertFalse(html.contains("Total blocked time"), html);
            assertFalse(html.contains("Locks by total wait"), html);
            assertEquals(r.workWaits().isEmpty(), !html.contains("Waiting for work"), html);
            assertEquals(r.workWaits().isEmpty(), !text.contains("WAITING FOR WORK"), text);
        }
        assertTrue(reports.get(0).noContention().startsWith("No contended monitor enters or parks in the recording"));
        assertTrue(reports.get(1).noContention().startsWith("No contention: every wait was a worker"));
        assertTrue(reports.get(2).noContention().contains("match the filters"));
        assertTrue(reports.get(3).noContention().contains("match the filters"));
    }

    @Test
    void textAndHtmlAddUpTheSameLockSites() {
        // Two queues whose stacks differ only below the frames a site is compared on: one site
        // with two instances in both reports, whatever depth each prints its other stacks at.
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final List<Frame> frames = new ArrayList<>();
            for (int f = 0; f < 10; f++) {
                frames.add(new Frame("dev.app.Layer" + f, "call", f == 8 ? 100 + i : 1, "JIT compiled"));
            }
            waits.add(new Wait(new Interval(1_100 * MS, 1_200 * MS), new ThreadRef(10 + i, "browse-" + i),
                    new Wait.LockKey("dev.app.Queue", 0x100 + i, Wait.Kind.PARK), null, new Stack(frames, false)));
        }
        final ContentionReport r = new ContentionReport(window(), waits);
        final String text = section(Text.locks(r, 15, true), "LOCK SITES BY TOTAL WAIT");
        final String page = Html.locks(r, 15, true);
        final int from = page.indexOf("<h2>Lock sites by total wait");
        final String html = page.substring(from, page.indexOf("<h2>", from + 1));
        assertTrue(text.contains("2 lock instances"), text);
        assertEquals(1, occurrences(text, "Layer0.call"), text);
        assertTrue(html.contains("2 lock instances"), html);
        assertEquals(1, occurrences(html, "Layer0.call"), html);
        // Neither prints the frame the grouping did not look at.
        assertFalse(text.contains("Layer8"), text);
        assertFalse(html.contains("Layer8"), html);
    }

    @Test
    void locksBySiteRankOneRowPerStackWithItsInstanceCount() {
        final Stack mailbox = new Stack(List.of(new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
                new Frame("dev.app.Dispatcher", "run", 31, "JIT compiled")), false);
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waits.add(new Wait(new Interval(1_100 * MS, (1_200 + i * 10) * MS), new ThreadRef(i, "dispatcher-" + i),
                    new Wait.LockKey("dev.app.DefaultMailbox", 0x10 + i, Wait.Kind.PARK), HOLDER, mailbox));
        }
        final String text = Text.locks(new ContentionReport(window(), waits), 15, true);
        final String sites = section(text, "LOCK SITES BY TOTAL WAIT");
        assertTrue(sites.contains("330 ms across 3 waits, 3 lock instances, longest 120 ms"), sites);
        assertEquals(1, occurrences(sites, "DefaultMailbox.awaitNextMessage"), sites);
        // The per-instance table is what --by-site replaces, so it is not printed as well.
        assertFalse(text.contains("LOCKS BY TOTAL WAIT\n"), text);
        assertFalse(text.contains("WHERE THEY WAITED"), text);
        // Everything that does not group by stack stays where it was.
        assertTrue(text.contains("THREADS BY TIME BLOCKED"), text);
        assertTrue(text.contains("LONGEST WAITS"), text);
    }

    @Test
    void allocWarnsThatVirtualThreadsAreUnderCounted() {
        final AllocationReport r = new AllocationReport(window(), "jdk.ObjectAllocationSample", 1000, 1, 2,
                Map.of(), Map.of("vt-1", 1000L), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE,
                new AllocationReport.Dropped(1, 2048));
        final String text = Text.alloc(r, 15, false, SiteKey.culpritMethod());
        assertTrue(text.contains("\nWARNING    allocation on virtual threads is under-counted, and can still be "
                + "over-counted: 1 first sample of virtual threads, 2.05 KB, not counted"), text);
        // A diff says it once, with both recordings' counts, and names a side that had none.
        final AllocationReport none = new AllocationReport(window(), "jdk.ObjectAllocationSample", 1000, 1, 2,
                Map.of(), Map.of("vt-1", 1000L), Map.of(), Map.of(), Map.of(), Map.of(), AllocationReport.Support.NONE,
                AllocationReport.Dropped.NONE);
        final String diff = Text.allocDiff(new AllocationDiff(none, r), 15, false, SiteKey.culpritMethod());
        assertEquals(1, occurrences(diff, "allocation on virtual threads"), diff);
        assertTrue(diff.contains("\nWARNING    allocation on virtual threads is under-counted, and can still be "
                + "over-counted: baseline none; current 1 first sample of virtual threads, 2.05 KB, not counted"), diff);
        assertTrue(Text.allocDiff(new AllocationDiff(none, none), 15, false, SiteKey.culpritMethod())
                .lines().noneMatch(l -> l.startsWith("WARNING")));
    }

    @Test
    void allocSitesNameTheMethodAndCountEverySampleBehindTheRow() {
        // The same method reached two ways: one row, and its support is both stacks' samples.
        final Frame culprit = new Frame("dev.app.NodeId", "parse", 453, "JIT compiled");
        final Stack viaSubstring = new Stack(List.of(new Frame("java.lang.String", "substring", 2904, "JIT compiled"),
                culprit), false);
        final Stack viaCopy = new Stack(List.of(new Frame("java.util.Arrays", "copyOfRange", 3849, "JIT compiled"),
                new Frame("dev.app.NodeId", "parse", 454, "JIT compiled")), false);
        final AllocationReport r = new AllocationReport(window(), "jdk.ObjectAllocationSample", 1000, 1200, 1200,
                Map.of(), Map.of("worker", 1000L), Map.of(), Map.of(viaSubstring, 700L, viaCopy, 300L),
                Map.of(), Map.of(), new AllocationReport.Support(Map.of(), Map.of(),
                Map.of(viaSubstring, 900L, viaCopy, 300L)));

        final String sites = section(Text.alloc(r, 15, true, SiteKey.culpritMethod()), "BY SITE");
        assertTrue(sites.contains("1200 samples  dev.app.NodeId.parse  (2 stacks, the biggest below)"), sites);
        assertEquals(1, occurrences(sites, "dev.app.NodeId.parse "), sites);
        // The line that tells a reader what --app could be pointed at.
        assertTrue(sites.contains("Packages dev.app 100.0%  (--app PREFIX"), sites);
        // Under --app the packages are still listed, the hint to use --app is not.
        final String app = section(Text.alloc(r, 15, true, SiteKey.inPackages(List.of("dev.app"))), "BY SITE");
        assertTrue(app.contains("Packages dev.app 100.0%\n"), app);
        assertFalse(app.contains("--app PREFIX"), app);
    }

    @Test
    void stallsPrintARepeatedStackOnceAndPointAtIt() {
        // One lock convoying two event loops is several stalls and one stack: the demo printed
        // the same six frames five times down the list.
        final ThreadRef loop1 = new ThreadRef(1, "event-loop-1");
        final ThreadRef loop2 = new ThreadRef(2, "event-loop-2");
        final Stack touch = new Stack(List.of(new Frame("dev.app.Registry", "touch", 29, "JIT compiled")), false);
        final Stack flush = new Stack(List.of(new Frame("dev.app.Persistence", "flush", 15, "JIT compiled")), false);
        final Stall a = new Stall(loop1, new Interval(1_000 * MS, 1_176 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on dev.app.Registry", touch, Stall.Evidence.EVENT, 2);
        final Stall b = new Stall(loop2, new Interval(1_000 * MS, 1_175 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on dev.app.Registry", touch, Stall.Evidence.EVENT, 2);
        final Stall c = new Stall(loop1, new Interval(1_300 * MS, 1_400 * MS), Stall.Verdict.BLOCKED_MONITOR,
                "blocked on dev.app.Store", flush, Stall.Evidence.EVENT, 1);
        final StallReport r = new StallReport(window(), 50 * MS,
                List.of(new StallReport.ThreadSummary(loop1, 10, MS, MS, 2, 276 * MS, 176 * MS)),
                List.of(a, b, c), List.of(), List.of());
        final String listed = section(Text.stalls(r, 15), "STALLS >=");

        // Every stall keeps its own row: they are separate occurrences, not one aggregate.
        assertEquals(3, occurrences(listed, "BLOCKED_MONITOR"), listed);
        assertEquals(1, occurrences(listed, "Registry.touch"), listed);
        assertTrue(listed.contains("same stack as #1"), listed);
        assertEquals(1, occurrences(listed, "Persistence.flush"), listed);
    }

    @Test
    void stallsLeadsWithWhatTheRecordingCannotShowAndSaysSoWhenItFoundNothing() {
        // The field case: event loops, 0 stalls, and a recording blind to anything under 750 ms.
        final ThreadRef loop = new ThreadRef(1, "event-loop-1");
        final StallReport r = new StallReport(window(), 50 * MS,
                List.of(new StallReport.ThreadSummary(loop, 460, 0, 250 * MS, 0, 0, 0, 750 * MS,
                        StallReport.Sight.NATIVE_SAMPLER, 250 * MS)),
                List.of(), List.of(), List.of("a warning"));
        final String text = Text.stalls(r, 15);
        // Before the warnings, and before the empty list it qualifies.
        // The bound in one short line first; the reasons close the report.
        assertTrue(text.contains("Unseen     unexplained stalls under 750 ms cannot be seen on 1 of 1 threads "
                + "(UNSEEN below)\nWARNING    a warning\n"), text);
        assertTrue(text.contains("  none this recording can show: see Unseen above"), text);
        final String why = section(text, "UNSEEN (");
        assertTrue(text.indexOf("UNSEEN (") > text.indexOf("STALLS >="), text);
        assertTrue(why.contains("  " + StallReport.UNSEEN_SCOPE + "\n"), why);
        assertTrue(why.contains("  - on 1 of 1 threads, a stall no event explains is seen only from 750 ms"), why);
        why.lines().forEach(line -> assertTrue(line.length() <= Text.PROSE_WIDTH, line));
        // A thread with no stall is counted, not listed: the Unseen line speaks for it.
        assertTrue(section(text, "PER THREAD").contains("1 thread with no stall"), text);
    }

    @Test
    void wrapBreaksProseAtSpacesWithinTheWidth() {
        final String words = "word ".repeat(40).strip();
        final String wrapped = Text.wrap(words, "  - ", "    ");
        assertTrue(wrapped.startsWith("  - word word"), wrapped);
        wrapped.lines().forEach(line -> {
            assertTrue(line.length() <= Text.PROSE_WIDTH, line);
            assertFalse(line.endsWith(" "), line);
        });
        assertEquals(words, String.join(" ", wrapped.lines().map(String::strip).map(l -> l.replaceFirst("^- ", "")).toList()));
        // A word longer than a line stands on its own, unbroken.
        final String longWord = "x".repeat(Text.PROSE_WIDTH + 5);
        assertEquals("  a\n  " + longWord + "\n  b\n", Text.wrap("a " + longWord + " b", "  ", "  "));
        assertEquals("  short\n", Text.wrap("short", "  ", "  "));
    }

    @Test
    void stallsRanksUnexplainedGapsApartFromTheStallsItCanExplain() {
        final ThreadRef worker = new ThreadRef(1, "browse-1");
        final Stall gap = new Stall(worker, new Interval(1_000 * MS, 1_474 * MS), Stall.Verdict.UNEXPLAINED,
                "no samples and no blocking event", Stack.EMPTY, Stall.Evidence.SILENCE, 0);
        final Stall parked = new Stall(worker, new Interval(1_500 * MS, 1_671 * MS), Stall.Verdict.PARKED,
                "parked at ChannelBrowseSink.take", Stack.EMPTY, Stall.Evidence.EVENT, 3);
        final StallReport r = new StallReport(window(), 100 * MS,
                List.of(new StallReport.ThreadSummary(worker, 10, MS, MS, 2, 645 * MS, 474 * MS)),
                List.of(gap, parked), List.of(), List.of());
        final String text = Text.stalls(r, 15);

        // The 474 ms nobody can act on no longer outranks the 171 ms with an explanation:
        // they are different kinds of claim, so they are different lists.
        assertTrue(text.contains("STALLS >= 100 ms: 1 found"), text);
        assertTrue(text.contains("UNEXPLAINED GAPS >= 100 ms: 1 found"), text);
        assertTrue(text.indexOf("ChannelBrowseSink") < text.indexOf("UNEXPLAINED GAPS"), text);
        // The lead that turns a gap into a next step.
        assertTrue(text.contains("jdk.SocketWrite"), text);
        // Both kinds still count in the totals.
        assertTrue(section(text, "BY VERDICT").contains("UNEXPLAINED"), text);
    }

    @Test
    void stallsWithNoThreadNameWhatMatchedAndStillListThePauses() {
        // `jfrq info` lists the VM Thread; stalls has nothing to judge it by. Saying "use jfrq
        // info" back sent the reader round in a circle, and the pauses that stopped every
        // thread were dropped with the rest.
        final Pause gc = new Pause(new Interval(1_100 * MS, 1_400 * MS), PauseKind.GC, "Full (gcId 9)");
        final StallReport r = new StallReport(window(), 50 * MS, List.of(), List.of(), List.of(gc),
                List.of("1 matching thread has no samples and no blocking events, so nothing to judge by (VM Thread)"));
        final String text = Text.stalls(r, 15);
        assertTrue(text.contains("WARNING    1 matching thread has no samples"), text);
        assertTrue(text.contains(StallReport.NO_THREAD + "\n"), text);
        assertTrue(text.contains("JVM-WIDE PAUSES >= gap"), text);
        assertTrue(text.contains("Full (gcId 9)"), text);
        assertFalse(text.contains("PER THREAD"), text);
        assertTrue(text.endsWith("\n"), text);
    }

    @Test
    void locksLabelALockTheCollectorMovedAndKeepItsWaits() {
        final ContentionReport r = movedMailbox();
        final String text = Text.locks(r, 15, false);
        // Counted in the total, and the total says how much of it carries the label.
        assertTrue(text.contains("Blocked    6.00 s across 100 waits\n"), text);
        assertTrue(text.contains("Moved      6.00 s of it on locks the collector probably moved from under 1 thread: "
                + "see MOVED BY THE COLLECTOR"), text);
        final String section = section(text, "MOVED BY THE COLLECTOR (");
        assertTrue(section.contains("dispatcher-1  dev.app.DefaultMailbox"), section);
        assertTrue(section.contains("1 in 10^3"), section);
        assertTrue(section.contains("dev.app.DefaultMailbox.await"), section);
        final String html = Html.locks(r, 15, false);
        assertTrue(html.contains("Moved by the collector"), html);
        assertTrue(html.contains("1 in 10^3"), html);
        final Map<String, Object> doc = JsonParser.object(Json.locks(r, 15, false, "9.9.9"));
        assertEquals(6_000 * MS, doc.get("movedNanos"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> moved = ((List<Map<String, Object>>) doc.get("movedByCollector")).getFirst();
        assertEquals("dispatcher-1", moved.get("thread"));
        assertEquals(3, ((List<?>) moved.get("locks")).size());
        assertEquals(100L, moved.get("waits"));
        assertTrue((Double) moved.get("chanceLog10") < -3.0, moved.toString());
        // Nothing to label, nothing printed.
        assertFalse(Text.locks(new ContentionReport(window(), List.of(blocked(400, 1_900))), 15, false)
                .contains("MOVED BY THE COLLECTOR"));
    }

    static final long S = 1_000_000_000L;

    static HealthReport.Series bytes(final String name, final double start, final double end) {
        return new HealthReport.Series(name, HealthReport.Series.Unit.BYTES, 9, start, end, start, end, end, start, end);
    }

    /** A report with every section {@code health} gained, and {@code categories} NMT categories. */
    static HealthReport health(final String file, final int categories, final boolean throwsOff) {
        final List<HealthReport.Series> nmt = new ArrayList<>();
        nmt.add(bytes("Total", 1_200e6, 1_800e6));
        for (int i = 0; i < categories; i++) {
            nmt.add(bytes("Category " + i, 100e6 - i, 200e6 - i));
        }
        final RecordingInfo info = new RecordingInfo(Path.of("runs", file), new Interval(10 * S, 110 * S), 1, Map.of(),
                throwsOff ? Map.of("jdk.JavaExceptionThrow", Map.of("enabled", "false")) : Map.of(), Set.of(),
                List.of("a file warning"));
        return new HealthReport(info, List.of(), new HealthReport.Gc(Map.of("G1New", 4L),
                Map.of("G1 Evacuation Pause", 4L), 0, S / 50, 30 * MS, 12, Nulls.LONG_NULL, Nulls.LONG_NULL),
                List.of(bytes(HealthReport.HEAP_AFTER_GC, 20e6, 400e6)), new HealthReport.Threads(7, 30),
                new HealthReport.Throwables(500, 50 * S, 0, null, List.of(), List.of(), Map.of()),
                new ThreadCpu.Result(Map.of(), 0.086, 100, 10), nmt, List.of("the JVM's figure is wrong"));
    }

    @Test
    void healthSaysItsWarningsFirstAndItsNativeMemoryAndThreadCpu() {
        final String text = Text.health(health("n1.jfr", 3, true), 2);
        final int warning = text.indexOf("WARNING    the JVM's figure is wrong\n");
        assertTrue(warning > 0 && warning < text.indexOf("\nFINDINGS"), text);
        assertTrue(text.contains("  Java threads used 8.6% of the JVM's CPUs"), text);
        assertTrue(text.contains("  The floor of heap after GC rose by 380 MB."), text);
        assertTrue(text.contains("\nNATIVE MEMORY (committed, by NMT category"), text);
        assertTrue(text.contains("  Category 1 ") && !text.contains("  Category 2 "), text);
        assertTrue(text.contains("  ... 1 more categories\n"), text);
        assertTrue(text.contains("the event was off in this recording. To record the throwables created, add "
                + HealthReport.ENABLE_THROWS), text);
        // Without NMT there is no section at all.
        assertFalse(Text.health(health("n1.jfr", 0, false), 2).contains("... 0 more"));
        assertFalse(Text.health(health("n1.jfr", 0, false), 2).contains(HealthReport.ENABLE_THROWS));
    }

    @Test
    void healthOfSeveralRecordingsNamesEachOneAndItsWarnings() {
        final String text = Text.healthCompared(List.of(health("n1.jfr", 0, false), health("n2.jfr", 0, false)));
        assertTrue(text.startsWith("HEALTH OF 2 RECORDINGS (one row each"), text);
        assertTrue(text.contains("\n  n1.jfr  "), text);
        assertTrue(text.contains("\nn2.jfr\n  WARNING  a file warning\n  WARNING  the JVM's figure is wrong\n"
                + "  no findings\n"), text);
        assertTrue(text.contains("18.0 MB → 400 MB") || text.contains("20.0 MB → 400 MB"), text);
    }

    @Test
    void infoListsTheCreatorsItShowsAndCountsTheRest() {
        final ThreadRef parent = new ThreadRef(9, "submitter");
        final List<ThreadCensus.Start> starts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            starts.add(new ThreadCensus.Start((20 + i) * S, new ThreadRef(i, "w-" + i), parent, new Stack(List.of(
                    new Frame("com.example.Site" + i, "start", 1, false)), false), false));
        }
        final ThreadRef w0 = starts.getFirst().thread();
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(), Map.of(w0, 1L), Map.of(), Set.of(w0),
                Set.of(w0), Set.of(w0), starts, new ThreadCpu.Result(Map.of(w0, 0.5), 0.5, 4, 1));
        final RecordingInfo info = new RecordingInfo(Path.of("rec.jfr"), new Interval(10 * S, 110 * S), 1, Map.of(),
                Map.of(), Set.of(w0), List.of());
        final String text = Text.info(info, census, RecordingSummary.starts(census, Glob.of("w-*"), 2));
        assertTrue(text.contains("  CREATED BY (" + RecordingSummary.CREATOR_RULE + "), showing 2 of 3\n"), text);
        assertTrue(text.contains("        started from submitter\n"), text);
        assertTrue(text.contains("  Attached: threads native code attached"), text);
        assertTrue(text.contains("  CPU: the share of the JVM's CPUs (the machine's, unless -XX:ActiveProcessorCount"), text);
        assertTrue(text.contains("50.0%"), text);
    }

    @Test
    void anAttachedCreatorHasNoStartingThreadAndNoStack() {
        // Native code attaching a thread names no parent and no stack: the row stands alone.
        final ThreadRef t = new ThreadRef(1, "Thread-1");
        final List<ThreadCensus.Start> starts = List.of(new ThreadCensus.Start(20 * S, t, null, Stack.EMPTY, true));
        final ThreadCensus.Result census = new ThreadCensus.Result(Set.of(), Map.of(t, 1L), Map.of(), Set.of(t),
                Set.of(t), Set.of(t), starts, ThreadCpu.Result.UNKNOWN);
        final RecordingInfo info = new RecordingInfo(Path.of("rec.jfr"), new Interval(10 * S, 110 * S), 1, Map.of(),
                Map.of(), Set.of(t), List.of());
        final String created = section(Text.info(info, census, RecordingSummary.starts(census, Glob.of("Thread-*"), 5)),
                "  CREATED BY");
        assertTrue(created.endsWith("   1       1  100.0%  " + RecordingSummary.ATTACHED + "\n"), created);
        assertFalse(created.contains("started from"), created);
    }

    @Test
    void theStartingThreadsPrintInNameOrderWhicheverOrderTheyStartedIn() {
        // The parents were a set salted per JVM: the same file listed them, and folded them, differently per run.
        final List<ThreadRef> parents = List.of(new ThreadRef(7, "Signal Dispatcher"), new ThreadRef(6, "alpha"),
                new ThreadRef(5, "main"), new ThreadRef(4, "pool-10-thread-1"), new ThreadRef(3, "pool-2-thread-1"),
                new ThreadRef(2, "pool-2-thread-2"), new ThreadRef(1, "reaper"));
        final RecordingInfo info = new RecordingInfo(Path.of("rec.jfr"), new Interval(10 * S, 110 * S), 1, Map.of(),
                Map.of(), Set.of(), List.of());
        final String forward = startedFrom(info, parents);
        assertEquals(forward, startedFrom(info, parents.reversed()));
        assertTrue(forward.contains("        started from Signal Dispatcher, alpha, main, pool-N-thread-N* (3 threads)"
                + " (+1 more)\n"), forward);
        assertTrue(startedFrom(info, List.of(parents.get(4), parents.get(1), parents.get(2)))
                .contains("        started from alpha, main, pool-2-thread-1\n"));
    }

    /** {@code info --thread 'w-*'} on one site that each of {@code parents} ran once, in the order given. */
    private static String startedFrom(final RecordingInfo info, final List<ThreadRef> parents) {
        final List<ThreadCensus.Start> starts = new ArrayList<>();
        for (int i = 0; i < parents.size(); i++) {
            starts.add(new ThreadCensus.Start((20 + i) * S, new ThreadRef(100 + i, "w-" + i), parents.get(i),
                    new Stack(List.of(new Frame("com.example.Pool", "grow", 1, false)), false), false));
        }
        final ThreadCensus.Result census = new ThreadCensus.Result(null, null, null, null, null, Set.of(), starts,
                ThreadCpu.Result.UNKNOWN);
        return Text.info(info, census, RecordingSummary.starts(census, Glob.of("w-*"), 5));
    }
}
