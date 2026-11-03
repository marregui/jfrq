// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.locks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.ThreadFilter;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Holders;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.stalls.Perch;
import dev.jfrq.core.util.Sorts;

/**
 * Lock contention in one recording: per-lock and per-thread totals, the longest
 * individual waits in time order, and <em>convoys</em>: chains where the thread that held
 * the lock was itself blocked on another lock at the time.
 */
public final class ContentionReport {

    /**
     * Totals for one lock.
     *
     * @param longest the longest single wait for it, whose stack stands for the row: ranking
     *                by duration means a hot lock of many short waits never reaches
     *                {@code LONGEST WAITS}, and a row without a stack is a name a reader
     *                cannot act on
     */
    public record LockStats(Wait.LockKey lock, long totalNanos, int count, long maxNanos,
                            Set<ThreadRef> waiters, Set<ThreadRef> owners, Wait longest) {
    }

    /**
     * One thread's waits from one place, over several addresses the collector probably moved
     * one object to: see {@link Perch#moved}. Its waits are in {@link #waits()} like any
     * other; this is the label on them.
     *
     * @param locks       the addresses, in the order their waits were reported
     * @param stack       the stack of its longest wait, which names the place
     * @param chanceLog10 the odds, as a power of ten, that chance put a GC pause in every
     *                    change of address
     * @param totalNanos  its reported waits, in the window
     * @param count       how many those were
     */
    public record MovedLock(ThreadRef waiter, String lockClass, List<Wait.LockKey> locks, Stack stack,
                            double chanceLog10, long totalNanos, int count) {
    }

    /** Totals for one waiting thread. */
    public record ThreadStats(ThreadRef thread, long totalNanos, int count, long maxNanos) {
    }

    /**
     * One lock site: the locks whose longest wait prints the same stack, and their summed
     * evidence. Fifteen queues of the same kind are one site with fifteen instances, not
     * fifteen rows a reader has to recognise as one and add up.
     *
     * @param longest    the longest wait at this site, whose stack stands for it
     * @param locks      the lock instances folded into the row, in rank order
     * @param totalNanos the wait over every one of them
     * @param count      how many waits that was
     * @param maxNanos   the longest single wait
     */
    public record SiteStats(Wait longest, List<Wait.LockKey> locks, long totalNanos, int count, long maxNanos,
                            Set<ThreadRef> waiters, Set<ThreadRef> owners) {

        public Wait.Kind kind() {
            return longest.lock().kind();
        }
    }

    /**
     * The locks whose longest wait prints the same stack. A server with one mailbox per worker
     * has as many locks as workers and a single stack between them: printed once per lock it
     * was 66 lines of a 177-line report, and the locks are the information the repetition hid.
     *
     * @param locks   the locks this stack stands for, in the order they were ranked
     * @param longest the longest wait across them, whose stack is the one to print
     */
    public record StackGroup(List<Wait.LockKey> locks, Wait longest) {
    }

    /**
     * A wait whose owner was itself waiting: {@code links.get(0)} is the outermost wait,
     * {@code links.get(1)} is the owner's own overlapping wait, and so on.
     */
    public record Convoy(List<Wait> links) {
        public Wait head() {
            return links.getFirst();
        }

        public int depth() {
            return links.size();
        }
    }

    /** How deep a lock site's stack is compared and printed, by every renderer: see {@link #lockSites(int)}. */
    public static final int SITE_FRAMES = 6;

    private static final Comparator<Wait> BY_INTERVAL = Comparator.comparing(Wait::interval);
    /** How the holder walk reads a wait: a lock is its class and address. */
    private static final Holders.Access<Wait> WAITS = new Holders.Access<>() {
        @Override
        public Interval interval(final Wait wait) {
            return wait.interval();
        }

        @Override
        public ThreadRef owner(final Wait wait) {
            return wait.owner();
        }

        @Override
        public Object lock(final Wait wait) {
            return wait.lock();
        }

        @Override
        public Wait withHolder(final Wait wait, final ThreadRef holder, final List<ThreadRef> via) {
            return new Wait(wait.interval(), wait.waiter(), wait.lock(), holder, wait.stack(), via);
        }
    };

    private final RecordingInfo info;
    /** The reported waits, after the filters, in start order. */
    private final List<Wait> waits;
    /** Reported parks that were workers waiting for their own queue, in start order. */
    private final List<Wait> workWaits;
    /** How many waits the recording holds before the filters. */
    private final int unfilteredCount;
    /** How many waits inside the window the filters (--thread, --min, --lock) left out. */
    private final int filteredOutCount;
    /** How many reported waits were cut down to the recording's span. */
    private final int clippedCount;
    /**
     * How many of the locks under {@link #workWaits} were set aside by their shape alone: no
     * listed park on them was recognised by a frame in the idle list.
     */
    private final int perchCount;
    /** The reported waits the collector probably moved from under one thread, most waited first. */
    private final List<MovedLock> moved;
    /** The part of {@link #totalNanos()} that is theirs. */
    private final long movedNanos;
    /**
     * Every lock ranked, and every wait longest first: each is asked for by more than one
     * section of a report, and computed once. On a loaded node's 13 000 waits the second
     * computation of each was a fifth of the time the text report took.
     */
    private List<LockStats> ranked;
    private List<Wait> byDuration;
    /** Every thread's waits, filters or not, so that a convoy can be followed into any thread. */
    private final ObjObjHashMap<ThreadRef, Waits> byWaiter = new ObjObjHashMap<>(256);

    /** One thread's waits in start order, and the longest of them: bounds the window a lookup by start scans. */
    private static final class Waits {
        final ObjList<Wait> list = new ObjList<>(8);
        long longest;

        void add(final Wait w) {
            list.add(w);
            longest = Math.max(longest, w.duration());
        }
    }

    public ContentionReport(final RecordingInfo info, final List<Wait> waits) {
        this(info, waits, 0, _ -> true);
    }

    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos, final Predicate<String> waiterFilter) {
        this(info, waits, minNanos, waiterFilter, IdleMatcher.forWorkWaits());
    }

    /**
     * @param waits        every wait in the recording; holders are resolved across all of them
     * @param minNanos     waits shorter than this are left out of the report
     * @param waiterFilter only waits by threads whose name passes are reported
     * @param workWaits    which parks are a worker waiting for its own queue rather than
     *                     contention; those are reported apart, because on a server they
     *                     outnumber and outrank every real lock
     */
    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos,
                            final Predicate<String> waiterFilter, final IdleMatcher workWaits) {
        this(info, waits, minNanos, waiterFilter, workWaits, _ -> true);
    }

    /**
     * @param lockFilter only waits for locks that pass are reported; a lock is named by its
     *                   class, its address, or both, and the filter runs after holder
     *                   resolution like the others
     */
    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos,
                            final Predicate<String> waiterFilter, final IdleMatcher workWaits,
                            final Predicate<Wait.LockKey> lockFilter) {
        this(info, waits, minNanos, waiterFilter, workWaits, lockFilter, new LongList(0));
    }

    /**
     * @param gcPauses the recording's collection pauses as flat (start, end) pairs, ascending:
     *                 when a lock could have moved to a new address (see {@link Perch#moved})
     */
    public ContentionReport(final RecordingInfo info, final List<Wait> waits, final long minNanos,
                            final Predicate<String> waiterFilter, final IdleMatcher workWaits,
                            final Predicate<Wait.LockKey> lockFilter, final LongList gcPauses) {
        this.info = info;
        final ObjList<Wait> sorted = new ObjList<>(waits.size());
        for (int i = 0, n = waits.size(); i < n; i++) {
            sorted.add(waits.get(i));
        }
        sorted.sort(BY_INTERVAL);
        // Only monitor enters name a previous owner; a park is never a link in the walk.
        final Holders<Wait> holders = new Holders<>(WAITS);
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            if (w.kind() == Wait.Kind.MONITOR_ENTER) {
                holders.add(w.waiter(), w);
            }
        }
        holders.seal();
        // The filter runs once per thread, not once per wait (G-2.2).
        final ThreadFilter waiterVerdicts = new ThreadFilter(waiterFilter);
        final ObjList<Wait> reported = new ObjList<>(sorted.size());
        final ObjList<Wait> idling = new ObjList<>();
        final Interval window = info.span();

        // First pass: resolve the holders and clip, and weigh each lock while every wait is
        // still in hand. Holders are resolved on the true intervals; everything counted is
        // the part inside the window, so the totals and the shares cannot exceed it. Clipping
        // keeps the order: both ends move by a monotone function of themselves.
        final ObjList<Wait> insides = new ObjList<>(sorted.size());
        final Map<Wait.LockKey, Perch.Shape> shapes = new LinkedHashMap<>();
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            final Wait inside = clip(holders.resolve(w.waiter(), w), window);
            insides.add(inside);
            if (inside.duration() > 0) {
                shapes.computeIfAbsent(inside.lock(), _ -> new Perch.Shape())
                        .add(inside.waiter(), inside.duration(), inside.stack(), inside.owner() != null);
            }
        }
        // The shape verdict is reached before any filter narrows the report, so that --min or
        // --thread cannot turn a perch into contention by hiding the waits that prove it is not.
        final Set<Wait.LockKey> perches = new HashSet<>();
        // Stacks are interned, and a server has thousands of locks and a handful of loops:
        // each stack is rendered once, not once per lock.
        final Map<Stack, String> loops = new IdentityHashMap<>();
        if (!workWaits.matchesNothing()) {
            final Set<String> perchLoops = new HashSet<>();
            shapes.forEach((lock, s) -> {
                if (lock.kind() == Wait.Kind.PARK && s.matches(window.duration())) {
                    perches.add(lock);
                    // A perch whose waits carry no stack names no loop: every other stackless
                    // lock would print the same, and none of them is evidence about this one.
                    final String loop = loops.computeIfAbsent(s.stack(), Perch::loop);
                    if (loop != null) {
                        perchLoops.add(loop);
                    }
                }
            });
            // What shape finds is a lock; what it identifies is the loop above it. One worker
            // out of thirteen that was busy for two thirds of the recording parks on its own
            // mailbox exactly like the other twelve, and a rule that let a threshold decide
            // between them would put that one lock, alone, at the top of the contention it is
            // not part of. The measurement names the frame the idle list was missing; the
            // frame then answers for every lock that waits there. Loops are compared as they
            // print, so two stacks a reader cannot tell apart are one loop.
            shapes.forEach((lock, s) -> {
                if (lock.kind() == Wait.Kind.PARK && !perches.contains(lock)) {
                    final String loop = loops.computeIfAbsent(s.stack(), Perch::loop);
                    if (loop != null && perchLoops.contains(loop)) {
                        perches.add(lock);
                    }
                }
            });
        }

        // Labelled, never set aside: the evidence that the pieces are one object is timing, and
        // timing can be caused (Perch#moved), so their waits stay contention.
        final Map<Wait.LockKey, Perch.Move> movedOf = moved(sorted, insides, shapes, perches, loops, gcPauses,
                window.duration());
        final Map<Perch.Move, MovedAcc> movedAcc = new LinkedHashMap<>();
        long movedTotal = 0;

        int clipped = 0;
        int filteredOut = 0;
        final Set<Wait.LockKey> byShape = new HashSet<>();
        final Set<Wait.LockKey> byName = new HashSet<>();
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            final Wait inside = insides.getQuick(i);
            final boolean named = w.kind() == Wait.Kind.PARK && workWaits.isIdle(w.stack());
            final boolean idle = named || (w.kind() == Wait.Kind.PARK && perches.contains(w.lock()));
            // A worker parked on its own queue holds nothing and blocks nobody, so it is not a
            // convoy link either; the rest stay reachable so a convoy can be followed into any
            // thread and any lock, whatever the filters let through. The report lists the
            // filtered ones.
            if (!idle) {
                waitsOf(byWaiter, w.waiter()).add(inside);
            }
            if (inside.duration() <= 0) {
                continue;
            }
            if (!lockFilter.test(w.lock()) || inside.duration() < minNanos
                    || !waiterVerdicts.passes(w.waiter())) {
                filteredOut++;
                continue;
            }
            if (idle) {
                idling.add(inside);
                (named ? byName : byShape).add(w.lock());
            } else {
                reported.add(inside);
                if (inside.duration() < w.duration()) {
                    clipped++;
                }
                final Perch.Move move = movedOf.get(w.lock());
                if (move != null) {
                    movedAcc.computeIfAbsent(move, _ -> new MovedAcc()).add(inside);
                    movedTotal += inside.duration();
                }
            }
        }
        byShape.removeAll(byName);
        this.waits = reported.toList();
        this.workWaits = idling.toList();
        this.unfilteredCount = sorted.size();
        this.filteredOutCount = filteredOut;
        this.clippedCount = clipped;
        this.perchCount = byShape.size();
        final List<MovedLock> movedLocks = new ArrayList<>(movedAcc.size());
        movedAcc.forEach((move, acc) -> movedLocks.add(new MovedLock(move.place().waiter(), acc.lockClass,
                List.copyOf(acc.locks), move.stack(), move.chanceLog10(), acc.total, acc.count)));
        movedLocks.sort(Comparator.comparingLong(MovedLock::totalNanos).reversed());
        this.moved = List.copyOf(movedLocks);
        this.movedNanos = movedTotal;
    }

    /** One {@link MovedLock} being summed from the reported waits. */
    private static final class MovedAcc {
        final Set<Wait.LockKey> locks = new LinkedHashSet<>();
        String lockClass;
        long total;
        int count;

        void add(final Wait w) {
            if (lockClass == null) {
                lockClass = w.lock().className();
            }
            locks.add(w.lock());
            total += w.duration();
            count++;
        }
    }


    /**
     * The locks the collector probably moved from under one thread: the park locks only one
     * thread waited on and nobody was seen holding, not already its perch, gathered by
     * {@link Perch.Place} (that thread and the loop it waits in), whose total has a perch's
     * shape and whose changes of address {@link Perch#moved} explains. Each piece maps to the
     * {@link Perch.Move} it belongs to.
     */
    private static Map<Wait.LockKey, Perch.Move> moved(final ObjList<Wait> sorted, final ObjList<Wait> insides,
                                                       final Map<Wait.LockKey, Perch.Shape> shapes,
                                                       final Set<Wait.LockKey> perches, final Map<Stack, String> loops,
                                                       final LongList gcPauses, final long windowNanos) {
        if (gcPauses.isEmpty()) {
            return Map.of();
        }
        final Map<Perch.Place, Perch.Shape> folded = new HashMap<>();
        final Map<Wait.LockKey, Perch.Place> placeOf = new HashMap<>();
        shapes.forEach((lock, s) -> {
            if (lock.kind() == Wait.Kind.PARK && !perches.contains(lock) && s.ownThread()) {
                final String loop = loops.computeIfAbsent(s.stack(), Perch::loop);
                if (loop != null) {
                    final Perch.Place place = new Perch.Place(s.waiter(), loop);
                    folded.computeIfAbsent(place, _ -> new Perch.Shape()).add(s);
                    placeOf.put(lock, place);
                }
            }
        });
        folded.values().removeIf(s -> !s.matches(windowNanos));
        if (folded.isEmpty()) {
            return Map.of();
        }
        // The waits at each candidate place in time order: one thread's, so start order is end order.
        final Map<Perch.Place, LongList[]> turns = new HashMap<>();
        for (int i = 0, n = sorted.size(); i < n; i++) {
            final Wait w = sorted.getQuick(i);
            final Perch.Place place = insides.getQuick(i).duration() > 0 ? placeOf.get(w.lock()) : null;
            if (place != null && folded.containsKey(place)) {
                final LongList[] t = turns.computeIfAbsent(place, _ -> new LongList[]{new LongList(), new LongList()});
                t[0].add(w.lock().address());
                t[1].add(w.interval().end());
            }
        }
        final Map<Wait.LockKey, Perch.Move> out = new HashMap<>();
        turns.forEach((place, t) -> {
            final double chance = Perch.chanceLog10(t[0], t[1], gcPauses);
            if (Perch.moved(t[0], t[1], gcPauses)) {
                final List<Wait.LockKey> pieces = new ArrayList<>();
                placeOf.forEach((lock, p) -> {
                    if (p.equals(place)) {
                        pieces.add(lock);
                    }
                });
                final Perch.Move move = new Perch.Move(place, folded.get(place).stack(), pieces.size(), chance);
                for (final Wait.LockKey lock : pieces) {
                    out.put(lock, move);
                }
            }
        });
        return out;
    }

    /** A wait counted only for the part inside the window; the same object when it is wholly inside. */
    private static Wait clip(final Wait w, final Interval window) {
        final Interval inside = w.interval().clampTo(window);
        return inside == w.interval() ? w : new Wait(inside, w.waiter(), w.lock(), w.owner(), w.stack(), w.via());
    }

    private static Waits waitsOf(final ObjObjHashMap<ThreadRef, Waits> map, final ThreadRef thread) {
        final int index = map.keyIndex(thread);
        return index < 0 ? map.valueAtQuick(index) : map.putAt(index, thread, new Waits());
    }

    /**
     * Whether the filters ({@code --thread}, {@code --min}, {@code --lock}) left out any wait
     * inside the window. Waits set apart as waiting for work are not filtered out: they are
     * reported, in their own section.
     */
    public boolean isFiltered() {
        return filteredOutCount > 0;
    }

    public int unfilteredCount() {
        return unfilteredCount;
    }

    /**
     * Why nothing is listed as contention, in the words both renderers print; {@code null}
     * when something is. An idle process reaches here with no filter set, and is told it is
     * idle; a filtered one is told that the answer is about what matched.
     */
    public String noContention() {
        if (!waits.isEmpty()) {
            return null;
        }
        if (!workWaits.isEmpty()) {
            return isFiltered()
                    ? "No contention: every wait that matches the filters (--thread, --min, --lock) was a worker "
                            + "waiting for work, not a thread held up by another; " + unfilteredCount
                            + " in the recording."
                    : "No contention: every wait was a worker waiting for work, not a thread held up by another.";
        }
        return isFiltered()
                ? "No contended monitor enters or parks match the filters (--thread, --min, --lock); "
                        + unfilteredCount + " in the recording."
                : "No contended monitor enters or parks in the recording (at or above the thresholds above).";
    }

    /**
     * How many reported waits began before the recording's span or were still running at
     * its end, and are therefore counted only for the part inside it. A {@code jfrq-live}
     * delta window slices waits at both ends by construction, so this is normal rather
     * than a defect in the file; it is reported because it is why a wait's total here can
     * be smaller than the same wait elsewhere.
     */
    public int clippedCount() {
        return clippedCount;
    }

    public RecordingInfo info() {
        return info;
    }

    /** The reported waits (those passing the filters), in start order. */
    public List<Wait> waits() {
        return waits;
    }

    /**
     * Parks that were a worker waiting for its own queue: not contention, and on a server
     * they are most of the blocking time in the file. Reported apart so that ranking by
     * duration does not bury the one lock that matters under a dozen idle pools.
     */
    public List<Wait> workWaits() {
        return workWaits;
    }

    public long workWaitNanos() {
        return sum(workWaits);
    }

    /** {@link #locks(int)} over the waiting-for-work parks instead of the contended waits. */
    public List<LockStats> workWaitLocks(final int top) {
        return locksOf(workWaits, top);
    }

    /**
     * How many of the locks under {@link #workWaits()} were recognised by their shape rather
     * than by a frame in the idle list: one thread, no holder, most of the recording parked
     * there, or the same loop as a lock like that. Counted over the listed parks only, so it
     * is a count of rows the reader can see, after the filters; a lock the idle list also
     * named on any of them counts as named. The report says so, because a reader who does
     * not know the rule cannot tell why a lock they can see in the file is missing from the
     * contention above.
     */
    public int perchCount() {
        return perchCount;
    }

    /** The locks the collector probably moved from under one thread, most waited first: a label, not a filter. */
    public List<MovedLock> moved() {
        return moved;
    }

    /** How much of {@link #totalNanos()} is on {@link #moved()}. */
    public long movedNanos() {
        return movedNanos;
    }

    /** How many distinct threads were waiting for work. */
    public int workWaitThreads() {
        final Set<ThreadRef> threads = new LinkedHashSet<>();
        for (final Wait w : workWaits) {
            threads.add(w.waiter());
        }
        return threads.size();
    }

    public boolean isEmpty() {
        return waits.isEmpty();
    }

    public long totalNanos() {
        return sum(waits);
    }

    private static long sum(final List<Wait> of) {
        long total = 0;
        for (final Wait w : of) {
            total += w.duration();
        }
        return total;
    }

    /** Locks ranked by total time threads spent waiting for them. */
    public List<LockStats> locks(final int top) {
        return limit(rankedLocks(), top);
    }

    private List<LockStats> rankedLocks() {
        if (ranked == null) {
            ranked = locksOf(waits, Integer.MAX_VALUE);
        }
        return ranked;
    }

    /**
     * The ranked locks of {@link #locks(int)} grouped by the stack that stands for them, in
     * rank order.
     *
     * @param frames how many frames the caller will print: two stacks that differ only below
     *               that print the same, so they are one group. Keyed on the rendering rather
     *               than on the frames, because an elided stack also shows the first frame
     *               of the application's own code wherever it lies, and two stacks that differ
     *               there are two a reader can tell apart
     */
    public List<StackGroup> lockStacks(final int top, final int frames) {
        final List<StackGroup> out = new ArrayList<>();
        byStack(locks(top), frames).forEach((_, group) -> {
            final List<Wait.LockKey> locks = new ArrayList<>(group.size());
            Wait longest = null;
            for (final LockStats l : group) {
                locks.add(l.lock());
                if (longest == null || l.longest().duration() > longest.duration()) {
                    longest = l.longest();
                }
            }
            out.add(new StackGroup(List.copyOf(locks), longest));
        });
        return List.copyOf(out);
    }

    /**
     * Lock sites ranked by total wait: every lock whose longest wait prints the same stack
     * summed into one row, over all locks rather than over the top {@code top} of them, so a
     * site spread across forty instances outranks one big lock instead of being lost below it.
     *
     * <p>Fifteen queues of the same kind are fifteen rows in {@link #locks(int)} and one line
     * of work to the reader; the addresses stay reachable through a {@code --lock} query.
     *
     * <p>A site is the stack as it prints to {@link #SITE_FRAMES} frames, whichever renderer
     * asks: the text and the HTML report must add up the same rows to the same totals, so
     * both group, and print a site's stack, at that one depth.
     */
    public List<SiteStats> lockSites(final int top) {
        final List<SiteStats> sites = new ArrayList<>();
        byStack(rankedLocks(), SITE_FRAMES).forEach((_, group) -> {
            final List<Wait.LockKey> locks = new ArrayList<>(group.size());
            final Set<ThreadRef> waiters = new LinkedHashSet<>();
            final Set<ThreadRef> owners = new LinkedHashSet<>();
            long total = 0;
            long max = 0;
            int count = 0;
            Wait longest = null;
            for (final LockStats l : group) {
                locks.add(l.lock());
                waiters.addAll(l.waiters());
                owners.addAll(l.owners());
                total += l.totalNanos();
                max = Math.max(max, l.maxNanos());
                count += l.count();
                if (longest == null || l.longest().duration() > longest.duration()) {
                    longest = l.longest();
                }
            }
            // Insertion order (rank, then first wait) so the names a row prints are the same on every run.
            sites.add(new SiteStats(longest, List.copyOf(locks), total, count, max,
                    Collections.unmodifiableSet(waiters), Collections.unmodifiableSet(owners)));
        });
        sites.sort(Comparator.comparingLong(SiteStats::totalNanos).reversed());
        return limit(sites, top);
    }

    /**
     * The given locks grouped by the rendering of their longest wait's stack, keeping the
     * order they came in. A lock whose longest wait carries no stack renders as the empty
     * string and groups with the others that carry none, which is all a reader can tell
     * about them too.
     */
    private static Map<String, List<LockStats>> byStack(final List<LockStats> locks, final int frames) {
        final Map<String, List<LockStats>> groups = new LinkedHashMap<>();
        for (final LockStats l : locks) {
            if (l.longest() != null) {
                groups.computeIfAbsent(l.longest().stack().pretty("", frames), _ -> new ArrayList<>()).add(l);
            }
        }
        return groups;
    }

    private static List<LockStats> locksOf(final List<Wait> from, final int top) {
        // One lookup per wait: a LockKey is a record, and its hash is not free.
        final Map<Wait.LockKey, LockTotals> totals = new LinkedHashMap<>();
        for (final Wait w : from) {
            totals.computeIfAbsent(w.lock(), _ -> new LockTotals()).add(w);
        }
        final List<LockStats> stats = new ArrayList<>(totals.size());
        totals.forEach((lock, t) -> stats.add(new LockStats(lock, t.total, t.count, t.max, t.waiters,
                t.owners == null ? Set.of() : t.owners, t.longest)));
        stats.sort(Comparator.comparingLong(LockStats::totalNanos).reversed());
        return limit(stats, top);
    }

    /** One lock's totals while they are summed. */
    private static final class LockTotals {
        long total;
        int count;
        long max;
        final Set<ThreadRef> waiters = new LinkedHashSet<>();
        Set<ThreadRef> owners;
        Wait longest;

        void add(final Wait w) {
            final long d = w.duration();
            total += d;
            count++;
            max = Math.max(max, d);
            waiters.add(w.waiter());
            if (w.owner() != null) {
                if (owners == null) {
                    owners = new LinkedHashSet<>();
                }
                owners.add(w.owner());
            }
            if (longest == null || d > longest.duration()) {
                longest = w;
            }
        }
    }

    /** Threads ranked by total time blocked. */
    public List<ThreadStats> waiters(final int top) {
        final Map<ThreadRef, long[]> totals = new LinkedHashMap<>();
        for (final Wait w : waits) {
            final long[] t = totals.computeIfAbsent(w.waiter(), _ -> new long[3]);
            t[0] += w.duration();
            t[1]++;
            t[2] = Math.max(t[2], w.duration());
        }
        final List<ThreadStats> stats = new ArrayList<>();
        totals.forEach((thread, t) -> stats.add(new ThreadStats(thread, t[0], (int) t[1], t[2])));
        stats.sort(Comparator.comparingLong(ThreadStats::totalNanos).reversed());
        return limit(stats, top);
    }

    /** The longest individual waits. */
    public List<Wait> longest(final int top) {
        return limit(byDuration(), top);
    }

    private List<Wait> byDuration() {
        if (byDuration == null) {
            final List<Wait> sorted = new ArrayList<>(waits);
            sorted.sort((a, b) -> Long.compare(b.duration(), a.duration()));
            byDuration = Collections.unmodifiableList(sorted);
        }
        return byDuration;
    }

    /**
     * Finds convoys: for each monitor wait whose owner is known, follows the owner into
     * its own wait for a <em>different</em> lock overlapping the same time, up to
     * {@code maxDepth} links (co-waiters for the same lock are already folded into
     * {@link Wait#via()}). Only chains of two or more links are returned, longest head
     * wait first.
     */
    public List<Convoy> convoys(final int maxDepth, final int top) {
        final List<Convoy> found = new ArrayList<>();
        // Heads in descending duration: once `top` convoys exist, no later head can outrank them.
        for (final Wait head : byDuration()) {
            if (found.size() >= top) {
                break;
            }
            final List<Wait> links = new ArrayList<>();
            links.add(head);
            final Set<ThreadRef> seen = new LinkedHashSet<>();
            seen.add(head.waiter());
            Wait current = head;
            while (links.size() < maxDepth && current.owner() != null && seen.add(current.owner())) {
                final Wait next = longestOverlapping(current.owner(), current.interval(), current.lock());
                if (next == null) {
                    break;
                }
                links.add(next);
                current = next;
            }
            if (links.size() >= 2) {
                found.add(new Convoy(List.copyOf(links)));
            }
        }
        return List.copyOf(found);
    }

    private Wait longestOverlapping(final ThreadRef thread, final Interval during, final Wait.LockKey notThisLock) {
        Wait best = null;
        long bestOverlap = 0;
        final Waits theirs = byWaiter.get(thread);
        if (theirs == null) {
            return null;
        }
        final ObjList<Wait> list = theirs.list;
        final int from = Sorts.lowerBound(list, Wait::start, during.start() - theirs.longest);
        for (int i = from, n = list.size(); i < n; i++) {
            final Wait w = list.getQuick(i);
            if (w.start() >= during.end()) {
                break;
            }
            if (w.lock().equals(notThisLock)) {
                continue;
            }
            final long overlap = w.interval().overlap(during);
            if (overlap > bestOverlap) {
                best = w;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    private static <T> List<T> limit(final List<T> list, final int top) {
        return list.size() > top ? List.copyOf(list.subList(0, top)) : List.copyOf(list);
    }
}
