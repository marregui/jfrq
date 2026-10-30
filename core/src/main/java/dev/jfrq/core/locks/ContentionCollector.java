// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.locks;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.LongObjHashMap;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.IdleMatcher;
import dev.jfrq.core.util.Sorts;

/**
 * Collects contended monitor enters and parks into a {@link ContentionReport}.
 *
 * <p>Two things are deliberately not collected. {@code jdk.JavaMonitorWait}
 * ({@code Object.wait()}): a thread in {@code wait()} chose to wait for a notification, it
 * is not contending for the lock. And parks without a blocker object: those are
 * {@code LockSupport.parkNanos} sleeps and pacing loops, not locks. A park with a blocker
 * is kept, though a blocker of {@code AbstractQueuedSynchronizer$ConditionObject} usually
 * means a worker waiting for work on a queue rather than a lock fight; the class is shown
 * so the reader can tell.
 */
public final class ContentionCollector implements JfrReader.Sink {

    public static final String MONITOR_ENTER = EventKinds.nameOf(EventKinds.JAVA_MONITOR_ENTER);
    public static final String THREAD_PARK = EventKinds.nameOf(EventKinds.THREAD_PARK);
    /** Read for when a lock could have moved to a new address, not as a wait. */
    public static final String GC_PHASE_PAUSE = EventKinds.nameOf(EventKinds.GC_PHASE_PAUSE);

    private static final Set<String> TYPES = Set.of(MONITOR_ENTER, THREAD_PARK, GC_PHASE_PAUSE);

    private final long minNanos;
    private final Predicate<String> waiterFilter;
    private final IdleMatcher workWaits;
    private final Predicate<Wait.LockKey> lockFilter;
    private final ObjList<Wait> waits = new ObjList<>(1024);
    /**
     * One {@link Wait.LockKey} per lock, keyed by address: a recording holds a few locks
     * and hundreds of thousands of waits. An address reused by another class (the object
     * moved) replaces the entry; the earlier key stays alive through its waits and still
     * compares by value.
     */
    private final LongObjHashMap<Wait.LockKey> locks = new LongObjHashMap<>(64, Long.MIN_VALUE);
    /** Collection pause starts and ends, in file order until {@link #finish}. */
    private final LongList pauseStarts = new LongList(64);
    private final LongList pauseEnds = new LongList(64);
    private Interner interner = new Interner();
    private ContentionReport report;

    /**
     * @param minNanos     waits shorter than this are left out of the report
     * @param waiterFilter only waits by threads whose name passes are reported
     */
    public ContentionCollector(final long minNanos, final Predicate<String> waiterFilter) {
        this(minNanos, waiterFilter, IdleMatcher.forWorkWaits());
    }

    public ContentionCollector(final long minNanos, final Predicate<String> waiterFilter, final IdleMatcher workWaits) {
        this(minNanos, waiterFilter, workWaits, _ -> true);
    }

    /**
     * @param workWaits  which parks the report separates out as "no work to do"
     * @param lockFilter which locks are reported at all
     */
    public ContentionCollector(final long minNanos, final Predicate<String> waiterFilter, final IdleMatcher workWaits,
                               final Predicate<Wait.LockKey> lockFilter) {
        this.minNanos = minNanos;
        this.waiterFilter = waiterFilter;
        this.workWaits = workWaits;
        this.lockFilter = lockFilter;
    }

    public ContentionCollector() {
        this(0, _ -> true);
    }

    @Override
    public void begin(final Interner interner) {
        this.interner = interner;
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public void accept(@Transient final RecordedEvent e) {
        accept(e, EventKinds.kindOf(e.getEventType().getName()));
    }

    /**
     * Every wait is kept, filtered or not: the holder of a lock is found by walking through
     * the waits of <em>other</em> threads, and a short wait by the intermediary is exactly
     * the one that says who really held it. The filters apply in the report.
     */
    @Override
    public void accept(@Transient final RecordedEvent e, final int kind) {
        if (kind == EventKinds.GC_PHASE_PAUSE) {
            final long start = Events.startNanos(e);
            pauseStarts.add(start);
            pauseEnds.add(Math.max(start, Events.endNanos(e)));
            return;
        }
        final ThreadRef waiter = interner.thread(e);
        if (waiter == null) {
            return;
        }
        final boolean monitor = kind == EventKinds.JAVA_MONITOR_ENTER;
        final String cls = Events.className(e, monitor ? Fields.MONITOR_CLASS : Fields.PARKED_CLASS, interner);
        if (!monitor && cls == null) {
            return;
        }
        final Wait.Kind waitKind = monitor ? Wait.Kind.MONITOR_ENTER : Wait.Kind.PARK;
        final Wait.LockKey lock = lock(cls, Events.longOr(e, Fields.ADDRESS, 0, interner), waitKind);
        final ThreadRef owner = monitor ? Events.thread(e, Fields.PREVIOUS_OWNER, interner) : null;
        waits.add(new Wait(Events.interval(e), waiter, lock, owner, Events.stack(e, interner)));
    }

    private Wait.LockKey lock(final String cls, final long address, final Wait.Kind kind) {
        final int index = locks.keyIndex(address);
        if (index < 0) {
            final Wait.LockKey known = locks.valueAtQuick(index);
            if (known.kind() == kind && Objects.equals(known.className(), cls)) {
                return known;
            }
        }
        final Wait.LockKey created = new Wait.LockKey(cls, address, kind);
        if (index < 0) {
            locks.put(address, created);
        } else {
            locks.putAt(index, address, created);
        }
        return created;
    }

    @Override
    public void finish(final RecordingInfo info) {
        final int[] order = Sorts.order(pauseStarts);
        final LongList pauses = new LongList(2 * order.length);
        for (final int i : order) {
            pauses.add(pauseStarts.getQuick(i));
            pauses.add(pauseEnds.getQuick(i));
        }
        report = new ContentionReport(info, waits.toList(), minNanos, waiterFilter, workWaits, lockFilter, pauses);
    }

    public ContentionReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }
}
