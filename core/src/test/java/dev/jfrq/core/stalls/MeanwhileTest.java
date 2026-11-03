// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.jfr.Absent;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.SiteKey;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Stall.LockHolder;
import dev.jfrq.core.stalls.Stall.Verdict;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;

import static dev.jfrq.core.stalls.StallAnalysisTest.BURN;
import static dev.jfrq.core.stalls.StallAnalysisTest.HOLDER;
import static dev.jfrq.core.stalls.StallAnalysisTest.LOOP;
import static dev.jfrq.core.stalls.StallAnalysisTest.MS;
import static dev.jfrq.core.stalls.StallAnalysisTest.OTHER;
import static dev.jfrq.core.stalls.StallAnalysisTest.busy;
import static dev.jfrq.core.stalls.StallAnalysisTest.idle;
import static dev.jfrq.core.stalls.StallAnalysisTest.sampledInfo;
import static dev.jfrq.core.stalls.StallAnalysisTest.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The threads seen running while a stall with no recorded holder lasted ([Meanwhile]): which
/// stalls get them, which samples count, how they rank. Hand-built samples; times in
/// milliseconds, gap 50 ms.
class MeanwhileTest {

    /** Where a watched thread parks on a {@code ReentrantLock}: nothing in it says who holds the lock. */
    static final Stack LOCKING = stack(new Frame("jdk.internal.misc.Unsafe", "park", 0, "Native"),
            new Frame("java.util.concurrent.locks.LockSupport", "park", 223, "JIT compiled"),
            new Frame("java.util.concurrent.locks.ReentrantLock", "lock", 323, "JIT compiled"),
            new Frame("dev.app.Painter", "paint", 40, "JIT compiled"));
    static final Stack BLIT = stack(new Frame("sun.java2d.metal.MTLLayer", "blitTexture", 0, "Native"),
            new Frame("dev.app.Renderer", "flush", 12, "JIT compiled"));
    static final Stack ACCEPT = stack(new Frame("sun.nio.ch.Net", "accept", 0, "Native"),
            new Frame("sun.nio.ch.NioSocketImpl", "accept", 760, "JIT compiled"));
    static final Stack REAPER = stack(new Frame("java.lang.ProcessHandleImpl", "waitForProcessExit0", 0, "Native"),
            new Frame("java.lang.ProcessHandleImpl$1", "run", 189, "Interpreted"));
    static final Stack PIPE = stack(new Frame("java.io.FileInputStream", "readBytes", 0, "Native"),
            new Frame("java.io.FileInputStream", "read", 287, "JIT compiled"));
    static final Stack SLEEPING = stack(new Frame("java.lang.Thread", "sleepNanos0", 0, "Native"),
            new Frame("java.lang.Thread", "sleep", 509, "JIT compiled"));
    static final ThreadRef PAINTER = new ThreadRef(4, "painter");
    static final ThreadRef READER = new ThreadRef(5, "reader");

    /** LOOP idle around a 1 s park on the lock, from 1,000 to 2,000 ms, plus the given other threads. */
    static StallReport parkedWhile(final Block park, final ThreadTimeline... others) {
        final List<Sample> loop = new ArrayList<>(idle(0, 1_000, 20));
        loop.addAll(idle(2_000, 3_000, 20));
        final List<ThreadTimeline> timelines = new ArrayList<>();
        timelines.add(new ThreadTimeline(LOOP, loop, List.of(park)));
        timelines.addAll(List.of(others));
        return new StallAnalysis(50 * MS).analyse(sampledInfo(), timelines, List.of());
    }

    static Block parkOnLock() {
        return new Block(new Interval(1_000 * MS, 2_000 * MS), BlockKind.PARK,
                "on java.util.concurrent.locks.ReentrantLock$NonfairSync@1", LOCKING, false);
    }

    static Stall loopStall(final StallReport r) {
        return r.stallsOf(LOOP).getFirst();
    }

    static List<Sample> samples(final long from, final long to, final long step, final Stack stack,
                                final boolean inNative) {
        return busy(from, to, step, stack, inNative);
    }

    /// A park on a lock names the thread that ran while it lasted, labelled apart from the
    /// holder, and leaves out a thread whose samples were all native waits that run nothing.
    @Test
    void aParkOnALockNamesWhoRanMeanwhileAndNotWhoWaited() {
        final StallReport r = parkedWhile(parkOnLock(),
                new ThreadTimeline(PAINTER, samples(1_000, 2_000, 50, BLIT, true), List.of()),
                new ThreadTimeline(OTHER, samples(1_000, 2_000, 10, ACCEPT, true), List.of()),
                new ThreadTimeline(READER, samples(1_000, 2_000, 10, PIPE, true), List.of()));
        final Stall s = loopStall(r);
        assertEquals(Verdict.PARKED, s.verdict());
        assertEquals(1, s.meanwhile().size(), s.meanwhile().toString());
        final Meanwhile m = s.meanwhile().getFirst();
        assertEquals(PAINTER, m.thread());
        assertEquals(0, m.javaSamples());
        assertEquals(20, m.nativeSamples());
        assertEquals("sun.java2d.metal.MTLLayer.blitTexture", m.frame());
        assertEquals("painter: 20 native samples, all in sun.java2d.metal.MTLLayer.blitTexture", m.describe());
    }

    /// A monitor whose holder the recording names keeps it alone; one held by nobody known gets
    /// the threads seen meanwhile.
    @Test
    void aKnownHolderIsNeverJoinedByACorrelation() {
        final ThreadTimeline painter = new ThreadTimeline(PAINTER, samples(1_000, 2_000, 50, BLIT, true), List.of());
        final Block held = new Block(new Interval(1_000 * MS, 2_000 * MS), BlockKind.MONITOR, "dev.app.Registry@1",
                LOCKING, HOLDER);
        final Stall known = loopStall(parkedWhile(held, painter));
        assertEquals(Verdict.BLOCKED_MONITOR, known.verdict());
        assertTrue(known.detail().endsWith("held by housekeeper"), known.detail());
        assertTrue(known.meanwhile().isEmpty(), known.meanwhile().toString());

        final Block unknown = new Block(new Interval(1_000 * MS, 2_000 * MS), BlockKind.MONITOR, "dev.app.Registry@1",
                LOCKING, null);
        final Stall nobody = loopStall(parkedWhile(unknown, painter));
        assertTrue(nobody.detail().endsWith("held by unknown"), nobody.detail());
        assertEquals(PAINTER, nobody.meanwhile().getFirst().thread());
    }

    /// One blocking event says whether it is a lock and whether its holder is recorded; events
    /// combined name a holder if any of them does, and make no lock only if none of them is one.
    @Test
    void aLockHolderIsReadFromTheEventsAndCombinedSoAKnownHolderWins() {
        final Interval at = new Interval(0, 100 * MS);
        assertEquals(LockHolder.UNRECORDED, LockHolder.of(new Block(at, BlockKind.PARK, "on X@1", LOCKING, false)));
        assertEquals(LockHolder.NOT_A_LOCK,
                LockHolder.of(new Block(at, BlockKind.PARK, Absent.NO_BLOCKER, LOCKING, false)));
        assertEquals(LockHolder.KNOWN, LockHolder.of(new Block(at, BlockKind.MONITOR, "X@1", LOCKING, HOLDER)));
        assertEquals(LockHolder.UNRECORDED, LockHolder.of(new Block(at, BlockKind.MONITOR, "X@1", LOCKING, null)));
        for (final BlockKind kind : List.of(BlockKind.OBJECT_WAIT, BlockKind.SLEEP, BlockKind.SOCKET_READ,
                BlockKind.SOCKET_WRITE, BlockKind.FILE_READ, BlockKind.FILE_WRITE, BlockKind.FILE_FORCE)) {
            assertEquals(LockHolder.NOT_A_LOCK, LockHolder.of(new Block(at, kind, "x", LOCKING, false)), kind.name());
        }
        for (final LockHolder h : LockHolder.values()) {
            assertEquals(LockHolder.KNOWN, h.and(LockHolder.KNOWN));
            assertEquals(LockHolder.KNOWN, LockHolder.KNOWN.and(h));
            assertEquals(h, h.and(LockHolder.NOT_A_LOCK));
            assertEquals(h, LockHolder.NOT_A_LOCK.and(h));
        }
        assertEquals(LockHolder.UNRECORDED, LockHolder.UNRECORDED.and(LockHolder.UNRECORDED));
    }

    /// A silence that twenty short parks on a lock explain together is a lock with no recorded
    /// holder; twenty monitor waits of which one names a holder are a lock whose holder is known.
    @Test
    void aSilenceManyWaitsExplainCarriesTheirHolder() {
        final ThreadTimeline painter = new ThreadTimeline(PAINTER, samples(1_000, 2_000, 50, BLIT, true), List.of());
        final List<Block> parks = new ArrayList<>();
        final List<Block> monitors = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            final Interval each = new Interval((1_000 + 50L * k) * MS, (1_040 + 50L * k) * MS);
            parks.add(new Block(each, BlockKind.PARK, "on java.util.concurrent.locks.ReentrantLock$NonfairSync@1",
                    LOCKING, false));
            monitors.add(new Block(each, BlockKind.MONITOR, "dev.app.Registry@1", LOCKING, k == 1 ? HOLDER : null));
        }
        final Stall parked = silenceOf(parks, painter);
        assertEquals(Verdict.PARKED, parked.verdict());
        assertTrue(parked.detail().startsWith("20 × parked on"), parked.detail());
        assertEquals(LockHolder.UNRECORDED, parked.holder());
        assertEquals(PAINTER, parked.meanwhile().getFirst().thread());

        final Stall blocked = silenceOf(monitors, painter);
        assertEquals(Verdict.BLOCKED_MONITOR, blocked.verdict());
        assertEquals(LockHolder.KNOWN, blocked.holder());
        assertTrue(blocked.meanwhile().isEmpty(), blocked.meanwhile().toString());
    }

    /// Marking a silence simultaneous with another thread's, and cutting a run's tail, rewrite the
    /// detail and the interval, never what the stall rests on.
    @Test
    void rewritingAStallKeepsItsHolder() {
        final Interval at = new Interval(0, 100 * MS);
        final Stall a = new Stall(LOOP, at, Verdict.UNEXPLAINED, "a", Stack.EMPTY, Stall.Evidence.SILENCE, 0,
                LockHolder.UNRECORDED);
        final Stall b = new Stall(OTHER, at, Verdict.UNEXPLAINED, "b", Stack.EMPTY, Stall.Evidence.SILENCE, 0,
                LockHolder.KNOWN);
        final List<Stall> marked = StallAnalysis.markSimultaneous(List.of(a, b));
        assertTrue(marked.getFirst().detail().contains("simultaneous on 2"), marked.getFirst().detail());
        assertEquals(LockHolder.UNRECORDED, marked.getFirst().holder());
        assertEquals(LockHolder.KNOWN, marked.getLast().holder());
    }

    /** LOOP idle every 20 ms but for a silence from 1,000 to 2,000 ms that {@code blocks} explain. */
    private static Stall silenceOf(final List<Block> blocks, final ThreadTimeline other) {
        final List<Sample> loop = new ArrayList<>(idle(0, 1_000, 20));
        loop.addAll(idle(2_000, 3_000, 20));
        final StallReport r = new StallAnalysis(50 * MS).analyse(sampledInfo(),
                List.of(new ThreadTimeline(LOOP, loop, blocks), other), List.of());
        assertEquals(1, r.stallsOf(LOOP).size(), r.stallsOf(LOOP).toString());
        final Stall s = r.stallsOf(LOOP).getFirst();
        assertEquals(Stall.Evidence.SILENCE, s.evidence());
        return s;
    }

    /// A park on no object has nothing anyone holds, and a sleep is the thread's own: neither
    /// names a thread seen meanwhile.
    @Test
    void aWaitOnNothingAnyoneHoldsNamesNobody() {
        final ThreadTimeline painter = new ThreadTimeline(PAINTER, samples(1_000, 2_000, 50, BLIT, true), List.of());
        final Block noObject = new Block(new Interval(1_000 * MS, 2_000 * MS), BlockKind.PARK, Absent.NO_BLOCKER,
                LOCKING, false);
        assertTrue(loopStall(parkedWhile(noObject, painter)).meanwhile().isEmpty());
        final Block sleep = new Block(new Interval(1_000 * MS, 2_000 * MS), BlockKind.SLEEP, "", SLEEPING, false);
        assertTrue(loopStall(parkedWhile(sleep, painter)).meanwhile().isEmpty());
    }

    /// A stall no other thread was sampled in prints nothing, rather than "nobody ran".
    @Test
    void noSampleInTheStallIsNoLine() {
        final ThreadTimeline before = new ThreadTimeline(PAINTER, samples(0, 990, 50, BLIT, true), List.of());
        assertTrue(loopStall(parkedWhile(parkOnLock(), before)).meanwhile().isEmpty());
    }

    /// Java samples rank first, then native ones, then the name; three are named, the stalled
    /// thread never, and a sample outside the stall does not count.
    @Test
    void javaSamplesRankFirstAndThreeAreNamed() {
        final ThreadRef a = new ThreadRef(10, "a");
        final ThreadRef b = new ThreadRef(11, "b");
        final ThreadRef c = new ThreadRef(12, "c");
        final ThreadRef d = new ThreadRef(13, "d");
        final ThreadRef e = new ThreadRef(14, "e");
        final Bystanders bystanders = new Bystanders(IdleMatcher.forBystanders(IdleMatcher.defaults()), null);
        bystanders.add(a, flat(samples(100, 130, 10, BURN, false)));
        bystanders.add(b, flat(samples(100, 190, 10, BLIT, true)));
        final List<Sample> cs = new ArrayList<>(samples(100, 130, 10, BURN, false));
        cs.add(new Sample(150 * MS, BLIT, false, true));
        bystanders.add(c, flat(cs));
        bystanders.add(d, flat(samples(100, 110, 10, BURN, false)));
        bystanders.add(e, flat(samples(300, 400, 10, BURN, false)));
        bystanders.add(LOOP, samples(100, 200, 5, BURN, false));
        final Interval stall = new Interval(100 * MS, 200 * MS);

        final List<Meanwhile> seen = bystanders.during(LOOP, stall);
        assertEquals(List.of(c, a, d), seen.stream().map(Meanwhile::thread).toList());
        assertEquals("c: 3 Java and 1 native samples, 3 of them in dev.app.CpuWork.burn", seen.getFirst().describe());
        assertEquals("d: 1 Java sample in dev.app.CpuWork.burn", seen.getLast().describe());
        // Watched itself, LOOP is named when another thread asks.
        assertEquals(LOOP, bystanders.during(a, stall).getFirst().thread());
    }

    /// The native waits that run nothing are idle for a thread seen meanwhile, the work is not,
    /// and --idle none leaves every sample counted.
    @Test
    void nativeWaitsThatRunNothingAreIdleForBystanders() {
        final IdleMatcher bystanders = IdleMatcher.forBystanders(IdleMatcher.defaults());
        for (final Stack waiting : List.of(ACCEPT, REAPER, PIPE, SLEEPING, StallAnalysisTest.IDLE, LOCKING)) {
            assertTrue(bystanders.isIdle(waiting), waiting.toString());
        }
        assertFalse(bystanders.isIdle(BLIT));
        assertFalse(bystanders.isIdle(BURN));
        // A thread watched as an event loop is not idle in a pipe read: that is a stall of its own.
        assertFalse(IdleMatcher.defaults().isIdle(PIPE));
        assertTrue(IdleMatcher.forBystanders(IdleMatcher.none()).matchesNothing());
        // A loop the caller named is at rest on any thread.
        final IdleMatcher named = IdleMatcher.forBystanders(IdleMatcher.of("dev\\.app\\.Renderer\\.flush"));
        assertTrue(named.isIdle(BLIT) && named.isIdle(ACCEPT));
    }

    /// Samples that arrive out of order, a little or wholly, and long and short questions in any
    /// order, give the counts a straight count gives.
    @Test
    void theAnswerDoesNotDependOnArrivalOrderOrOnWhichQuestionCameFirst() {
        final List<Sample> sorted = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            sorted.add(new Sample(i * 10 * MS, i % 4 == 0 ? ACCEPT : BURN, false, i % 2 == 0));
        }
        final List<Sample> reversed = new ArrayList<>(sorted.reversed());
        final List<Sample> swapped = new ArrayList<>(sorted);
        for (int i = 0; i + 1 < swapped.size(); i += 7) {
            swapped.set(i, sorted.get(i + 1));
            swapped.set(i + 1, sorted.get(i));
        }
        final List<Interval> questions = List.of(new Interval(1_000 * MS, 1_200 * MS),
                new Interval(0, 4_000 * MS), new Interval(1_000 * MS, 1_200 * MS), new Interval(405 * MS, 425 * MS));
        for (final List<Sample> arrival : List.of(sorted, reversed, swapped)) {
            final Bystanders bystanders = new Bystanders(IdleMatcher.forBystanders(IdleMatcher.defaults()), null);
            bystanders.add(PAINTER, flat(arrival));
            for (final Interval q : questions) {
                int java = 0;
                int inNative = 0;
                for (final Sample s : sorted) {
                    if (s.time() >= q.start() && s.time() < q.end() && s.stack() == BURN) {
                        java += s.isInNative() ? 0 : 1;
                        inNative += s.isInNative() ? 1 : 0;
                    }
                }
                final Meanwhile m = bystanders.during(LOOP, q).getFirst();
                assertEquals(java, m.javaSamples(), q.toString());
                assertEquals(inNative, m.nativeSamples(), q.toString());
            }
        }
    }

    /// Under --app the site is named beside the innermost frame.
    @Test
    void underAppTheSiteIsNamed() {
        final Bystanders bystanders = new Bystanders(IdleMatcher.forBystanders(IdleMatcher.defaults()),
                SiteKey.inPackages(List.of("dev.app")));
        bystanders.add(PAINTER, flat(samples(100, 130, 10, BLIT, true)));
        final Meanwhile m = bystanders.during(LOOP, new Interval(100 * MS, 200 * MS)).getFirst();
        assertEquals("dev.app.Renderer.flush", m.siteOrNull());
        assertEquals("painter: 3 native samples, all in sun.java2d.metal.MTLLayer.blitTexture, site "
                + "dev.app.Renderer.flush", m.describe());
    }

    private static Bystanders.Samples flat(final List<Sample> samples) {
        final Bystanders.Samples out = new Bystanders.Samples();
        for (final Sample s : samples) {
            out.add(s.time(), s.stack(), s.isInNative());
        }
        return out;
    }
}
