// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.LongObjHashMap;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.stalls.Timeline.Block;
import dev.jfrq.core.stalls.Timeline.BlockKind;
import dev.jfrq.core.stalls.Timeline.Pause;
import dev.jfrq.core.stalls.Timeline.PauseKind;
import dev.jfrq.core.stalls.Timeline.Sample;
import dev.jfrq.core.stalls.Timeline.ThreadTimeline;
import dev.jfrq.core.util.ClassNames;
import dev.jfrq.core.util.Sorts;

/**
 * Builds {@link ThreadTimeline}s for the threads matching a filter, plus the JVM-wide
 * pauses, from one pass over a recording; then hands them to {@link StallAnalysis}.
 *
 * <p>Pauses come from three event families. {@code jdk.GCPhasePause} is the
 * stop-the-world part of a collection. Every other safepoint is assembled from
 * {@code jdk.SafepointBegin} (the time to bring the threads to a halt) and
 * {@code jdk.ExecuteVMOperation} (the operation that ran while they were halted, which
 * names it: a thread dump, a deoptimisation, a class redefinition), joined on the
 * safepoint id. {@code jdk.SafepointEnd} is used when present, but the JDK's own
 * {@code default} and {@code profile} settings disable it, which is why the VM operation
 * event is the one that matters.
 *
 * <p>Every thread's parks are weighed for {@link Perch} and every thread's monitor waits are
 * kept to resolve lock holders, watched or not: both answers depend on what the other
 * threads did, and must not change with {@code --thread}. {@code jdk.ThreadStart} and
 * {@code jdk.ThreadEnd} bound a watched thread's life inside the recording, so that the
 * stretch before its first sample and after its last can be judged without mistaking a
 * thread's birth or death for a stall.
 *
 * <p>Per event the collector does one probe per thread (the filter verdict is cached
 * with the thread), reuses the detail strings for locks and peers, and keeps GC pauses,
 * safepoint events and every thread's parks flat until {@link #finish}; the {@link Sample}
 * and {@link Block} records the analysis reads, with the {@link Interval} of each block,
 * are the only per-event allocations left.
 */
public final class StallCollector implements JfrReader.Sink {

    private static final Set<String> TYPES = EventKinds.names(
            EventKinds.EXECUTION_SAMPLE, EventKinds.NATIVE_METHOD_SAMPLE,
            EventKinds.JAVA_MONITOR_ENTER, EventKinds.THREAD_PARK, EventKinds.JAVA_MONITOR_WAIT,
            EventKinds.THREAD_SLEEP, EventKinds.SOCKET_READ, EventKinds.SOCKET_WRITE, EventKinds.FILE_READ,
            EventKinds.FILE_WRITE, EventKinds.FILE_FORCE, EventKinds.GC_PHASE_PAUSE, EventKinds.SAFEPOINT_BEGIN,
            EventKinds.SAFEPOINT_END, EventKinds.EXECUTE_VM_OPERATION, EventKinds.THREAD_START,
            EventKinds.THREAD_END);

    private static final Comparator<Sample> BY_TIME = Comparator.comparingLong(Sample::time);
    private static final Comparator<Block> BY_INTERVAL = Comparator.comparing(Block::interval);
    private static final Comparator<Pause> PAUSE_BY_INTERVAL = Comparator.comparing(Pause::interval);
    private static final ToLongFunction<Pause> PAUSE_START = Pause::start;
    private static final ToLongFunction<Pause> PAUSE_DURATION = Pause::duration;
    /** A monitor block names its lock by {@code detail}. */
    static final Holders.Access<Block> MONITOR_WAITS = new Holders.Access<>() {
        @Override
        public Interval interval(final Block wait) {
            return wait.interval();
        }

        @Override
        public ThreadRef owner(final Block wait) {
            return wait.owner();
        }

        @Override
        public Object lock(final Block wait) {
            return wait.detail();
        }

        @Override
        public Block withHolder(final Block wait, final ThreadRef holder, final List<ThreadRef> via) {
            return new Block(wait.interval(), wait.kind(), wait.detail(), wait.stack(), holder, via, wait.bytes(),
                    wait.timedOut());
        }
    };

    /** The coarsest wall-clock tick a deadline and an event end can disagree by: Windows' 15.6 ms. */
    private static final long WALL_CLOCK_TICK_NANOS = 16_000_000L;
    /** GC pauses are kept flat until the end (G-1.8): three longs per pause. */
    private static final int GC_SLOT = 3;
    private static final int GC_START = 0;
    private static final int GC_END = 1;
    private static final int GC_ID = 2;

    /** Safepoint events are kept flat until the end (G-1.8), joined on their id in {@link #finish}. */
    private static final int SP_SLOT = 4;
    private static final int SP_KIND = 0;
    private static final int SP_ID = 1;
    private static final int SP_START = 2;
    private static final int SP_END = 3;
    private static final long SP_BEGIN = 0;
    private static final long SP_END_EVENT = 1;
    private static final long SP_OPERATION = 2;

    private final Predicate<String> threadFilter;
    private final IdleMatcher idle;
    private final StallAnalysis analysis;
    /** Every thread seen in a thread event, watched or not, with the filter's verdict. */
    private final ObjObjHashMap<ThreadRef, ThreadEvents> threads = new ObjObjHashMap<>(256);
    private final LongList gcPauses = new LongList(64 * GC_SLOT);
    private final ObjList<String> gcNames = new ObjList<>(64);
    private final LongList safepointEvents = new LongList(64 * SP_SLOT);
    /** The operation's name for a safepoint row that is a VM operation, {@code null} for the others. */
    private final ObjList<String> safepointOperations = new ObjList<>(64);
    /** Every thread's monitor waits: who really held a lock depends on threads nobody asked about. */
    private final Holders<Block> holders = new Holders<>(MONITOR_WAITS);
    /** Every thread's parks: whether a lock is a perch depends on everyone who parked on it. */
    private final ParkShapes parks = new ParkShapes();
    private final LockNames lockNames = new LockNames();
    private final PeerNames peerNames = new PeerNames();
    private Interner interner = new Interner();
    private StallReport report;

    public StallCollector(final Predicate<String> threadFilter, final IdleMatcher idle, final long gapNanos) {
        this(threadFilter, idle, IdleMatcher.forWorkWaits(), gapNanos);
    }

    /**
     * @param idle      what an idle <em>sample</em> looks like: the wait is the top frame
     * @param workWaits what a park that is only "no work to do" looks like: the frame that
     *                  decides sits below the park, so the two lists are not the same list
     */
    public StallCollector(final Predicate<String> threadFilter, final IdleMatcher idle, final IdleMatcher workWaits,
                          final long gapNanos) {
        this(threadFilter, idle, workWaits, gapNanos, SiteKey.culpritMethod());
    }

    /** @param culpritKey what names a busy run's culprit ({@code --app}); see {@link StallAnalysis} */
    public StallCollector(final Predicate<String> threadFilter, final IdleMatcher idle, final IdleMatcher workWaits,
                          final long gapNanos, final SiteKey culpritKey) {
        this.threadFilter = threadFilter;
        this.idle = idle;
        this.analysis = new StallAnalysis(gapNanos, workWaits, culpritKey);
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

    @Override
    public void accept(@Transient final RecordedEvent e, final int kind) {
        switch (kind) {
            case EventKinds.GC_PHASE_PAUSE -> {
                final long start = Events.startNanos(e);
                gcPauses.add(start);
                gcPauses.add(Math.max(start, Events.endNanos(e)));
                gcPauses.add(Events.longOr(e, Fields.GC_ID, Nulls.LONG_NULL, interner));
                gcNames.add(Events.stringOr(e, Fields.NAME, "GC", interner));
            }
            // Begin, end and operation may arrive in any order (delivery is file order); joined in finish().
            case EventKinds.SAFEPOINT_BEGIN -> safepointEvent(e, SP_BEGIN, null);
            case EventKinds.SAFEPOINT_END -> safepointEvent(e, SP_END_EVENT, null);
            case EventKinds.EXECUTE_VM_OPERATION -> {
                if (Events.booleanOr(e, Fields.SAFEPOINT, false, interner)) {
                    safepointEvent(e, SP_OPERATION, Events.stringOr(e, Fields.OPERATION, "?", interner));
                }
            }
            default -> acceptThreadEvent(e, kind);
        }
    }

    private void safepointEvent(@Transient final RecordedEvent e, final long kind, final String operation) {
        final long start = Events.startNanos(e);
        safepointEvents.add(kind);
        safepointEvents.add(Events.longOr(e, Fields.SAFEPOINT_ID, Nulls.LONG_NULL, interner));
        safepointEvents.add(start);
        safepointEvents.add(Math.max(start, Events.endNanos(e)));
        safepointOperations.add(operation);
    }

    private void acceptThreadEvent(@Transient final RecordedEvent e, final int kind) {
        final ThreadRef thread = interner.thread(e);
        if (thread == null) {
            return;
        }
        final int index = threads.keyIndex(thread);
        final ThreadEvents t = index < 0 ? threads.valueAtQuick(index)
                : threads.putAt(index, thread, new ThreadEvents(threadFilter.test(thread.name())));
        switch (kind) {
            case EventKinds.JAVA_MONITOR_ENTER -> {
                // Kept for every thread; a watched thread's own wait carries the stack and is the same object.
                final Block wait = new Block(Events.interval(e), BlockKind.MONITOR, lockName(e, Fields.MONITOR_CLASS),
                        t.watched ? Events.stack(e, interner) : Stack.EMPTY,
                        Events.thread(e, Fields.PREVIOUS_OWNER, interner));
                holders.add(thread, wait);
                if (t.watched) {
                    t.blocks.add(wait);
                }
                return;
            }
            case EventKinds.THREAD_PARK -> {
                // Weighed for every thread; a watched thread's park is also one of its blocks.
                final String lock = parkName(e);
                if (t.watched) {
                    final Stack stack = Events.stack(e, interner);
                    final Interval interval = Events.interval(e);
                    final Block park = new Block(interval, BlockKind.PARK, lock, stack, parkTimedOut(e, interval));
                    t.blocks.add(park);
                    parks.add(thread, lock, park.start(), park.interval().end(), stack);
                } else if (!ParkShapes.NO_BLOCKER.equals(lock)) {
                    // A park on no object is never weighed, so its stack and times are not read.
                    final long start = Events.startNanos(e);
                    parks.add(thread, lock, start, Math.max(start, Events.endNanos(e)), Events.stack(e, interner));
                }
                return;
            }
            case EventKinds.THREAD_START -> {
                t.started = Events.startNanos(e);
                return;
            }
            case EventKinds.THREAD_END -> {
                t.ended = Events.startNanos(e);
                return;
            }
            // Counted for every thread: how often the sampler saw a thread depends on how many others it saw.
            case EventKinds.EXECUTION_SAMPLE -> t.javaSamples++;
            case EventKinds.NATIVE_METHOD_SAMPLE -> t.nativeSamples++;
            default -> {
            }
        }
        if (!t.watched) {
            return;
        }
        switch (kind) {
            case EventKinds.EXECUTION_SAMPLE, EventKinds.NATIVE_METHOD_SAMPLE -> {
                final Stack stack = Events.stack(e, interner);
                final boolean inNative = kind == EventKinds.NATIVE_METHOD_SAMPLE;
                t.samples.add(new Sample(Events.startNanos(e), stack, idle.isIdle(stack), inNative));
            }
            case EventKinds.JAVA_MONITOR_WAIT -> t.blocks.add(new Block(Events.interval(e), BlockKind.OBJECT_WAIT,
                    lockNames.on(lockName(e, Fields.MONITOR_CLASS)), Events.stack(e, interner),
                    Events.booleanOr(e, Fields.TIMED_OUT, false, interner)));
            case EventKinds.THREAD_SLEEP -> {
                final Interval interval = Events.interval(e);
                // In the recording's own unit: nanoseconds on JDK 25, milliseconds on JDK 17.
                final long time = Events.durationNanosOr(e, Fields.TIME, Nulls.LONG_NULL, interner);
                t.blocks.add(new Block(interval, BlockKind.SLEEP, "", Events.stack(e, interner),
                        time != Nulls.LONG_NULL && interval.duration() >= time));
            }
            case EventKinds.SOCKET_READ -> block(t, e, BlockKind.SOCKET_READ, peerNames.from(peer(e)),
                    Events.longOr(e, Fields.BYTES_READ, 0, interner));
            case EventKinds.SOCKET_WRITE -> block(t, e, BlockKind.SOCKET_WRITE, peerNames.to(peer(e)),
                    Events.longOr(e, Fields.BYTES_WRITTEN, 0, interner));
            case EventKinds.FILE_READ -> block(t, e, BlockKind.FILE_READ, Events.stringOr(e, Fields.PATH, "?", interner),
                    Events.longOr(e, Fields.BYTES_READ, 0, interner));
            case EventKinds.FILE_WRITE -> block(t, e, BlockKind.FILE_WRITE, Events.stringOr(e, Fields.PATH, "?", interner),
                    Events.longOr(e, Fields.BYTES_WRITTEN, 0, interner));
            case EventKinds.FILE_FORCE -> block(t, e, BlockKind.FILE_FORCE, Events.stringOr(e, Fields.PATH, "?", interner), 0);
            default -> {
            }
        }
    }

    private void block(final ThreadEvents t, @Transient final RecordedEvent e, final BlockKind kind, final String detail,
                       final long bytes) {
        t.blocks.add(new Block(Events.interval(e), kind, detail, Events.stack(e, interner), bytes));
    }

    /**
     * Whether a park ran out the time it was given: a relative timeout (nanoseconds) it lasted
     * at least, or an absolute deadline (epoch milliseconds) it ended at or after. An untimed
     * park carries neither.
     *
     * <p>The deadline was read from the wall clock and the event's end is JFR's tick clock
     * placed on the wall clock at the chunk's start; the two can disagree by a clock tick,
     * and on Windows a 150 ms {@code parkUntil} that ran its course was seen ending before
     * its deadline. So an end within {@link #WALL_CLOCK_TICK_NANOS} of it counts.
     */
    private boolean parkTimedOut(@Transient final RecordedEvent e, final Interval interval) {
        // Nanoseconds on every JDK, and Long.MIN_VALUE for an untimed park, which a Duration cannot hold.
        final long timeout = Events.longOr(e, Fields.TIMEOUT, Nulls.LONG_NULL, interner);
        if (timeout > 0) {
            return interval.duration() >= timeout;
        }
        final long until = Events.longOr(e, Fields.UNTIL, Nulls.LONG_NULL, interner);
        return until > 0 && interval.end() >= until * 1_000_000L - WALL_CLOCK_TICK_NANOS;
    }

    /** {@code dev.app.Registry@1f2e}: one string per (class, address), reused across events. */
    private String lockName(@Transient final RecordedEvent e, final int field) {
        return lockNames.name(Events.className(e, field, interner), Events.longOr(e, Fields.ADDRESS, 0, interner));
    }

    private String parkName(@Transient final RecordedEvent e) {
        final String cls = Events.className(e, Fields.PARKED_CLASS, interner);
        return cls == null ? ParkShapes.NO_BLOCKER : lockNames.on(lockName(e, Fields.PARKED_CLASS));
    }

    /** {@code host:port}, or {@code address:port} when the host is unknown; one string per peer. */
    private String peer(@Transient final RecordedEvent e) {
        final String host = Events.stringOr(e, Fields.HOST, "", interner);
        final String where = host.isEmpty() ? Events.stringOr(e, Fields.ADDRESS, "?", interner) : host;
        return peerNames.peer(where, Events.longOr(e, Fields.PORT, 0, interner));
    }

    @Override
    public void finish(final RecordingInfo info) {
        holders.seal();
        final Interval span = info.span();
        // Without both lifetime events a thread's birth or death inside the window cannot be
        // told from a stall, so the stretches before its first sample and after its last are
        // not judged at all.
        final boolean lifetimes = info.isEnabled(EventKinds.nameOf(EventKinds.THREAD_START))
                && info.isEnabled(EventKinds.nameOf(EventKinds.THREAD_END));
        final ObjList<ThreadTimeline> timelines = new ObjList<>();
        final StallAnalysis.SamplerShares shares = new StallAnalysis.SamplerShares();
        for (int s = 0, n = threads.slots(); s < n; s++) {
            if (!threads.hasKeyAtSlot(s)) {
                continue;
            }
            final ThreadEvents t = threads.valueAtSlot(s);
            shares.add(t.javaSamples, t.nativeSamples, lifetimes ? life(t, span) : span.duration());
            if (!t.watched || (t.samples.isEmpty() && t.blocks.isEmpty())) {
                continue;
            }
            final ThreadRef thread = threads.keyAtSlot(s);
            t.samples.sort(BY_TIME);
            final ObjList<Block> blocks = new ObjList<>(t.blocks.size());
            for (int i = 0, m = t.blocks.size(); i < m; i++) {
                final Block block = t.blocks.getQuick(i);
                blocks.add(block.kind() == BlockKind.MONITOR ? holders.resolve(thread, block) : block);
            }
            blocks.sort(BY_INTERVAL);
            long lifeStart = Nulls.LONG_NULL;
            long lifeEnd = Nulls.LONG_NULL;
            if (lifetimes) {
                lifeStart = lifeStart(t, span);
                lifeEnd = lifeEnd(t, span, lifeStart);
            }
            timelines.add(new ThreadTimeline(thread, t.samples.toList(), blocks.toList(), lifeStart, lifeEnd));
        }
        report = analysis.analyse(info, timelines.toList(), pauses(), parks, silentThreads(info), shares);
    }

    /** A thread's life inside the window, from its start and end events where it has them. */
    private static long life(final ThreadEvents t, final Interval span) {
        final long start = lifeStart(t, span);
        return lifeEnd(t, span, start) - start;
    }

    private static long lifeStart(final ThreadEvents t, final Interval span) {
        return t.started == Nulls.LONG_NULL ? span.start() : Math.min(Math.max(t.started, span.start()), span.end());
    }

    private static long lifeEnd(final ThreadEvents t, final Interval span, final long lifeStart) {
        return t.ended == Nulls.LONG_NULL ? span.end() : Math.max(Math.min(t.ended, span.end()), lifeStart);
    }

    /**
     * Threads the filter matches that the recording saw, in any event this pass read, but
     * that have neither a sample nor a blocking event: the JVM's own threads, and any thread
     * blocked through the whole window with nothing ending inside it.
     */
    private List<String> silentThreads(final RecordingInfo info) {
        final List<String> silent = new ArrayList<>();
        for (final ThreadRef thread : info.threads()) {
            final ThreadEvents t = threads.get(thread);
            final boolean evidence = t != null && (t.samples.notEmpty() || t.blocks.notEmpty());
            if (!evidence && threadFilter.test(thread.name())) {
                silent.add(thread.name());
            }
        }
        silent.sort(Comparator.naturalOrder());
        return silent;
    }

    /**
     * GC pauses plus every other safepoint, each reported once. A safepoint spans from the
     * begin event's start to the end of its VM operation (or its end event, when recorded);
     * an operation whose begin event fell under the recording's threshold stands alone.
     * A safepoint that overlaps a GC pause is the GC's own and is dropped as a duplicate.
     */
    private List<Pause> pauses() {
        final ObjList<Pause> gcs = new ObjList<>(gcNames.size());
        for (int i = 0, n = gcNames.size(); i < n; i++) {
            final int slot = i * GC_SLOT;
            final long gcId = gcPauses.getQuick(slot + GC_ID);
            gcs.add(new Pause(new Interval(gcPauses.getQuick(slot + GC_START), gcPauses.getQuick(slot + GC_END)),
                    PauseKind.GC, gcId == Nulls.LONG_NULL ? gcNames.getQuick(i) : gcNames.getQuick(i) + " (gcId " + gcId + ")"));
        }
        gcs.sort(PAUSE_BY_INTERVAL);
        final long longestGc = Sorts.maxDuration(gcs, PAUSE_DURATION);

        final ObjList<Pause> safepoints = safepoints();
        final ObjList<Pause> pauses = new ObjList<>(gcs.size() + safepoints.size());
        pauses.addAll(gcs);
        for (int s = 0, n = safepoints.size(); s < n; s++) {
            final Pause sp = safepoints.getQuick(s);
            boolean isGc = false;
            final int from = Sorts.lowerBound(gcs, PAUSE_START, sp.start() - longestGc);
            for (int i = from, m = gcs.size(); i < m; i++) {
                final Pause gc = gcs.getQuick(i);
                if (gc.start() >= sp.interval().end()) {
                    break;
                }
                if (gc.interval().overlap(sp.interval()) >= StallAnalysis.COVER * sp.duration()) {
                    isGc = true;
                    break;
                }
            }
            if (!isGc) {
                pauses.add(sp);
            }
        }
        return pauses.toList();
    }

    /**
     * The safepoint events joined on their id: a safepoint spans from the begin event's start
     * to the end of its VM operation (or its end event, when recorded). An event without an
     * id cannot be joined; an operation without one stands alone, like one whose begin event
     * fell under the threshold, and a begin or end without one says nothing on its own.
     */
    private ObjList<Pause> safepoints() {
        final int rows = safepointOperations.size();
        final LongObjHashMap<Safepoint> byId = new LongObjHashMap<>(Math.max(16, rows), Nulls.LONG_NULL);
        final ObjList<Pause> out = new ObjList<>(rows);
        for (int r = 0; r < rows; r++) {
            final int slot = r * SP_SLOT;
            final long kind = safepointEvents.getQuick(slot + SP_KIND);
            final long id = safepointEvents.getQuick(slot + SP_ID);
            final long start = safepointEvents.getQuick(slot + SP_START);
            final long end = safepointEvents.getQuick(slot + SP_END);
            if (id == Nulls.LONG_NULL) {
                if (kind == SP_OPERATION) {
                    out.add(new Pause(new Interval(start, end), PauseKind.SAFEPOINT,
                            "VM operation " + safepointOperations.getQuick(r)));
                }
                continue;
            }
            final int index = byId.keyIndex(id);
            final Safepoint sp = index < 0 ? byId.valueAtQuick(index) : byId.putAt(index, id, new Safepoint(id));
            if (kind == SP_BEGIN) {
                sp.begin(start, end);
            } else if (kind == SP_END_EVENT) {
                sp.end(end);
            } else {
                sp.operation(start, end, safepointOperations.getQuick(r));
            }
        }
        for (int s = 0, n = byId.slots(); s < n; s++) {
            if (byId.hasKeyAtSlot(s)) {
                final Pause sp = byId.valueAtSlot(s).pause();
                if (sp != null) {
                    out.add(sp);
                }
            }
        }
        return out;
    }

    public StallReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }

    /** What one thread contributed. Lists are small at first; a busy watched thread grows them. */
    private static final class ThreadEvents {
        /** The filter's verdict for the thread's name, decided once. */
        final boolean watched;
        final ObjList<Sample> samples = new ObjList<>(16);
        final ObjList<Block> blocks = new ObjList<>(8);
        /** How often the sampler saw the thread in Java and in native code, watched or not. */
        int javaSamples;
        int nativeSamples;
        /** When the thread started and ended, if the recording saw it happen. */
        long started = Nulls.LONG_NULL;
        long ended = Nulls.LONG_NULL;

        ThreadEvents(final boolean watched) {
            this.watched = watched;
        }
    }

    /** The three events of one safepoint, joined in {@link #finish}, whatever order the file delivered them in. */
    private static final class Safepoint {
        final long id;
        long beginStart = Nulls.LONG_NULL;
        long beginEnd;
        long recordedEnd = Nulls.LONG_NULL;
        long operationStart = Nulls.LONG_NULL;
        long operationEnd;
        String operation;

        Safepoint(final long id) {
            this.id = id;
        }

        void begin(final long start, final long end) {
            beginStart = start;
            beginEnd = end;
        }

        void end(final long endNanos) {
            recordedEnd = endNanos;
        }

        void operation(final long start, final long end, final String name) {
            operationStart = start;
            operationEnd = end;
            operation = name;
        }

        /** The pause, or {@code null} when only an end event was seen. */
        Pause pause() {
            if (beginStart != Nulls.LONG_NULL) {
                long end = beginEnd;
                if (recordedEnd != Nulls.LONG_NULL) {
                    end = Math.max(end, recordedEnd);
                }
                if (operationStart != Nulls.LONG_NULL) {
                    end = Math.max(end, operationEnd);
                }
                final String what = operation == null ? "safepoint " + id : "VM operation " + operation;
                return new Pause(new Interval(beginStart, Math.max(beginStart, end)), PauseKind.SAFEPOINT, what);
            }
            if (operationStart != Nulls.LONG_NULL) {
                return new Pause(new Interval(operationStart, operationEnd), PauseKind.SAFEPOINT,
                        "VM operation " + operation);
            }
            return null;
        }
    }

    /**
     * {@code Class@address} strings and their {@code on } prefix, one per lock: a
     * recording holds a few locks and hundreds of thousands of waits (G-2.3). Keyed by
     * address, with the class checked; an address reused by another class (the object
     * moved) replaces the entry, and the earlier string stays alive through its blocks.
     */
    private static final class LockNames {
        private final LongObjHashMap<Entry> byAddress = new LongObjHashMap<>(64, Long.MIN_VALUE);
        private final ObjObjHashMap<String, String> prefixed = new ObjObjHashMap<>(64);

        private static final class Entry {
            final String cls;
            final String name;

            Entry(final String cls, final String name) {
                this.cls = cls;
                this.name = name;
            }
        }

        String name(final String cls, final long address) {
            final int index = byAddress.keyIndex(address);
            if (index < 0) {
                final Entry e = byAddress.valueAtQuick(index);
                if (e.cls == cls || (cls != null && cls.equals(e.cls))) {
                    return e.name;
                }
            }
            final String name = (cls == null ? "?" : ClassNames.pretty(cls)) + "@" + Long.toHexString(address);
            final Entry created = new Entry(cls, name);
            if (index < 0) {
                byAddress.put(address, created);
            } else {
                byAddress.putAt(index, address, created);
            }
            return created.name;
        }

        /** {@code "on " + name}, one per name. */
        String on(final String name) {
            final int index = prefixed.keyIndex(name);
            return index < 0 ? prefixed.valueAtQuick(index) : prefixed.putAt(index, name, "on " + name);
        }
    }

    /** {@code host:port} strings and their {@code from }/{@code to } prefixes, one per peer. */
    private static final class PeerNames {
        private final ObjObjHashMap<String, LongObjHashMap<String>> byHost = new ObjObjHashMap<>(16);
        private final ObjObjHashMap<String, String> from = new ObjObjHashMap<>(16);
        private final ObjObjHashMap<String, String> to = new ObjObjHashMap<>(16);

        String peer(final String where, final long port) {
            final int index = byHost.keyIndex(where);
            final LongObjHashMap<String> ports = index < 0 ? byHost.valueAtQuick(index)
                    : byHost.putAt(index, where, new LongObjHashMap<>(4, Long.MIN_VALUE));
            final int p = ports.keyIndex(port);
            return p < 0 ? ports.valueAtQuick(p) : ports.putAt(p, port, where + ":" + port);
        }

        String from(final String peer) {
            final int index = from.keyIndex(peer);
            return index < 0 ? from.valueAtQuick(index) : from.putAt(index, peer, "from " + peer);
        }

        String to(final String peer) {
            final int index = to.keyIndex(peer);
            return index < 0 ? to.valueAtQuick(index) : to.putAt(index, peer, "to " + peer);
        }
    }
}
