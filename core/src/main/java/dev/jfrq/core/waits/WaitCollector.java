// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.waits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
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
import dev.jfrq.core.util.ClassNames;

/**
 * Collects {@code jdk.JavaMonitorWait} and {@code jdk.ThreadPark} from the waiting thread's side
 * into a {@link WaitReport}: by the class waited on ({@code monitorClass}, {@code parkedClass}),
 * by the frame a {@link SiteKey} names in the waiting stack, and by the frame below it
 * ({@link SiteKey#below}), the split {@code hot} makes of a site's samples.
 *
 * <p>The thread and class filters run once per thread and once per class, not per event. A kept
 * wait is its start, its end and its interned stack appended to its class's lists; the span the
 * time is clipped to is known only in {@link #finish}, which clips each wait the way
 * {@code locks} does ({@link Interval#clampTo}) and resolves the site once per distinct stack.
 * The notifier of a wait is not read: the question is what the waiting thread was doing.
 */
public final class WaitCollector implements JfrReader.Sink {

    public static final String MONITOR_WAIT = EventKinds.nameOf(EventKinds.JAVA_MONITOR_WAIT);
    public static final String THREAD_PARK = EventKinds.nameOf(EventKinds.THREAD_PARK);

    private static final Set<String> TYPES = Set.of(MONITOR_WAIT, THREAD_PARK);

    private static final long UNKNOWN = -1;

    private final Predicate<String> threads;
    private final Predicate<String> classes;
    private final SiteKey key;
    /** 1 when a thread's name passes the thread filter, 0 when not; resolved once per thread. */
    private final ObjLongHashMap<ThreadRef> verdicts = new ObjLongHashMap<>(256, UNKNOWN);
    private final ObjObjHashMap<String, Waits> waits = new ObjObjHashMap<>(64);
    private final ObjObjHashMap<String, Waits> parks = new ObjObjHashMap<>(64);
    private Interner interner = new Interner();
    private WaitReport report;

    /**
     * @param threads which waiting threads are read, by name
     * @param classes which classes waited on are read, by JVM name or as printed ({@code byte[]})
     * @param key     which frame of a waiting stack names its site
     */
    public WaitCollector(final Predicate<String> threads, final Predicate<String> classes, final SiteKey key) {
        this.threads = threads;
        this.classes = classes;
        this.key = key;
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
        final boolean monitor = kind == EventKinds.JAVA_MONITOR_WAIT;
        if (!monitor && kind != EventKinds.THREAD_PARK) {
            return;
        }
        final ThreadRef thread = interner.thread(e);
        if (thread == null || !passes(thread)) {
            return;
        }
        final String cls = Events.className(e, monitor ? Fields.MONITOR_CLASS : Fields.PARKED_CLASS, interner);
        final Waits into = waitsOf(monitor ? waits : parks, cls == null ? WaitReport.NO_BLOCKER : cls);
        if (into != null) {
            final long startNanos = Events.startNanos(e);
            into.add(startNanos, Math.max(startNanos, Events.endNanos(e)), Events.stack(e, interner));
        }
    }

    /** A wait whose fields are already read: what {@link #accept(RecordedEvent, int)} does, for tests. */
    void waited(final WaitReport.Kind kind, final ThreadRef thread, final String cls, final long startNanos,
            final long endNanos, final Stack stack) {
        if (passes(thread)) {
            final Waits into = waitsOf(kind == WaitReport.Kind.WAIT ? waits : parks, cls);
            if (into != null) {
                into.add(startNanos, endNanos, stack);
            }
        }
    }

    private boolean passes(final ThreadRef thread) {
        final int index = verdicts.keyIndex(thread);
        if (index < 0) {
            return verdicts.valueAtQuick(index) == 1;
        }
        final boolean passes = threads.test(thread.name());
        verdicts.putAt(index, thread, passes ? 1 : 0);
        return passes;
    }

    /** The class's waits, or {@code null} for a class the filter leaves out; the filter runs once per class. */
    private Waits waitsOf(final ObjObjHashMap<String, Waits> byClass, final String cls) {
        final int index = byClass.keyIndex(cls);
        if (index < 0) {
            return byClass.valueAtQuick(index);
        }
        final boolean kept = classes.test(cls) || classes.test(ClassNames.pretty(cls));
        return byClass.putAt(index, cls, kept ? new Waits() : null);
    }

    @Override
    public void finish(final RecordingInfo info) {
        final Interval window = info.span();
        final List<WaitReport.Monitor> rows = new ArrayList<>();
        final long[] totals = new long[3];
        monitors(WaitReport.Kind.WAIT, waits, window, rows, totals);
        monitors(WaitReport.Kind.PARK, parks, window, rows, totals);
        rows.sort(Comparator.comparingLong(WaitReport.Monitor::totalNanos).reversed()
                .thenComparing(WaitReport.Monitor::className).thenComparing(WaitReport.Monitor::kind));
        report = new WaitReport(info, key.description(), totals[0], totals[1], totals[2], List.copyOf(rows));
    }

    /** A row per class of {@code byClass} with time inside {@code window}; {@code totals}: time, waits, clipped. */
    private void monitors(final WaitReport.Kind kind, final ObjObjHashMap<String, Waits> byClass, final Interval window,
            final List<WaitReport.Monitor> rows, final long[] totals) {
        for (int s = 0, n = byClass.slots(); s < n; s++) {
            if (byClass.hasKeyAtSlot(s) && byClass.valueAtSlot(s) != null) {
                final WaitReport.Monitor row = monitor(kind, byClass.keyAtSlot(s), byClass.valueAtSlot(s), window,
                        totals);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
    }

    private WaitReport.Monitor monitor(final WaitReport.Kind kind, final String cls, final Waits w,
            final Interval window, final long[] totals) {
        // Per distinct stack first: the site and the frame below it are resolved once per stack.
        final Map<Stack, Acc> byStack = new IdentityHashMap<>();
        for (int i = 0, n = w.stacks.size(); i < n; i++) {
            final Interval whole = new Interval(w.startsNanos.getQuick(i), w.endsNanos.getQuick(i));
            final long insideNanos = whole.clampTo(window).duration();
            if (insideNanos <= 0) {
                continue;
            }
            if (insideNanos < whole.duration()) {
                totals[2]++;
            }
            byStack.computeIfAbsent(w.stacks.getQuick(i), _ -> new Acc()).add(insideNanos);
        }
        if (byStack.isEmpty()) {
            return null;
        }
        final Acc all = new Acc();
        final Map<String, Acc> sites = new HashMap<>();
        final Map<String, Map<String, Acc>> belowSites = new HashMap<>();
        for (final Map.Entry<Stack, Acc> e : byStack.entrySet()) {
            final Stack stack = e.getKey();
            final Acc a = e.getValue();
            final int at = key.frameIndex(stack);
            final String site = at < 0 ? SiteKey.NO_STACK : stack.frameQuick(at).stableName();
            sites.computeIfAbsent(site, _ -> new Acc()).merge(a);
            belowSites.computeIfAbsent(site, _ -> new HashMap<>())
                    .computeIfAbsent(SiteKey.below(stack, at), _ -> new Acc()).merge(a);
            all.merge(a);
        }
        final List<WaitReport.Site> siteRows = new ArrayList<>(sites.size());
        for (final Map.Entry<String, Acc> e : sites.entrySet()) {
            final List<WaitReport.Below> below = new ArrayList<>();
            for (final Map.Entry<String, Acc> b : belowSites.get(e.getKey()).entrySet()) {
                below.add(new WaitReport.Below(b.getKey(), b.getValue().totalNanos, b.getValue().waits));
            }
            below.sort(Comparator.comparingLong(WaitReport.Below::totalNanos).reversed()
                    .thenComparing(WaitReport.Below::frame));
            final Acc a = e.getValue();
            siteRows.add(new WaitReport.Site(e.getKey(), a.totalNanos, a.waits, a.maxNanos, List.copyOf(below)));
        }
        siteRows.sort(Comparator.comparingLong(WaitReport.Site::totalNanos).reversed()
                .thenComparing(WaitReport.Site::frame));
        totals[0] += all.totalNanos;
        totals[1] += all.waits;
        return new WaitReport.Monitor(kind, cls, all.totalNanos, all.waits, all.maxNanos, List.copyOf(siteRows));
    }

    /** The answer, after {@link #finish}. */
    public WaitReport report() {
        if (report == null) {
            throw new IllegalStateException("finish() has not run");
        }
        return report;
    }

    /** One class's waits while reading, in file order: start, end and stack of each. */
    private static final class Waits {

        final LongList startsNanos = new LongList(64);
        final LongList endsNanos = new LongList(64);
        final ObjList<Stack> stacks = new ObjList<>(64);

        void add(final long startNanos, final long endNanos, final Stack stack) {
            startsNanos.add(startNanos);
            endsNanos.add(endNanos);
            stacks.add(stack);
        }
    }

    /** Time, count and longest of a group of waits, in {@link #finish}. */
    private static final class Acc {

        long totalNanos;
        long waits;
        long maxNanos;

        void add(final long nanos) {
            totalNanos += nanos;
            waits++;
            maxNanos = Math.max(maxNanos, nanos);
        }

        void merge(final Acc o) {
            totalNanos += o.totalNanos;
            waits += o.waits;
            maxNanos = Math.max(maxNanos, o.maxNanos);
        }
    }
}
