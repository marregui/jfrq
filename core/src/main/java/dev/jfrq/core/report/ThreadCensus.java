// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.health.ThreadCpu;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Sorts;

/**
 * Which threads were alive when the recording began and when it ended, and which started
 * and ended in between: the answer to "is this a leak" that the threads <em>seen in
 * events</em> cannot give. That count moves with the window's activity: a pool that starts
 * a worker per task is 39 threads in one window and 64 in the next while the census shows
 * the same 4 alive at both ends.
 *
 * <p>Alive comes from {@code jdk.ThreadAllocationStatistics}, which the JVM writes at every
 * chunk boundary with one row per live Java thread, all rows of a batch under one
 * timestamp; the earliest batch is the start of the recording and the latest its end.
 * Started and ended come from {@code jdk.ThreadStart} and {@code jdk.ThreadEnd}. The JDK's
 * {@code default} and {@code profile} settings enable all three. A thread parked through
 * the whole window, which no other event names, is in the census all the same. Starts and
 * ends are counted as events, not as threads: the JVM stops and restarts its dynamic
 * compiler threads under one id. Only the starts and ends between the two censuses are
 * counted, so that alive at start, plus started, minus ended, is alive at end: a recording
 * that begins as the JVM boots has threads that start before its first census and are in it.
 * For the same reason a start is not counted for a thread already alive: on JDK 25 the
 * {@code main} thread, in the first census of a recording made from boot, gets a
 * {@code jdk.ThreadStart} afterwards all the same.
 *
 * <p>All three cover Java platform threads only: a virtual thread is in no census, and its
 * start and end are other event types, off in both JDK profiles; the JVM's own threads
 * without a Java identity (GC workers, the VM thread) are in none of them. What they say
 * about such a thread is therefore unknown, not zero: {@link Result#covered} is every thread
 * any of the three named, anywhere in the file, and {@link RecordingSummary#threadFamilies}
 * gives no counts for a family with a thread outside it.
 *
 * <p>A start also says who made the thread: the starting thread ({@code parentThread}) and
 * its stack. A thread that native code attached to the JVM (JNI {@code AttachCurrentThread},
 * an FFM upcall from a thread the JVM did not make) has neither, and that is the only mark the
 * file gives it: a library that attaches for every callback, as RocksDB's Java event listener
 * does, starts thousands of {@code Thread-N} that look like any other thread by name. The
 * {@code java} launcher attaches {@code main} the same way. The starts are kept, with their
 * stacks, for {@link RecordingSummary#starts}.
 *
 * <p>Each Java thread's CPU comes from {@code jdk.ThreadCPULoad} ({@link ThreadCpu}).
 *
 * <p>For {@code info}, which reads every event: the sink asks for everything and keeps
 * the kinds it needs, so adding it does not narrow the pass.
 */
public final class ThreadCensus implements JfrReader.Sink {

    /** Starts and ends, flat until the end (G-1.8): when, which thread, and whether it ended. */
    private final LongList lifeTimes = new LongList(128);
    private final ObjList<ThreadRef> lifeThreads = new ObjList<>(128);
    private final LongList lifeEnds = new LongList(128);
    /** Per start (a zero in {@link #lifeEnds}): the thread that started it, or {@code null}, and its stack. */
    private final ObjList<ThreadRef> lifeParents = new ObjList<>(128);
    private final ObjList<Stack> lifeStacks = new ObjList<>(128);
    /** Per start: 1 when native code attached the thread, 0 when it was started or it cannot be told. */
    private final LongList lifeAttached = new LongList(128);
    private final ThreadCpu cpu = new ThreadCpu();
    /** Whether any start named its parent, or was an attach: only then can the file tell an attach apart. */
    private boolean sawParent;
    /** Census rows, flat until the end: when, and which thread. */
    private final LongList rowTimes = new LongList(256);
    private final ObjList<ThreadRef> rowThreads = new ObjList<>(256);
    private Interner interner = new Interner();
    private Result result;

    /**
     * What the recording says about its threads' lives. A set the recording cannot fill is
     * {@code null}: the event that would say was not recorded.
     *
     * @param aliveAtStart the threads in the earliest census, or {@code null}
     * @param started      how many times each thread started between the two censuses (inside
     *                     the recording when there are not two), or {@code null}
     * @param ended        how many times each thread ended in the same stretch, or {@code null}
     * @param aliveAtEnd   the threads in the latest census, or {@code null}
     * @param covered      every thread a census row or a start or end event names, anywhere in
     *                     the file: the threads the counts can speak for; {@code null} without
     *                     a census, when no event can say which threads it would have named
     * @param attached     the threads native code attached to the JVM, from their starts anywhere in
     *                     the file; {@code null} when no start was recorded with its parent thread
     * @param startEvents  every start in the file, in time order
     * @param cpu          each Java thread's CPU, from {@code jdk.ThreadCPULoad}
     */
    public record Result(Set<ThreadRef> aliveAtStart, Map<ThreadRef, Long> started, Map<ThreadRef, Long> ended,
                         Set<ThreadRef> aliveAtEnd, Set<ThreadRef> covered, Set<ThreadRef> attached,
                         List<Start> startEvents, ThreadCpu.Result cpu) {
        /** Nothing known: a recording without the census or the lifetime events. */
        public static final Result UNKNOWN = new Result(null, null, null, null, null, null, List.of(),
                ThreadCpu.Result.UNKNOWN);

        public Result {
            aliveAtStart = aliveAtStart == null ? null : Set.copyOf(aliveAtStart);
            started = started == null ? null : Map.copyOf(started);
            ended = ended == null ? null : Map.copyOf(ended);
            aliveAtEnd = aliveAtEnd == null ? null : Set.copyOf(aliveAtEnd);
            covered = covered == null ? null : Set.copyOf(covered);
            attached = attached == null ? null : Set.copyOf(attached);
            startEvents = List.copyOf(startEvents);
        }

        /** The census and lives alone, as a file without starts' parents or CPU readings gives them. */
        public Result(final Set<ThreadRef> aliveAtStart, final Map<ThreadRef, Long> started,
                      final Map<ThreadRef, Long> ended, final Set<ThreadRef> aliveAtEnd, final Set<ThreadRef> covered) {
            this(aliveAtStart, started, ended, aliveAtEnd, covered, null, List.of(), ThreadCpu.Result.UNKNOWN);
        }

        /** Every start in the recording, or {@link Nulls#LONG_NULL} when it cannot say. */
        public long starts() {
            return sum(started);
        }

        /** Every end in the recording, or {@link Nulls#LONG_NULL} when it cannot say. */
        public long ends() {
            return sum(ended);
        }

        private static long sum(final Map<ThreadRef, Long> events) {
            if (events == null) {
                return Nulls.LONG_NULL;
            }
            long n = 0;
            for (final long v : events.values()) {
                n += v;
            }
            return n;
        }
    }

    /**
     * One {@code jdk.ThreadStart}.
     *
     * @param parent   the thread that started it; {@code null} when the event names none
     * @param stack    the starting thread's stack; empty when the event has none
     * @param isAttached whether native code attached the thread rather than Java code starting it
     */
    public record Start(long time, ThreadRef thread, ThreadRef parent, Stack stack, boolean isAttached) {
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of();
    }

    @Override
    public void begin(final Interner interner) {
        this.interner = interner;
    }

    @Override
    public void accept(@Transient final RecordedEvent e) {
        accept(e, EventKinds.kindOf(e.getEventType().getName()));
    }

    @Override
    public void accept(@Transient final RecordedEvent e, final int kind) {
        switch (kind) {
            case EventKinds.THREAD_START -> {
                final ThreadRef thread = Events.thread(e, Fields.THREAD, interner);
                if (thread != null) {
                    final ThreadRef parent = Events.thread(e, Fields.PARENT_THREAD, interner);
                    final Stack stack = Events.stack(e, interner);
                    final boolean attach = Events.isAttach(e, parent, stack, interner);
                    start(Events.startNanos(e), thread, parent, stack, attach);
                    cpu.start(thread, Events.startNanos(e), attach);
                }
            }
            case EventKinds.THREAD_END -> {
                final ThreadRef thread = Events.thread(e, Fields.THREAD, interner);
                if (thread != null) {
                    life(Events.startNanos(e), thread, true);
                }
            }
            case EventKinds.THREAD_CPU_LOAD -> {
                final ThreadRef thread = Events.thread(e, Fields.EVENT_THREAD, interner);
                if (thread != null) {
                    cpu.add(thread, Events.startNanos(e), Events.doubleOr(e, Fields.USER, 0, interner)
                            + Events.doubleOr(e, Fields.SYSTEM, 0, interner));
                }
            }
            case EventKinds.THREAD_ALLOCATION_STATISTICS -> {
                final ThreadRef thread = Events.thread(e, Fields.THREAD, interner);
                if (thread != null) {
                    row(Events.startNanos(e), thread);
                }
            }
            default -> {
            }
        }
    }

    /** One census row: {@code thread} was alive at {@code time}. */
    void row(final long time, final ThreadRef thread) {
        rowTimes.add(time);
        rowThreads.add(thread);
    }

    /** One start, or one end, of {@code thread}; a start with no parent known and no stack, never an attach. */
    void life(final long time, final ThreadRef thread, final boolean end) {
        lifeTimes.add(time);
        lifeThreads.add(thread);
        lifeEnds.add(end ? 1 : 0);
        lifeParents.add(null);
        lifeStacks.add(Stack.EMPTY);
        lifeAttached.add(0);
    }

    /** One start of {@code thread}, by {@code parent} from {@code stack}, or attached by native code. */
    void start(final long time, final ThreadRef thread, final ThreadRef parent, final Stack stack,
               final boolean attached) {
        lifeTimes.add(time);
        lifeThreads.add(thread);
        lifeEnds.add(0);
        lifeParents.add(parent);
        lifeStacks.add(stack);
        lifeAttached.add(attached ? 1 : 0);
        sawParent |= parent != null || attached;
    }

    @Override
    public void finish(final RecordingInfo info) {
        final boolean lifetimes = recorded(info, EventKinds.THREAD_START) && recorded(info, EventKinds.THREAD_END);
        Set<ThreadRef> atStart = null;
        Set<ThreadRef> atEnd = null;
        long from = Long.MIN_VALUE;
        long to = Long.MAX_VALUE;
        if (rowTimes.notEmpty()) {
            long first = Long.MAX_VALUE;
            long last = Long.MIN_VALUE;
            for (int i = 0, n = rowTimes.size(); i < n; i++) {
                first = Math.min(first, rowTimes.getQuick(i));
                last = Math.max(last, rowTimes.getQuick(i));
            }
            // A single batch is one end or the other, whichever it is nearer to.
            final boolean single = first == last;
            final boolean nearStart = first - info.startNanos() <= info.endNanos() - last;
            if (!single || nearStart) {
                atStart = batch(first);
                from = first;
            }
            if (!single || !nearStart) {
                atEnd = batch(last);
                to = last;
            }
        }
        Set<ThreadRef> covered = null;
        // Without a census, the lives alone say which threads they cover: a GC worker has none.
        if (rowTimes.notEmpty() || lifetimes) {
            covered = new HashSet<>();
            for (int i = 0, n = rowThreads.size(); i < n; i++) {
                covered.add(rowThreads.getQuick(i));
            }
            for (int i = 0, n = lifeThreads.size(); i < n; i++) {
                covered.add(lifeThreads.getQuick(i));
            }
        }
        // In time order, since delivery is file order: whether a start is a new life depends on
        // what came before it. A thread that starts at a census instant is already in it; one
        // that ends there still is.
        final int[] order = Sorts.order(lifeTimes);
        final List<Start> starts = new ArrayList<>();
        final Set<ThreadRef> attached = sawParent ? new HashSet<>() : null;
        for (final int i : order) {
            if (lifeEnds.getQuick(i) == 0) {
                final boolean byNative = lifeAttached.getQuick(i) != 0;
                starts.add(new Start(lifeTimes.getQuick(i), lifeThreads.getQuick(i), lifeParents.getQuick(i),
                        lifeStacks.getQuick(i), byNative));
                if (byNative) {
                    attached.add(lifeThreads.getQuick(i));
                }
            }
        }
        final ThreadCpu.Result threadCpu = cpu.result(info);
        if (!lifetimes) {
            result = new Result(atStart, null, null, atEnd, covered, attached, starts, threadCpu);
            return;
        }
        final Set<ThreadRef> alive = atStart == null ? new HashSet<>() : new HashSet<>(atStart);
        final ObjLongHashMap<ThreadRef> started = new ObjLongHashMap<>(64);
        final ObjLongHashMap<ThreadRef> ended = new ObjLongHashMap<>(64);
        for (final int i : order) {
            if (lifeTimes.getQuick(i) <= from || lifeTimes.getQuick(i) > to) {
                continue;
            }
            final ThreadRef thread = lifeThreads.getQuick(i);
            if (lifeEnds.getQuick(i) != 0) {
                ended.increment(thread, 1);
                alive.remove(thread);
            } else if (alive.add(thread)) {
                started.increment(thread, 1);
            }
        }
        result = new Result(atStart, toMap(started), toMap(ended), atEnd, covered, attached, starts, threadCpu);
    }

    /** Enabled in the settings, or present when the file carries no settings to ask. */
    private static boolean recorded(final RecordingInfo info, final int kind) {
        final String type = EventKinds.nameOf(kind);
        return info.isEnabled(type) || info.has(type);
    }

    private Set<ThreadRef> batch(final long time) {
        final Set<ThreadRef> out = new HashSet<>();
        for (int i = 0, n = rowTimes.size(); i < n; i++) {
            if (rowTimes.getQuick(i) == time) {
                out.add(rowThreads.getQuick(i));
            }
        }
        return out;
    }

    private static Map<ThreadRef, Long> toMap(final ObjLongHashMap<ThreadRef> events) {
        final Map<ThreadRef, Long> out = new HashMap<>(events.size() * 2);
        for (int s = 0, n = events.slots(); s < n; s++) {
            if (events.hasKeyAtSlot(s)) {
                out.put(events.keyAtSlot(s), events.valueAtSlot(s));
            }
        }
        return out;
    }

    public Result result() {
        if (result == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return result;
    }
}
