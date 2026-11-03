// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.IdentityHashMap;
import java.util.Map;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.jfr.Absent;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Sorts;

/**
 * What every lock parked on did across the whole recording, gathered for {@link Perch}.
 *
 * <p>The shape rule weighs a lock by every thread that ever waited on it, so it has to see
 * every thread's parks, not only the watched ones: a lock that two threads share is not
 * either thread's perch, and which threads {@code --thread} selected must not change that.
 * {@code locks} decides the same rule before any filter, which is why the two commands agree.
 *
 * <p>A park with no blocker object never counts: every {@code LockSupport.parkNanos} in the
 * JVM would share the one "lock", and a thread's own backoff or pacing loop would be filed
 * away as its idle point.
 *
 * <p>Kept flat (G-1.8): a lock is a number, and a park is three longs (the lock, its two
 * ends), its thread and its stack, appended per event. A server with one lock per request
 * has as many locks as parks, so nothing is allocated per lock until {@link #perchStacks}.
 * There a few arrays span every lock, and a {@link Perch.Shape} is made only for a lock that
 * parked long enough to be a perch, or for a piece of one a collection moved, when its thread
 * alone parked on such pieces for more than half the span.
 */
final class ParkShapes {

    private static final int SLOT = 3;
    private static final int LOCK = 0;
    private static final int START = 1;
    private static final int END = 2;

    private final ObjLongHashMap<String> lockIds = new ObjLongHashMap<>(64);
    private final LongList parks = new LongList(64 * SLOT);
    private final ObjList<ThreadRef> waiters = new ObjList<>(64);
    private final ObjList<Stack> stacks = new ObjList<>(64);

    /**
     * @param lock  the lock's name as the collector details it; {@link Absent#NO_BLOCKER} is ignored
     * @param stack the park's stack, which is what a perch identifies
     */
    void add(final ThreadRef waiter, final String lock, final long start, final long end, final Stack stack) {
        if (Absent.NO_BLOCKER.equals(lock)) {
            return;
        }
        final int index = lockIds.keyIndex(lock);
        final long id;
        if (index < 0) {
            id = lockIds.valueAtQuick(index);
        } else {
            id = lockIds.size();
            lockIds.putAt(index, lock, id);
        }
        parks.add(id);
        parks.add(start);
        parks.add(end);
        waiters.add(waiter);
        stacks.add(stack);
    }

    /**
     * The stack of every lock {@link Perch} recognises in {@code span}: the loop that waits
     * there, which then answers for every other park from the same place. Each park counts
     * only for the part inside the span, as it does in {@code locks}.
     */
    ObjList<Stack> perchStacks(final Interval span) {
        return perchStacks(span, new LongList(0), new ObjList<>());
    }

    /**
     * {@link #perchStacks(Interval)}, and in {@code moves} the locks the collector probably
     * moved from under one thread (see {@link Perch#moved}): a label for the stalls on them,
     * never a perch, since timing is the only evidence they are one object.
     *
     * @param gcPauses collection pauses as flat (start, end) pairs, ascending
     */
    ObjList<Stack> perchStacks(final Interval span, final LongList gcPauses, final ObjList<Perch.Move> moves) {
        final int locks = lockIds.size();
        final int n = waiters.size();
        // First the total per lock: a lock whose total fails the rule even with one waiter and
        // any number of parks is no perch, whoever they were, and is not looked at again.
        final long[] inside = new long[locks];
        for (int i = 0; i < n; i++) {
            inside[(int) parks.getQuick(i * SLOT + LOCK)] += inside(i, span);
        }
        final Perch.Shape[] shapes = new Perch.Shape[locks];
        for (int i = 0; i < n; i++) {
            final int lock = (int) parks.getQuick(i * SLOT + LOCK);
            final long nanos = inside(i, span);
            if (nanos > 0 && Perch.matches(inside[lock], Integer.MAX_VALUE, 1, false, span.duration())) {
                if (shapes[lock] == null) {
                    shapes[lock] = new Perch.Shape();
                }
                // A park has no holder to record, so the lock is never owned.
                shapes[lock].add(waiters.getQuick(i), nanos, stacks.getQuick(i), false);
            }
        }
        final ObjList<Stack> found = new ObjList<>();
        final boolean[] perch = new boolean[locks];
        for (int lock = 0; lock < locks; lock++) {
            final Perch.Shape shape = shapes[lock];
            if (shape != null && shape.matches(span.duration()) && !shape.stack().isEmpty()) {
                found.add(shape.stack());
                perch[lock] = true;
            }
        }
        if (gcPauses.notEmpty()) {
            moved(span, gcPauses, inside, perch, moves);
        }
        return found;
    }

    /**
     * The label {@code locks} puts on the same waits: the locks only one thread parked on,
     * gathered by {@link Perch.Place} (that thread and the loop it parks in), whose total has
     * a perch's shape and whose changes of address {@link Perch#moved} explains. One thread is
     * parked at most once at a time, so only a thread whose such locks add up to more than
     * half the span can hold one, and only its locks are looked at.
     */
    private void moved(final Interval span, final LongList gcPauses, final long[] inside,
                       final boolean[] perch, final ObjList<Perch.Move> moves) {
        final int locks = inside.length;
        final int n = waiters.size();
        final ThreadRef[] waiterOf = new ThreadRef[locks];
        final boolean[] several = new boolean[locks];
        for (int i = 0; i < n; i++) {
            final int lock = (int) parks.getQuick(i * SLOT + LOCK);
            final ThreadRef waiter = waiters.getQuick(i);
            if (waiterOf[lock] == null) {
                waiterOf[lock] = waiter;
            } else if (!waiterOf[lock].equals(waiter)) {
                several[lock] = true;
            }
        }
        final ObjLongHashMap<ThreadRef> perThread = new ObjLongHashMap<>(64);
        for (int lock = 0; lock < locks; lock++) {
            if (!perch[lock] && !several[lock] && inside[lock] > 0) {
                perThread.increment(waiterOf[lock], inside[lock]);
            }
        }
        final Perch.Shape[] pieces = new Perch.Shape[locks];
        for (int i = 0; i < n; i++) {
            final int lock = (int) parks.getQuick(i * SLOT + LOCK);
            final long nanos = inside(i, span);
            if (nanos > 0 && !perch[lock] && !several[lock]
                    && Perch.matches(perThread.get(waiterOf[lock]), Integer.MAX_VALUE, 1, false, span.duration())) {
                if (pieces[lock] == null) {
                    pieces[lock] = new Perch.Shape();
                }
                pieces[lock].add(waiters.getQuick(i), nanos, stacks.getQuick(i), false);
            }
        }
        final Map<Stack, String> rendered = new IdentityHashMap<>();
        final Perch.Place[] placeOf = new Perch.Place[locks];
        final ObjLongHashMap<Perch.Place> foldOf = new ObjLongHashMap<>(16);
        final ObjList<Perch.Place> places = new ObjList<>();
        final ObjList<Perch.Shape> folds = new ObjList<>();
        for (int lock = 0; lock < locks; lock++) {
            if (pieces[lock] != null) {
                final String loop = rendered.computeIfAbsent(pieces[lock].stack(), Perch::loop);
                if (loop != null) {
                    final Perch.Place place = new Perch.Place(waiterOf[lock], loop);
                    placeOf[lock] = place;
                    final int index = foldOf.keyIndex(place);
                    final int f;
                    if (index < 0) {
                        f = (int) foldOf.valueAtQuick(index);
                    } else {
                        f = places.size();
                        foldOf.putAt(index, place, f);
                        places.add(place);
                        folds.add(new Perch.Shape());
                    }
                    folds.getQuick(f).add(pieces[lock]);
                }
            }
        }
        for (int f = 0, m = places.size(); f < m; f++) {
            final Perch.Shape fold = folds.getQuick(f);
            if (!fold.matches(span.duration())) {
                continue;
            }
            final Perch.Place place = places.getQuick(f);
            int addresses = 0;
            for (int lock = 0; lock < locks; lock++) {
                if (place.equals(placeOf[lock])) {
                    addresses++;
                }
            }
            final LongList ids = new LongList();
            final LongList ends = new LongList();
            for (int i = 0; i < n; i++) {
                final int lock = (int) parks.getQuick(i * SLOT + LOCK);
                if (place.equals(placeOf[lock]) && inside(i, span) > 0) {
                    ids.add(lock);
                    ends.add(parks.getQuick(i * SLOT + END));
                }
            }
            final int[] order = Sorts.order(ends);
            final LongList orderedIds = new LongList(order.length);
            final LongList orderedEnds = new LongList(order.length);
            for (final int i : order) {
                orderedIds.add(ids.getQuick(i));
                orderedEnds.add(ends.getQuick(i));
            }
            if (Perch.moved(orderedIds, orderedEnds, gcPauses)) {
                moves.add(new Perch.Move(place, fold.stack(), addresses,
                        Perch.chanceLog10(orderedIds, orderedEnds, gcPauses)));
            }
        }
    }

    private long inside(final int park, final Interval span) {
        final long start = Math.max(parks.getQuick(park * SLOT + START), span.start());
        final long end = Math.min(parks.getQuick(park * SLOT + END), span.end());
        return Math.max(0, end - start);
    }
}
