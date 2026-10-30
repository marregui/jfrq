// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.locks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.locks.Wait.Kind;
import dev.jfrq.core.locks.Wait.LockKey;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.IdleMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentionReportTest {

    static final long MS = 1_000_000L;
    static final ThreadRef LOOP1 = new ThreadRef(1, "event-loop-1");
    static final ThreadRef LOOP2 = new ThreadRef(2, "event-loop-2");
    static final ThreadRef HOUSEKEEPER = new ThreadRef(3, "housekeeper");
    static final ThreadRef FLUSHER = new ThreadRef(4, "flusher");
    static final LockKey REGISTRY = new LockKey("dev.app.Registry", 0xabc, Kind.MONITOR_ENTER);
    static final LockKey STORE = new LockKey("dev.app.Store", 0xdef, Kind.MONITOR_ENTER);
    static final LockKey QUEUE = new LockKey("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject",
            0x111, Kind.PARK);

    static Wait wait(final long fromMs, final long toMs, final ThreadRef waiter, final LockKey lock, final ThreadRef owner) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), waiter, lock, owner, Stack.EMPTY);
    }

    /** A park on the queue lock, with the stack that decides whether it is contention. */
    static Wait park(final long fromMs, final long toMs, final ThreadRef waiter, final Stack stack) {
        return new Wait(new Interval(fromMs * MS, toMs * MS), waiter, QUEUE, null, stack);
    }

    static Stack stack(final Frame... frames) {
        return new Stack(List.of(frames), false);
    }

    /** A pool worker with nothing to do: the frame that says so is below the park and the queue. */
    static final Stack NO_WORK = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
            new Frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "await", 1761, "JIT compiled"),
            new Frame("java.util.concurrent.LinkedBlockingQueue", "take", 435, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "getTask", 1070, "JIT compiled"),
            new Frame("java.util.concurrent.ThreadPoolExecutor", "runWorker", 1130, "JIT compiled"));

    /** The same park, under an application call that is waiting for an answer. */
    static final Stack AWAITING_RESULT = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 341, "JIT compiled"),
            new Frame("java.util.concurrent.SynchronousQueue", "poll", 800, "JIT compiled"),
            new Frame("dev.app.Rpc", "call", 44, "JIT compiled"),
            new Frame("dev.app.Handler", "channelRead0", 59, "JIT compiled"));

    static RecordingInfo info() {
        return window(0, 10_000);
    }

    static RecordingInfo window(final long fromMs, final long toMs) {
        return new RecordingInfo(Path.of("t.jfr"), new Interval(fromMs * MS, toMs * MS), 1, Map.of(), Map.of(),
                Set.of(), List.of());
    }

    @Test
    void emptyReport() {
        final ContentionReport r = new ContentionReport(info(), List.of());
        assertTrue(r.isEmpty());
        assertEquals(0, r.totalNanos());
        assertTrue(r.locks(5).isEmpty());
        assertTrue(r.waiters(5).isEmpty());
        assertTrue(r.convoys(5, 5).isEmpty());
        assertTrue(r.longest(5).isEmpty());
        assertEquals(info(), r.info());
    }

    @Test
    void locksAndWaitersAreRankedByTotalTime() {
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(600, 650, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(700, 720, HOUSEKEEPER, STORE, FLUSHER),
                wait(800, 805, LOOP1, QUEUE, null)));

        assertEquals(275 * MS, r.totalNanos());
        final List<ContentionReport.LockStats> locks = r.locks(10);
        assertEquals(3, locks.size());
        assertEquals(REGISTRY, locks.getFirst().lock());
        assertEquals(250 * MS, locks.getFirst().totalNanos());
        assertEquals(2, locks.getFirst().count());
        assertEquals(200 * MS, locks.getFirst().maxNanos());
        assertEquals(Set.of(LOOP1, LOOP2), locks.getFirst().waiters());
        assertEquals(Set.of(HOUSEKEEPER), locks.getFirst().owners());
        assertEquals(QUEUE, locks.get(2).lock());
        assertTrue(locks.get(2).owners().isEmpty());
        assertEquals(1, r.locks(1).size());

        final List<ContentionReport.ThreadStats> waiters = r.waiters(10);
        assertEquals(LOOP1, waiters.getFirst().thread());
        assertEquals(205 * MS, waiters.getFirst().totalNanos());
        assertEquals(2, waiters.getFirst().count());
        assertEquals(200 * MS, waiters.getFirst().maxNanos());

        assertEquals(200 * MS, r.longest(1).getFirst().duration());
        assertEquals(List.of(100 * MS, 600 * MS, 700 * MS, 800 * MS),
                r.waits().stream().map(Wait::start).toList());
    }

    @Test
    void theRealHolderIsResolvedThroughCoWaiters() {
        // JFR says loop-1 got the lock from loop-2, but loop-2 was itself waiting for the
        // housekeeper the whole time: the housekeeper is who held it.
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER)));
        final Wait resolved = r.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, resolved.owner());
        assertEquals(List.of(LOOP2), resolved.via());
        assertEquals("held by housekeeper (handed on through event-loop-2)", resolved.heldBy());
        final Wait direct = r.waits().stream().filter(w -> w.waiter().equals(LOOP2)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, direct.owner());
        assertTrue(direct.via().isEmpty());
        assertEquals("held by housekeeper", direct.heldBy());
        assertEquals(Set.of(HOUSEKEEPER), r.locks(1).getFirst().owners());
    }

    @Test
    void anIntermediaryThatHeldTheLockForMostOfTheWaitIsTheHolder() {
        // The housekeeper held the registry for the first half millisecond of the wait and
        // handed it to event-loop-2, which then held it for 299.5 ms while event-loop-1 waited.
        // JFR names event-loop-2 as the one that released it to event-loop-1, and it was:
        // walking back through its one-millisecond wait would blame the housekeeper for 299 ms
        // it did not hold.
        final Wait loop1 = new Wait(new Interval(MS / 2, 300 * MS), LOOP1, REGISTRY, LOOP2, Stack.EMPTY);
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(0, 1, LOOP2, REGISTRY, HOUSEKEEPER), loop1));
        final Wait resolved = r.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals(LOOP2, resolved.owner());
        assertEquals(List.of(HOUSEKEEPER), resolved.via());
        assertEquals("held by event-loop-2 (handed on through housekeeper)", resolved.heldBy());

        // The longer hold wins: event-loop-2 got the lock at 201 ms and held it 99 ms, the
        // housekeeper held it the 101 ms before.
        final ContentionReport late = new ContentionReport(info(), List.of(
                wait(100, 201, LOOP2, REGISTRY, HOUSEKEEPER), wait(100, 300, LOOP1, REGISTRY, LOOP2)));
        final Wait walked = late.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, walked.owner());
        assertEquals(List.of(LOOP2), walked.via());
        // A tie names the thread nearer the waiter.
        final ContentionReport half = new ContentionReport(info(), List.of(
                wait(100, 200, LOOP2, REGISTRY, HOUSEKEEPER), wait(100, 300, LOOP1, REGISTRY, LOOP2)));
        assertEquals(LOOP2, half.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow()
                .owner());
    }

    @Test
    void theHolderIsTheThreadThatHeldTheLockLongestInsideTheWait() {
        // Ping-pong between event-loop-2 and the housekeeper while event-loop-1 waited 0-100 ms:
        // event-loop-2's longest wait ended at 5 ms, but the one it got the lock from before
        // handing it over ended at 99. The housekeeper held it from 6 to 99 and from 0 to 5.
        final ContentionReport pingPong = new ContentionReport(info(), List.of(
                wait(0, 100, LOOP1, REGISTRY, LOOP2),
                wait(-200, 5, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(5, 6, HOUSEKEEPER, REGISTRY, LOOP2),
                wait(7, 99, LOOP2, REGISTRY, HOUSEKEEPER)));
        final Wait c = pingPong.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals(HOUSEKEEPER, c.owner());
        assertEquals(List.of(LOOP2), c.via());

        // Three holds inside a 97 ms wait: the flusher 36 ms, event-loop-2 21, the housekeeper
        // the last 40. The one to name is the housekeeper, not the first thread to get the lock
        // before the middle of the wait.
        final ContentionReport hop = new ContentionReport(info(), List.of(
                wait(0, 97, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(2, 36, LOOP2, REGISTRY, FLUSHER),
                wait(4, 57, HOUSEKEEPER, REGISTRY, LOOP2)));
        final Wait a = hop.waits().stream().filter(w -> w.waiter().equals(LOOP1)).findFirst().orElseThrow();
        assertEquals("held by housekeeper (handed on through flusher, event-loop-2)", a.heldBy());
    }

    @Test
    void resolutionStopsAtCyclesAndAtUnknownOwners() {
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 300, LOOP2, REGISTRY, LOOP1)));
        for (final Wait w : r.waits()) {
            assertTrue(w.via().isEmpty(), w.toString());
        }
        final ContentionReport parks = new ContentionReport(info(), List.of(wait(0, 10, LOOP1, QUEUE, null)));
        assertNull(parks.waits().getFirst().owner());
        assertEquals("", parks.waits().getFirst().heldBy());
    }

    @Test
    void convoysFollowTheHolderIntoADifferentLock() {
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(150, 200, HOUSEKEEPER, STORE, FLUSHER),
                wait(900, 950, LOOP1, REGISTRY, HOUSEKEEPER)));
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(2, convoys.size());
        final ContentionReport.Convoy first = convoys.getFirst();
        assertEquals(2, first.depth());
        assertEquals(LOOP1, first.head().waiter());
        assertEquals(HOUSEKEEPER, first.head().owner());
        assertEquals(STORE, first.links().get(1).lock());
        assertEquals(FLUSHER, first.links().get(1).owner());
        assertEquals(LOOP2, convoys.get(1).head().waiter());
        assertEquals(1, r.convoys(5, 1).size());
        // A depth limit of one link means no convoy can form.
        assertTrue(r.convoys(1, 10).isEmpty());
    }

    @Test
    void waitsAreCountedOnlyForThePartInsideTheWindow() {
        // A one-second window: event-loop-1's wait began before it, event-loop-2's runs past
        // its end. Counted whole they put 2.3 s of blocking inside a 1 s window.
        final RecordingInfo info = window(1_000, 2_000);
        final ContentionReport r = new ContentionReport(info, List.of(
                wait(400, 1_900, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(1_800, 2_600, LOOP2, STORE, HOUSEKEEPER)));

        assertEquals(1_100 * MS, r.totalNanos());
        assertEquals(2, r.clippedCount());
        assertEquals(900 * MS, r.locks(10).getFirst().totalNanos());
        assertEquals(900 * MS, r.locks(10).getFirst().maxNanos());
        assertEquals(900 * MS, r.waiters(10).getFirst().totalNanos());
        assertEquals(900 * MS, r.longest(1).getFirst().duration());
        // The clipped wait keeps everything but its interval.
        assertEquals(HOUSEKEEPER, r.longest(1).getFirst().owner());
        assertEquals(REGISTRY, r.longest(1).getFirst().lock());
        // The invariant the 112.5 % in the report broke: no thread is blocked for longer than the window.
        for (final ContentionReport.ThreadStats t : r.waiters(10)) {
            assertTrue(t.totalNanos() <= info.span().duration(), t.thread() + " " + t.totalNanos());
        }
        assertEquals(1_000 * MS, r.longest(2).getFirst().start());
        assertEquals(2_000 * MS, r.longest(2).get(1).end());
    }

    @Test
    void aWaitWhollyOutsideTheWindowIsNotReported() {
        final ContentionReport r = new ContentionReport(window(1_000, 2_000), List.of(
                wait(100, 900, LOOP1, REGISTRY, HOUSEKEEPER)));
        assertTrue(r.isEmpty());
        assertEquals(0, r.totalNanos());
        assertEquals(0, r.clippedCount());
    }

    @Test
    void theMinFilterAppliesToTheInWindowPart() {
        // 800 ms of waiting, 50 ms of it inside the window: --min 100ms leaves nothing.
        final List<Wait> straddling = List.of(wait(200, 1_050, LOOP1, REGISTRY, HOUSEKEEPER));
        assertTrue(new ContentionReport(window(1_000, 2_000), straddling, 100 * MS, _ -> true).isEmpty());
        assertEquals(50 * MS, new ContentionReport(window(1_000, 2_000), straddling, 10 * MS, _ -> true).totalNanos());
    }

    @Test
    void convoysAreFollowedOnTheTrueIntervalsAndReportedOnTheClippedOnes() {
        // The housekeeper's own wait began before the window; the walk-back still finds it.
        final ContentionReport r = new ContentionReport(window(1_000, 2_000), List.of(
                wait(1_100, 1_400, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(900, 1_300, HOUSEKEEPER, STORE, FLUSHER)));
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(1, convoys.size());
        assertEquals(LOOP1, convoys.getFirst().head().waiter());
        assertEquals(STORE, convoys.getFirst().links().get(1).lock());
        // The link is printed with its in-window duration: 1 000 ms .. 1 300 ms, not 900 ms .. 1 300 ms.
        assertEquals(300 * MS, convoys.getFirst().links().get(1).duration());
    }

    @Test
    void parksWaitingForWorkAreReportedApartFromContention() {
        // The shape of a real server: one 200 ms lock fight against two idle pool threads
        // parked for the whole recording. Ranked together, the lock never reaches the top.
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                park(0, 9_000, FLUSHER, NO_WORK),
                park(0, 9_500, HOUSEKEEPER, NO_WORK)));

        assertEquals(1, r.waits().size());
        assertEquals(200 * MS, r.totalNanos());
        assertEquals(REGISTRY, r.locks(10).getFirst().lock());
        assertEquals(1, r.locks(10).size());
        assertEquals(1, r.waiters(10).size());

        assertEquals(2, r.workWaits().size());
        assertEquals(2, r.workWaitThreads());
        assertEquals(18_500 * MS, r.workWaitNanos());
        assertEquals(QUEUE, r.workWaitLocks(10).getFirst().lock());
    }

    @Test
    void aParkWaitingForAResultIsStillContention() {
        // Same queue class, but the frames below the park are an application call.
        final ContentionReport r = new ContentionReport(info(), List.of(park(100, 5_000, LOOP1, AWAITING_RESULT)));
        assertEquals(1, r.waits().size());
        assertEquals(4_900 * MS, r.totalNanos());
        assertTrue(r.workWaits().isEmpty());
    }

    @Test
    void theSplitCanBeTurnedOff() {
        final List<Wait> waits = List.of(park(0, 9_000, FLUSHER, NO_WORK));
        final ContentionReport off = new ContentionReport(info(), waits, 0, _ -> true, IdleMatcher.none());
        assertEquals(1, off.waits().size());
        assertTrue(off.workWaits().isEmpty());
    }

    @Test
    void anIdleWorkerIsNeverAConvoyLink() {
        // The housekeeper holds the registry and is itself parked for work: that is not a convoy,
        // it is a thread doing nothing. Only a real second lock makes a chain.
        final ContentionReport r = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                park(50, 400, HOUSEKEEPER, NO_WORK)));
        assertTrue(r.convoys(5, 10).isEmpty(), r.convoys(5, 10).toString());
    }

    @Test
    void everyLockRowCarriesTheStackOfItsLongestWait() {
        // A hot lock of many short waits: none of them is long enough to reach LONGEST WAITS,
        // so the row's own stack is the only way to see where it was taken.
        final Stack cow = stack(new Frame("java.util.concurrent.CopyOnWriteArrayList", "add", 472, "JIT compiled"),
                new Frame("dev.app.Browser", "handleBrowseResult", 282, "JIT compiled"));
        final ContentionReport r = new ContentionReport(info(), List.of(
                new Wait(new Interval(100 * MS, 130 * MS), LOOP1, REGISTRY, HOUSEKEEPER, cow),
                new Wait(new Interval(200 * MS, 220 * MS), LOOP2, REGISTRY, HOUSEKEEPER, cow),
                wait(300, 900, HOUSEKEEPER, STORE, FLUSHER)));

        final ContentionReport.LockStats registry = r.locks(10).stream()
                .filter(l -> l.lock().equals(REGISTRY)).findFirst().orElseThrow();
        assertEquals(30 * MS, registry.longest().duration());
        assertEquals(cow, registry.longest().stack());
        // The store's single wait is the longest in the recording and is its own representative.
        assertEquals(600 * MS, r.locks(10).getFirst().longest().duration());
    }

    @Test
    void theLockFilterSelectsByClassOrByAddress() {
        final List<Wait> all = List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(400, 900, LOOP2, STORE, HOUSEKEEPER));
        final ContentionReport byAddress = new ContentionReport(info(), all, 0, _ -> true, IdleMatcher.forWorkWaits(),
                lock -> lock.pretty().equals("dev.app.Registry@abc"));
        assertEquals(1, byAddress.waits().size());
        assertEquals(REGISTRY, byAddress.locks(10).getFirst().lock());

        final ContentionReport byClass = new ContentionReport(info(), all, 0, _ -> true, IdleMatcher.forWorkWaits(),
                lock -> lock.prettyClass().equals("dev.app.Store"));
        assertEquals(1, byClass.waits().size());
        assertEquals(500 * MS, byClass.totalNanos());

        final ContentionReport none = new ContentionReport(info(), all, 0, _ -> true, IdleMatcher.forWorkWaits(),
                _ -> false);
        assertTrue(none.isEmpty());
    }

    @Test
    void theLockFilterNarrowsTheListNotTheConvoysBehindIt() {
        // --lock Registry: the housekeeper's own wait for the store is not a row of this
        // report, and it is exactly the link that says why the registry was held so long.
        final List<Wait> all = List.of(
                wait(100, 300, LOOP1, REGISTRY, HOUSEKEEPER),
                wait(150, 200, HOUSEKEEPER, STORE, FLUSHER));
        final ContentionReport r = new ContentionReport(info(), all, 0, _ -> true, IdleMatcher.forWorkWaits(),
                lock -> lock.prettyClass().equals("dev.app.Registry"));
        assertEquals(1, r.waits().size());
        assertTrue(r.isFiltered());
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(1, convoys.size());
        assertEquals(LOOP1, convoys.getFirst().head().waiter());
        assertEquals(STORE, convoys.getFirst().links().get(1).lock());
        assertEquals(FLUSHER, convoys.getFirst().links().get(1).owner());
    }

    @Test
    void lockKeyPrettyPrinting() {
        assertEquals("dev.app.Registry@abc", REGISTRY.pretty());
        assertEquals("dev.app.Registry", REGISTRY.prettyClass());
        assertEquals("byte[]@1", new LockKey("[B", 1, Kind.PARK).pretty());
        assertEquals("monitor", Kind.MONITOR_ENTER.label());
        assertEquals("park", Kind.PARK.label());
        final Wait w = wait(1, 2, LOOP1, QUEUE, null);
        assertEquals(Kind.PARK, w.kind());
        assertEquals(MS, w.start());
        assertEquals(2 * MS, w.end());
    }

    @Test
    void filtersApplyAfterHolderResolutionSoNarrowingTheQuestionKeepsTheAnswer() {
        final List<Wait> all = List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2),
                wait(100, 299, LOOP2, REGISTRY, HOUSEKEEPER),
                wait(150, 200, HOUSEKEEPER, STORE, FLUSHER),
                wait(400, 405, LOOP1, QUEUE, null));
        // Only event-loop-1 asked about, only waits of 10 ms and more.
        final ContentionReport r = new ContentionReport(info(), all, 10 * MS, name -> name.equals("event-loop-1"));

        assertEquals(1, r.waits().size());
        final Wait w = r.waits().getFirst();
        assertEquals(LOOP1, w.waiter());
        // The walk-back went through event-loop-2's wait, which the filter does not report.
        assertEquals(HOUSEKEEPER, w.owner());
        assertEquals(List.of(LOOP2), w.via());
        assertEquals(1, r.waiters(10).size());
        assertEquals(LOOP1, r.waiters(10).getFirst().thread());
        assertEquals(200 * MS, r.totalNanos());
        // The convoy still follows the housekeeper into the store lock it was itself waiting for.
        final List<ContentionReport.Convoy> convoys = r.convoys(5, 10);
        assertEquals(1, convoys.size());
        assertEquals(2, convoys.getFirst().depth());
        assertEquals(STORE, convoys.getFirst().links().get(1).lock());
        // The same waits with no filter report everything.
        assertEquals(4, new ContentionReport(info(), all).waits().size());
    }

    /**
     * A worker loop of an application's own making: no frame here is in any idle list, which
     * is the case shape exists for.
     */
    static final Stack MAILBOX = stack(
            new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "parkNanos", 271, "JIT compiled"),
            new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
            new Frame("dev.app.SystemDispatcher$DispatchLoop", "run", 80, "JIT compiled"));

    /**
     * {@code parks} parks of {@code eachMs} on one lock, one waiter, nobody holding it, 10 ms
     * of work between them: a loop that is parked for all but a fraction of its own span.
     */
    static List<Wait> perch(final LockKey lock, final ThreadRef waiter, final int parks, final long eachMs) {
        final List<Wait> out = new ArrayList<>(parks);
        for (int i = 0; i < parks; i++) {
            final long from = i * (eachMs + 10);
            out.add(new Wait(new Interval(from * MS, (from + eachMs) * MS), waiter, lock, null, MAILBOX));
        }
        return out;
    }

    @Test
    void aLockOneThreadSitsOnForMostOfTheRecordingIsNotContention() {
        // The shape of a mailbox, measured rather than named: one waiter, no holder, and the
        // thread is parked there for most of the window. The frame says nothing an idle list
        // would recognise, which is the case this exists for.
        final LockKey mailbox = new LockKey("dev.app.DefaultMailbox", 0x1, Kind.PARK);
        final List<Wait> waits = perch(mailbox, LOOP1, 100, 60);
        final ContentionReport r = new ContentionReport(window(0, 10_000), waits);
        assertTrue(r.waits().isEmpty(), "the mailbox is still reported as contention");
        assertEquals(100, r.workWaits().size());
        assertEquals(1, r.perchCount());

        // The same thread on the same lock for a tenth of the window is a consumer waiting for
        // work someone else owes it: that is a finding, and it stays in the report.
        final ContentionReport busy = new ContentionReport(window(0, 10_000), perch(mailbox, LOOP1, 10, 100));
        assertEquals(10, busy.waits().size());
        assertEquals(0, busy.perchCount());

        // Two threads on it is contention whatever the share, and so is one holder.
        final List<Wait> shared = new ArrayList<>(perch(mailbox, LOOP1, 50, 90));
        shared.add(new Wait(new Interval(0, 5_000 * MS), LOOP2, mailbox, null, MAILBOX));
        assertEquals(51, new ContentionReport(window(0, 10_000), shared).waits().size());
        final List<Wait> held = new ArrayList<>(perch(mailbox, LOOP1, 50, 90));
        held.add(new Wait(new Interval(0, 5_000 * MS), LOOP1, mailbox, HOUSEKEEPER, MAILBOX));
        assertEquals(51, new ContentionReport(window(0, 10_000), held).waits().size());

        // One park covering the window is a thread that is stuck. That is the most important
        // thing the report can say, so shape never files it away.
        final List<Wait> stuck = List.of(new Wait(new Interval(0, 9_000 * MS), LOOP1, mailbox, null, MAILBOX));
        assertEquals(1, new ContentionReport(window(0, 10_000), stuck).waits().size());

        // --idle none turns off every idle rule, this one included.
        final ContentionReport raw = new ContentionReport(window(0, 10_000), waits, 0, _ -> true, IdleMatcher.none());
        assertEquals(100, raw.waits().size());
        assertEquals(0, raw.perchCount());
    }

    @Test
    void theWorkerThatWasBusyParksWhereTheIdleOnesDo() {
        // Twelve dispatchers idle on their own mailbox and a thirteenth that was busy for two
        // thirds of the recording. Its lock is under the share on its own, and a rule that let
        // the threshold decide would put that one lock, alone, at the top of the contention it
        // is not part of. The stack the measurement found answers for it.
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            waits.addAll(perch(new LockKey("dev.app.DefaultMailbox", 0x10 + i, Kind.PARK),
                    new ThreadRef(10 + i, "dispatcher-" + i), 100, 60));
        }
        final LockKey busyOne = new LockKey("dev.app.DefaultMailbox", 0x99, Kind.PARK);
        waits.addAll(perch(busyOne, new ThreadRef(99, "dispatcher-12"), 20, 60));
        final ContentionReport r = new ContentionReport(window(0, 10_000), waits);
        assertTrue(r.waits().isEmpty(), "the busy worker's own mailbox is reported as contention");
        assertEquals(13, r.perchCount());

        // The propagation follows the stack, not the class: a lock nobody idles on keeps its row.
        final List<Wait> withReal = new ArrayList<>(waits);
        withReal.add(new Wait(new Interval(0, 2_000 * MS), FLUSHER, STORE, HOUSEKEEPER, AWAITING_RESULT));
        assertEquals(1, new ContentionReport(window(0, 10_000), withReal).waits().size());
    }

    @Test
    void aStacklessPerchSpeaksForNoOtherLock() {
        // A perch whose parks carry no stack names no loop. Every other stackless lock prints
        // the same nothing, and one of them here is two threads' real 400 ms wait.
        final LockKey mailbox = new LockKey("dev.app.DefaultMailbox", 0x1, Kind.PARK);
        final LockKey other = new LockKey("dev.app.Reply", 0x2, Kind.PARK);
        final List<Wait> waits = new ArrayList<>();
        for (final Wait w : perch(mailbox, LOOP1, 100, 60)) {
            waits.add(new Wait(w.interval(), w.waiter(), w.lock(), null, Stack.EMPTY));
        }
        waits.add(wait(100, 300, LOOP2, other, null));
        waits.add(wait(400, 600, LOOP2, other, null));
        final ContentionReport r = new ContentionReport(window(0, 10_000), waits);
        assertEquals(2, r.waits().size(), "a stackless perch swallowed an unrelated stackless lock");
        assertEquals(other, r.locks(10).getFirst().lock());
        // The perch itself is still recognised by its own shape.
        assertEquals(100, r.workWaits().size());
        assertEquals(1, r.perchCount());
    }

    @Test
    void aLoopIsTheStackAsItPrintsWhateverTheFrameKinds() {
        // The busy dispatcher's stack is the idle ones' with one frame inlined rather than
        // compiled: it prints the same, so it is the same loop and answers to the same verdict.
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            waits.addAll(perch(new LockKey("dev.app.DefaultMailbox", 0x10 + i, Kind.PARK),
                    new ThreadRef(10 + i, "dispatcher-" + i), 100, 60));
        }
        final Stack inlined = stack(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("java.util.concurrent.locks.LockSupport", "parkNanos", 271, "Inlined"),
                new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"),
                new Frame("dev.app.SystemDispatcher$DispatchLoop", "run", 80, "JIT compiled"));
        assertEquals(MAILBOX.pretty("", 10), inlined.pretty("", 10));
        final LockKey busyOne = new LockKey("dev.app.DefaultMailbox", 0x99, Kind.PARK);
        for (final Wait w : perch(busyOne, new ThreadRef(99, "dispatcher-12"), 20, 60)) {
            waits.add(new Wait(w.interval(), w.waiter(), w.lock(), null, inlined));
        }
        final ContentionReport r = new ContentionReport(window(0, 10_000), waits);
        assertTrue(r.waits().isEmpty(), "the busy worker's mailbox is reported as contention");
        assertEquals(13, r.perchCount());
    }

    @Test
    void recognisedByShapeCountsTheListedLocksTheIdleListDidNotName() {
        // Three locks with a perch's shape: the flusher's is a pool worker the idle list names,
        // and the two mailboxes are not in any list. Only the mailboxes were found by shape.
        final LockKey pool = new LockKey("java.util.concurrent.LinkedBlockingQueue", 0x1, Kind.PARK);
        final LockKey mailbox1 = new LockKey("dev.app.DefaultMailbox", 0x2, Kind.PARK);
        final LockKey mailbox2 = new LockKey("dev.app.DefaultMailbox", 0x3, Kind.PARK);
        final List<Wait> waits = new ArrayList<>();
        for (final Wait w : perch(pool, FLUSHER, 100, 60)) {
            waits.add(new Wait(w.interval(), w.waiter(), w.lock(), null, NO_WORK));
        }
        waits.addAll(perch(mailbox1, LOOP1, 100, 60));
        waits.addAll(perch(mailbox2, LOOP2, 100, 60));
        final ContentionReport all = new ContentionReport(window(0, 10_000), waits);
        assertEquals(300, all.workWaits().size());
        assertEquals(2, all.perchCount());
        // --thread leaves event-loop-2 out: its mailbox is not a row, so it is not counted.
        final ContentionReport one = new ContentionReport(window(0, 10_000), waits, 0,
                name -> !name.equals("event-loop-2"));
        assertEquals(200, one.workWaits().size());
        assertEquals(1, one.perchCount());
    }

    @Test
    void aPerchIsDecidedBeforeTheFiltersNarrowTheReport() {
        // --min hides the short parks that prove the lock is a perch; the verdict is reached
        // on every wait in the file, so what the reader passes cannot change what it is. The
        // three long parks that pass --min are a small share of the window on their own.
        final LockKey mailbox = new LockKey("dev.app.DefaultMailbox", 0x1, Kind.PARK);
        final List<Wait> waits = new ArrayList<>(perch(mailbox, LOOP1, 100, 60));
        for (int i = 0; i < 3; i++) {
            final long from = 7_000 + i * 210;
            waits.add(new Wait(new Interval(from * MS, (from + 200) * MS), LOOP1, mailbox, null, MAILBOX));
        }
        final ContentionReport r = new ContentionReport(window(0, 11_000), waits, 100 * MS, _ -> true);
        assertTrue(r.waits().isEmpty(), "--min turned the perch back into contention");
        assertEquals(3, r.workWaits().size());
        assertEquals(1, r.perchCount());

        // When the filters list none of its parks, the lock is not a row the reader can see,
        // and "recognised by shape" does not count it.
        final ContentionReport hidden = new ContentionReport(window(0, 11_000), waits, 300 * MS, _ -> true);
        assertTrue(hidden.waits().isEmpty());
        assertTrue(hidden.workWaits().isEmpty());
        assertEquals(0, hidden.perchCount());
    }

    @Test
    void locksWhoseLongestWaitPrintsTheSameStackAreOneGroup() {
        // One mailbox per worker: as many locks as workers, one stack between them.
        final Stack mailbox = stack(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("dev.app.DefaultMailbox", "awaitNextMessage", 92, "JIT compiled"));
        final List<Wait> waits = List.of(
                new Wait(new Interval(100 * MS, 300 * MS), LOOP1, new LockKey("dev.app.Mailbox", 0x1, Kind.PARK),
                        null, mailbox),
                new Wait(new Interval(100 * MS, 250 * MS), LOOP2, new LockKey("dev.app.Mailbox", 0x2, Kind.PARK),
                        null, mailbox),
                new Wait(new Interval(100 * MS, 200 * MS), HOUSEKEEPER, REGISTRY, FLUSHER, AWAITING_RESULT));
        final ContentionReport r = new ContentionReport(info(), waits);

        assertEquals(3, r.locks(10).size());
        final List<ContentionReport.StackGroup> groups = r.lockStacks(10, 6);
        assertEquals(2, groups.size());
        // Rank order is kept: the two mailboxes total more than the registry.
        assertEquals(2, groups.getFirst().locks().size());
        assertEquals(200 * MS, groups.getFirst().longest().duration());
        assertEquals(1, groups.get(1).locks().size());
        assertEquals(REGISTRY, groups.get(1).locks().getFirst());
        // Locks whose longest wait carries no stack have nothing to tell them apart either,
        // so they are one group too: the row still names them and gives the longest wait.
        final List<ContentionReport.StackGroup> stackless = new ContentionReport(info(), List.of(
                wait(100, 300, LOOP1, REGISTRY, LOOP2), wait(100, 200, LOOP2, STORE, FLUSHER))).lockStacks(10, 6);
        assertEquals(1, stackless.size());
        assertEquals(List.of(REGISTRY, STORE), stackless.getFirst().locks());
    }

    @Test
    void lockSitesRankTheStackAcrossEveryInstanceOfIt() {
        // One queue per in-flight request: each instance waits far less than the registry, so
        // every one of them ranks below it, and together they are the larger cost. Ranked per
        // instance the site is invisible; ranked per stack it is the first row.
        final Stack queue = stack(
                new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
                new Frame("dev.app.ChannelBrowseSink", "take", 154, "JIT compiled"));
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            waits.add(new Wait(new Interval(100 * MS, 200 * MS), new ThreadRef(10 + i, "browse-" + i),
                    new LockKey("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject",
                            0x100 + i, Kind.PARK), null, queue));
        }
        waits.add(new Wait(new Interval(100 * MS, 400 * MS), HOUSEKEEPER, REGISTRY, FLUSHER, AWAITING_RESULT));
        final ContentionReport r = new ContentionReport(window(0, 10_000), waits, 0, _ -> true, IdleMatcher.none());

        // Per instance, the registry's single 300 ms wait outranks every 100 ms queue.
        assertEquals(REGISTRY, r.locks(10).getFirst().lock());
        final List<ContentionReport.SiteStats> sites = r.lockSites(10);
        assertEquals(2, sites.size());
        final ContentionReport.SiteStats first = sites.getFirst();
        assertEquals(6, first.locks().size());
        assertEquals(600 * MS, first.totalNanos());
        assertEquals(6, first.count());
        assertEquals(100 * MS, first.maxNanos());
        assertEquals(6, first.waiters().size());
        // Named in the order they came, the same on every run.
        assertEquals(List.of("browse-0", "browse-1", "browse-2", "browse-3", "browse-4", "browse-5"),
                first.waiters().stream().map(ThreadRef::name).toList());
        assertTrue(first.owners().isEmpty());
        assertEquals(Kind.PARK, first.kind());
        // The registry is the second site, with its holder kept.
        assertEquals(List.of(REGISTRY), sites.get(1).locks());
        assertEquals(Set.of(FLUSHER), sites.get(1).owners());
        // --top caps the sites, not the instances behind them.
        assertEquals(1, r.lockSites(1).size());
        assertEquals(6, r.lockSites(1).getFirst().locks().size());
    }

    @Test
    void aSiteIsTheStackToOneDepthWhoeverPrintsIt() {
        // Two queues whose stacks differ only in their ninth frame: one site at the depth sites
        // are compared, which is the depth both renderers print them at.
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final List<Frame> frames = new ArrayList<>();
            for (int f = 0; f < 10; f++) {
                frames.add(new Frame("dev.app.Layer" + f, "call", f == 8 ? 100 + i : 1, "JIT compiled"));
            }
            waits.add(new Wait(new Interval(100 * MS, 200 * MS), new ThreadRef(10 + i, "browse-" + i),
                    new LockKey("dev.app.Queue", 0x100 + i, Kind.PARK), null, new Stack(frames, false)));
        }
        final ContentionReport r = new ContentionReport(info(), waits, 0, _ -> true, IdleMatcher.none());
        assertTrue(ContentionReport.SITE_FRAMES < 9);
        assertEquals(1, r.lockSites(10).size());
        assertEquals(2, r.lockSites(10).getFirst().locks().size());
    }

    /**
     * {@link #perch} with the lock at a new address from park {@code 33} and again from park
     * {@code 66}: what a collection that moves the mailbox twice leaves in the file. Each
     * thread's mailbox has addresses of its own, from {@code base}.
     */
    static List<Wait> movedPerch(final ThreadRef waiter, final long base) {
        final List<Wait> out = new ArrayList<>();
        final List<Wait> parks = perch(new LockKey("dev.app.DefaultMailbox", base, Kind.PARK), waiter, 100, 60);
        for (int i = 0; i < parks.size(); i++) {
            final Wait w = parks.get(i);
            final LockKey at = new LockKey("dev.app.DefaultMailbox", base + (i < 33 ? 0 : i < 66 ? 1 : 2), Kind.PARK);
            out.add(new Wait(w.interval(), w.waiter(), at, null, w.stack()));
        }
        return out;
    }

    /** Pauses as flat (start, end) pairs, in milliseconds. */
    static LongList pauses(final long... ms) {
        final LongList out = new LongList();
        for (final long m : ms) {
            out.add(m * MS);
        }
        return out;
    }

    static ContentionReport withPauses(final List<Wait> waits, final LongList pauses) {
        return new ContentionReport(window(0, 10_000), waits, 0, _ -> true, IdleMatcher.forWorkWaits(), _ -> true, pauses);
    }

    /** The two collections that moved {@link #movedPerch}'s mailbox. */
    static final long[] TWO_MOVES = {2_305, 2_310, 4_615, 4_620};

    @Test
    void aLockTheCollectorMovedIsLabelledAndStillCounted() {
        // Park 32 ends at 2 300 ms and park 33 at 2 370 ms; park 65 at 4 610 ms and park 66 at
        // 4 680 ms. Each address holds the thread for about a fifth of the window, none over the
        // line on its own; together they are 60 % of it. Two pauses in 6.9 s meet a 70 ms change
        // by chance at odds of 1 in 50, both changes at about 1 in 2 400.
        final List<Wait> waits = movedPerch(LOOP1, 0x10);
        final ContentionReport r = withPauses(waits, pauses(TWO_MOVES));
        // Timing is the only evidence the three addresses are one object, so nothing is set aside.
        assertEquals(100, r.waits().size());
        assertEquals(0, r.perchCount());
        assertEquals(1, r.moved().size());
        final ContentionReport.MovedLock m = r.moved().getFirst();
        assertEquals(LOOP1, m.waiter());
        assertEquals(3, m.locks().size());
        assertEquals(100, m.count());
        assertEquals(r.totalNanos(), m.totalNanos());
        assertEquals(r.totalNanos(), r.movedNanos());
        assertTrue(m.chanceLog10() < -3.3 && m.chanceLog10() > -3.5, Double.toString(m.chanceLog10()));

        // Without the pauses nothing says the three addresses are one object.
        assertTrue(withPauses(waits, pauses()).moved().isEmpty());
        // A change of address no pause explains is a new object.
        assertTrue(withPauses(waits, pauses(2_305, 2_310)).moved().isEmpty());
        // It is a label, not an idle rule: --idle none shows it too.
        final ContentionReport raw = new ContentionReport(window(0, 10_000), waits, 0, _ -> true, IdleMatcher.none(),
                _ -> true, pauses(TWO_MOVES));
        assertEquals(100, raw.waits().size());
        assertEquals(1, raw.moved().size());
    }

    @Test
    void eachThreadsMovedMailboxIsLabelledOnItsOwn() {
        // A pool: two workers in one loop, each on a mailbox of its own that the same two
        // collections moved. Gathering by loop alone would see two waiters and label neither.
        final List<Wait> waits = new ArrayList<>(movedPerch(LOOP1, 0x10));
        waits.addAll(movedPerch(LOOP2, 0x20));
        final ContentionReport r = withPauses(waits, pauses(TWO_MOVES));
        assertEquals(200, r.waits().size());
        assertEquals(2, r.moved().size());

        // A third thread in the loop that parked once has one address and nothing moved: no label.
        waits.add(new Wait(new Interval(0, 1_000 * MS), HOUSEKEEPER,
                new LockKey("dev.app.DefaultMailbox", 0x30, Kind.PARK), null, MAILBOX));
        final ContentionReport three = withPauses(waits, pauses(TWO_MOVES));
        assertEquals(201, three.waits().size());
        assertEquals(2, three.moved().size());
    }

    @Test
    void aConsumerWhoseOwnAllocationCollectsEveryRequestIsLabelledButNeverSetAside() {
        // The case that makes timing evidence and not proof: a consumer waits on a new future
        // per request, alternately 100 ms and 900 ms, and the work between two waits allocates
        // enough to set off a young collection every time. Every change of address has a pause in
        // it by cause, and the short changes are unlikely by chance, so the label fires. The
        // waits must still be counted: a slow downstream reported as idle is the worst answer.
        final List<Wait> waits = new ArrayList<>();
        final LongList pauses = new LongList();
        long t = 0;
        for (int i = 0; i < 40; i++) {
            final long length = i % 2 == 0 ? 100 : 900;
            waits.add(new Wait(new Interval(t * MS, (t + length) * MS), LOOP1,
                    new LockKey("java.util.concurrent.CompletableFuture$Signaller", 0x100 + i, Kind.PARK), null, MAILBOX));
            t += length;
            pauses.add((t + 20) * MS);
            pauses.add((t + 25) * MS);
            t += 50;
        }
        final ContentionReport r = new ContentionReport(window(0, t), waits, 0, _ -> true, IdleMatcher.forWorkWaits(),
                _ -> true, pauses);
        assertEquals(1, r.moved().size(), "the timing looks like a move: it is labelled as one");
        assertEquals(40, r.waits().size());
        assertEquals(r.totalNanos(), r.movedNanos());
        assertTrue(r.workWaits().isEmpty());
        assertEquals(0, r.perchCount());
    }

    @Test
    void longWaitsOnNewObjectsAreNotAMovedLock() {
        // A consumer waits 800 ms on a new future per request, with a collection every 200 ms:
        // every change of address has a pause in it, because every wait does. The pauses come
        // too often for that to say anything, and there is no label.
        final List<Wait> waits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final long from = i * 900L;
            waits.add(new Wait(new Interval(from * MS, (from + 800) * MS), LOOP1,
                    new LockKey("java.util.concurrent.CompletableFuture$Signaller", 0x100 + i, Kind.PARK), null, MAILBOX));
        }
        final LongList every200 = new LongList();
        for (long p = 100; p < 10_000; p += 200) {
            every200.add(p * MS);
            every200.add((p + 5) * MS);
        }
        final ContentionReport r = withPauses(waits, every200);
        assertEquals(10, r.waits().size());
        assertTrue(r.moved().isEmpty());
    }
}
