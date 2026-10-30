// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjHashSet;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

/**
 * Aggregates allocation events into an {@link AllocationReport}.
 *
 * <p>Two families of events can carry the estimate:
 * <ul>
 *   <li>{@code jdk.ObjectAllocationSample} (JDK 16+): throttled samples whose {@code weight}
 *       field is the number of bytes the sample stands for. Summing weights, never counting
 *       samples, gives the estimate.</li>
 *   <li>{@code jdk.ObjectAllocationInNewTLAB} / {@code OutsideTLAB} (older recordings, or
 *       profiles that enable them explicitly): one event per new TLAB, weighted by the TLAB
 *       size, plus one per large allocation, weighted by its size.</li>
 * </ul>
 * When both are present the sampled family is used, because it is the one the JDK tunes.
 *
 * <p>One sample per thread is discarded: the first. Its weight is the bytes the thread
 * allocated since it was <em>last sampled</em>, and for a thread that was never sampled
 * before, or not since an earlier recording hours ago, that is its lifetime allocation.
 * Left in, a main thread that allocated 100 MB at start-up and nothing during a one-minute
 * recording is reported as allocating 100 MB in that minute. Dropping the sample loses at
 * most the bytes between the previous sample and this one, which for a thread sampled at
 * hundreds of times a second is nothing, and for a thread sampled once is unknowable.
 * A platform thread born in the file keeps its first sample: its lifetime began inside the
 * recording, so the weight is all in the window, and dropping it lost most of what
 * short-lived pool threads allocated. Born means a {@code jdk.ThreadStart} no later than the
 * thread's first counter reading and first sample: the JVM that starts a recording writes one
 * for {@code main} seconds after main was sampled and counted ({@link #bornInFile}).
 *
 * <p>A virtual thread loses its first sample too, and the report says how many and how
 * heavy they were ({@link AllocationReport.Dropped}). The JVM counts allocation per
 * carrier, so a sample taken on a virtual thread is weighted by what its carrier allocated
 * since the carrier was last sampled, whichever virtual threads it ran meanwhile, and a
 * carrier never sampled before carries everything it allocated since it started: 3.35 GB
 * for 67 MB allocated inside the recording, on a JVM that had been running virtual threads
 * before it. The event names the virtual thread, not the carrier, so that sample cannot
 * be told apart from an honest one; dropping every virtual thread's first sample removes
 * it whenever it lands on one, at the price of most virtual-thread allocation (a virtual
 * thread is typically sampled once or never). A virtual thread that resumed on a carrier not
 * yet sampled carries that carrier's history on a later sample; such a sample is left out too
 * when it weighs more than every thread's counter grew by in the recording, which allocation
 * inside the recording cannot (one carrier's history: 3.89 GB on a sample, 259 MB counted).
 * A later sample under that bound is kept, so the estimate is still not a floor.
 *
 * <p>Independently of either, {@code jdk.ThreadAllocationStatistics} (written at every
 * chunk boundary by the JDK's own settings) carries each thread's exact allocation
 * counter. The difference between its last counter and its first is what the thread
 * allocated in between, and a thread that started in the file had a counter of zero at its
 * {@code jdk.ThreadStart}; the report shows that next to the estimate, so a reader knows how
 * far the sampling is from the truth for the threads that matter. The estimate it is set
 * against is the samples inside that same stretch, which is why each platform thread's
 * samples are kept with their times (per {@link #TLAB_BUCKET_NANOS} for the TLAB events): set
 * against the whole file, a pool thread started after the first chunk had its early
 * allocation in the estimate and not in the counter, which read as an estimate 28 % high on
 * a file whose samples were right. A thread that started and ended
 * between two counter events is never counted.
 *
 * <p>The aggregation runs on primitive-valued maps and the counters on a flat list
 * (G-1.3, G-1.8); the {@code java.util} maps the report exposes are built once at the end.
 */
public final class AllocationCollector implements JfrReader.Sink {

    public static final String SAMPLE = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_SAMPLE);
    public static final String IN_TLAB = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_IN_NEW_TLAB);
    public static final String OUTSIDE_TLAB = EventKinds.nameOf(EventKinds.OBJECT_ALLOCATION_OUTSIDE_TLAB);
    public static final String THREAD_STATISTICS = EventKinds.nameOf(EventKinds.THREAD_ALLOCATION_STATISTICS);

    public static final String THREAD_START = EventKinds.nameOf(EventKinds.THREAD_START);

    private static final Set<String> TYPES = Set.of(SAMPLE, IN_TLAB, OUTSIDE_TLAB, THREAD_STATISTICS, THREAD_START);

    /**
     * Per thread, at a stride of six: the smallest and largest counter seen, how often, when
     * the first and the last were read, and when the thread started ({@code Nulls.LONG_NULL}
     * when that is not in the file).
     */
    private static final int COUNTER_STRIDE = 6;
    private static final int COUNTER_MIN = 0;
    private static final int COUNTER_MAX = 1;
    private static final int COUNTER_SEEN = 2;
    private static final int COUNTER_FIRST_TIME = 3;
    private static final int COUNTER_LAST_TIME = 4;
    private static final int COUNTER_STARTED = 5;

    /**
     * The resolution the TLAB events' estimate over a counter's stretch is kept at: a thread's
     * weight per 100 ms, one entry per bucket it allocated in, so the cost follows threads and
     * time rather than events, which unthrottled run to millions a minute. A bucket counts when
     * its middle is inside the stretch, so each end of a stretch is off by at most 50 ms of
     * allocation. The samples are throttled (300 a second in {@code profile}) and keep their
     * own times: a short recording's stretch starts milliseconds into it.
     */
    static final long TLAB_BUCKET_NANOS = 100_000_000L;

    private final Accumulator sampled = new Accumulator(true, 1);
    /** TLAB events each stand for one buffer, so the first one carries no history. */
    private final Accumulator tlab = new Accumulator(false, TLAB_BUCKET_NANOS);
    private final ObjLongHashMap<ThreadRef> counterSlot = new ObjLongHashMap<>(256);
    private final LongList counters = new LongList(256 * COUNTER_STRIDE);
    private Interner interner = new Interner();
    private AllocationReport report;

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
            case EventKinds.OBJECT_ALLOCATION_SAMPLE -> {
                // Samples answer whenever there are any: the TLAB events' times are not needed.
                if (tlab.timed) {
                    tlab.untimed();
                }
                sampled.add(e, Events.longOr(e, Fields.WEIGHT, 0, interner), interner);
            }
            case EventKinds.OBJECT_ALLOCATION_IN_NEW_TLAB ->
                    tlab.add(e, Events.longOr(e, Fields.TLAB_SIZE, 0, interner), interner);
            case EventKinds.OBJECT_ALLOCATION_OUTSIDE_TLAB ->
                    tlab.add(e, Events.longOr(e, Fields.ALLOCATION_SIZE, 0, interner), interner);
            case EventKinds.THREAD_ALLOCATION_STATISTICS -> counter(e);
            case EventKinds.THREAD_START -> started(e);
            default -> {
            }
        }
    }

    /**
     * A {@code jdk.ObjectAllocationSample} whose fields are already read: what
     * {@link #accept(RecordedEvent, int)} does after {@link Events}, for tests that build the
     * samples by hand. {@code cls} may be {@code null}.
     */
    void sample(final ThreadRef thread, final long time, final long weight, final String cls, final Stack site) {
        sampled.add(thread, time, weight, cls, site);
    }

    /** A {@code jdk.ObjectAllocationInNewTLAB} whose fields are already read, for tests. */
    void tlab(final ThreadRef thread, final long time, final long size, final String cls, final Stack site) {
        tlab.add(thread, time, size, cls, site);
    }

    /**
     * The counter only grows, so the smallest value seen is the first reading and the largest
     * the last, and their times say which stretch the difference covers.
     */
    private void counter(@Transient final RecordedEvent e) {
        final ThreadRef thread = Events.thread(e, Fields.THREAD, interner);
        if (thread == null) {
            return;
        }
        final long allocated = Events.longOr(e, Fields.ALLOCATED, -1, interner);
        if (allocated < 0) {
            return;
        }
        counter(thread, Events.startNanos(e), allocated);
    }

    /** A counter reading whose fields are already read, for tests that build them by hand. */
    void counter(final ThreadRef thread, final long time, final long allocated) {
        final int base = counterSlot(thread);
        // A thread that stopped allocating reads the same value again: the stretch still runs
        // from the earliest reading of the smallest value to the latest of the largest.
        final long min = counters.getQuick(base + COUNTER_MIN);
        if (allocated < min || allocated == min && time < counters.getQuick(base + COUNTER_FIRST_TIME)) {
            counters.setQuick(base + COUNTER_MIN, allocated);
            counters.setQuick(base + COUNTER_FIRST_TIME, time);
        }
        final long max = counters.getQuick(base + COUNTER_MAX);
        if (allocated > max || allocated == max && time > counters.getQuick(base + COUNTER_LAST_TIME)) {
            counters.setQuick(base + COUNTER_MAX, allocated);
            counters.setQuick(base + COUNTER_LAST_TIME, time);
        }
        counters.setQuick(base + COUNTER_SEEN, counters.getQuick(base + COUNTER_SEEN) + 1);
    }

    /** A thread that started in the file: its counter was zero then. */
    private void started(@Transient final RecordedEvent e) {
        final ThreadRef thread = interner.thread(e);
        if (thread != null && !thread.isVirtual()) {
            started(thread, Events.startNanos(e));
        }
    }

    /** A {@code jdk.ThreadStart} whose fields are already read, for tests that build them by hand. */
    void started(final ThreadRef thread, final long time) {
        counters.setQuick(counterSlot(thread) + COUNTER_STARTED, time);
    }

    private int counterSlot(final ThreadRef thread) {
        final int index = counterSlot.keyIndex(thread);
        if (index < 0) {
            return (int) counterSlot.valueAtQuick(index);
        }
        final int base = counters.size();
        counters.add(Long.MAX_VALUE);
        counters.add(Long.MIN_VALUE);
        counters.add(0);
        counters.add(Nulls.LONG_NULL);
        counters.add(Nulls.LONG_NULL);
        counters.add(Nulls.LONG_NULL);
        counterSlot.putAt(index, thread, base);
        return base;
    }

    @Override
    public void finish(final RecordingInfo info) {
        // A recording with sampled events is answered from them even if every thread had only one.
        final Accumulator chosen = sampled.events > 0 ? sampled : tlab;
        final String source = sampled.events > 0 ? SAMPLE : IN_TLAB + " + " + OUTSIDE_TLAB;
        final ObjHashSet<ThreadRef> born = bornInFile(chosen);
        final Map<String, Long> counted = new HashMap<>();
        final Map<String, Long> estimated = new HashMap<>();
        counted(chosen, born, counted, estimated);
        if (!counted.isEmpty()) {
            long bound = 0;
            for (final long bytes : counted.values()) {
                bound += bytes;
            }
            sampled.dropHeavierThan(bound);
        }
        sampled.dropFirsts(born);
        report = new AllocationReport(info, source, chosen.total, chosen.samples, chosen.events, counted,
                toMap(chosen.byThread), toMap(chosen.byClass), toMap(chosen.bySite), toMaps(chosen.classByThread),
                toMaps(chosen.siteByThread), new AllocationReport.Support(toMap(chosen.countByThread),
                toMap(chosen.countByClass), toMap(chosen.countBySite)),
                new AllocationReport.Dropped(chosen.virtualFirsts, chosen.virtualFirstBytes),
                new AllocationReport.Dropped(chosen.carrierHistories, chosen.carrierHistoryBytes), estimated);
    }

    /**
     * The platform threads whose life began in the file: a {@code jdk.ThreadStart} no later than
     * the thread's first counter reading and its first sample. The JVM that starts a recording
     * writes one for {@code main} seconds after main has been running, sampled and counted; taken
     * at its word, main's start-up allocation was kept as the recording's and its counter ran
     * from zero over a stretch that ends before it begins.
     */
    private ObjHashSet<ThreadRef> bornInFile(final Accumulator chosen) {
        final ObjHashSet<ThreadRef> born = new ObjHashSet<>(64);
        for (int s = 0, n = counterSlot.slots(); s < n; s++) {
            if (!counterSlot.hasKeyAtSlot(s)) {
                continue;
            }
            final ThreadRef thread = counterSlot.keyAtSlot(s);
            final int base = (int) counterSlot.valueAtSlot(s);
            final long started = counters.getQuick(base + COUNTER_STARTED);
            final long firstReading = counters.getQuick(base + COUNTER_FIRST_TIME);
            if (started != Nulls.LONG_NULL && (firstReading == Nulls.LONG_NULL || started <= firstReading)
                    && started <= chosen.earliest(thread)) {
                born.add(thread);
            }
        }
        return born;
    }

    /**
     * Per thread name, what the JVM's counter grew by between its first reading, or the
     * thread's start, and its last reading, into {@code counted}; and what the samples the
     * estimate keeps say about that same stretch, into {@code estimated}. Called before
     * {@link Accumulator#dropFirsts}, which forgets the sample times.
     */
    private void counted(final Accumulator chosen, final ObjHashSet<ThreadRef> born, final Map<String, Long> counted,
                         final Map<String, Long> estimated) {
        for (int s = 0, n = counterSlot.slots(); s < n; s++) {
            if (!counterSlot.hasKeyAtSlot(s)) {
                continue;
            }
            final ThreadRef thread = counterSlot.keyAtSlot(s);
            final int base = (int) counterSlot.valueAtSlot(s);
            final long seen = counters.getQuick(base + COUNTER_SEEN);
            final boolean fromStart = born.contains(thread);
            if (seen == 0 || seen == 1 && !fromStart) {
                continue;
            }
            final long first = fromStart ? 0 : counters.getQuick(base + COUNTER_MIN);
            counted.merge(thread.name(), counters.getQuick(base + COUNTER_MAX) - first, Long::sum);
            estimated.merge(thread.name(), chosen.within(thread, fromStart,
                    fromStart ? counters.getQuick(base + COUNTER_STARTED) : counters.getQuick(base + COUNTER_FIRST_TIME),
                    counters.getQuick(base + COUNTER_LAST_TIME)), Long::sum);
        }
    }

    /** Available after {@link JfrReader#read}. */
    public AllocationReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }

    private static <K> Map<K, Long> toMap(final ObjLongHashMap<K> map) {
        final Map<K, Long> out = new HashMap<>(map.size() * 2);
        for (int s = 0, n = map.slots(); s < n; s++) {
            if (map.hasKeyAtSlot(s)) {
                out.put(map.keyAtSlot(s), map.valueAtSlot(s));
            }
        }
        return out;
    }

    private static <K> Map<String, Map<K, Long>> toMaps(final ObjObjHashMap<String, ObjLongHashMap<K>> maps) {
        final Map<String, Map<K, Long>> out = new HashMap<>(maps.size() * 2);
        for (int s = 0, n = maps.slots(); s < n; s++) {
            if (maps.hasKeyAtSlot(s)) {
                out.put(maps.keyAtSlot(s), toMap(maps.valueAtSlot(s)));
            }
        }
        return out;
    }

    private static final class Accumulator {
        private static final String NO_THREAD = "<no thread>";
        private static final String NO_CLASS = "?";
        static final int HEAVY_KEPT = 256;

        /** One virtual-thread sample kept in case it is carrier history, re-pointed when a heavier one replaces it. */
        private static final class Heavy {
            ThreadRef thread;
            long time;
            long bytes;
            String threadName;
            String cls;
            Stack site;
        }

        long total;
        /** Samples in the totals. */
        long samples;
        /** Events seen, discarded or not. */
        long events;
        /** Virtual threads' first samples taken out of the totals, and their weight. */
        long virtualFirsts;
        long virtualFirstBytes;
        /** Virtual threads' later samples taken out as carrier history ({@link #dropHeavierThan}), and their weight. */
        long carrierHistories;
        long carrierHistoryBytes;
        /**
         * The heaviest virtual-thread samples seen, at most {@link #HEAVY_KEPT}: only a carrier's first sample in the
         * recording can outweigh the bound, so there are at most as many as carriers, and a scheduler has at most 256.
         */
        private final Heavy[] heavy = new Heavy[HEAVY_KEPT];
        private int heavyCount;
        /** Where the lightest of {@link #heavy} is, the one a heavier sample replaces. */
        private int heavyMin;
        final ObjLongHashMap<String> byThread = new ObjLongHashMap<>(64);
        final ObjLongHashMap<String> byClass = new ObjLongHashMap<>(1024);
        final ObjLongHashMap<Stack> bySite = new ObjLongHashMap<>(4096);
        /** Samples behind each of the three, so a row can say how much evidence it rests on. */
        final ObjLongHashMap<String> countByThread = new ObjLongHashMap<>(64);
        final ObjLongHashMap<String> countByClass = new ObjLongHashMap<>(1024);
        final ObjLongHashMap<Stack> countBySite = new ObjLongHashMap<>(4096);
        final ObjObjHashMap<String, ObjLongHashMap<String>> classByThread = new ObjObjHashMap<>(64);
        final ObjObjHashMap<String, ObjLongHashMap<Stack>> siteByThread = new ObjObjHashMap<>(64);
        /** Whether a thread's earliest sample is history from before the recording, to be dropped. */
        private final boolean dropFirstPerThread;
        /** Whether events are kept per bucket of time; turned off for the family that will not answer. */
        boolean timed = true;
        /**
         * Per thread, the earliest sample seen (delivery is file order, not time order), and for a
         * platform thread every sample's time and weight.
         */
        private final ObjObjHashMap<ThreadRef, First> firsts = new ObjObjHashMap<>(64);

        /** One thread's earliest sample, re-pointed when an earlier one turns up (G-3.1). */
        private static final class First {
            long time;
            long bytes;
            String threadName;
            String cls;
            Stack site;
            /**
             * A platform thread's weight per bucket of time, as (bucket, weight) pairs, a bucket
             * merged into the last pair when it is the same, for the estimate over its counter's
             * stretch. {@code null} for a virtual thread, which has no counter.
             */
            LongList buckets;

            First of(final long time, final long bytes, final String threadName, final String cls, final Stack site) {
                this.time = time;
                this.bytes = bytes;
                this.threadName = threadName;
                this.cls = cls;
                this.site = site;
                return this;
            }
        }

        /** How wide a bucket of {@link First#buckets} is: 1 keeps every sample's own time. */
        private final long bucketNanos;

        Accumulator(final boolean dropFirstPerThread, final long bucketNanos) {
            this.dropFirstPerThread = dropFirstPerThread;
            this.bucketNanos = bucketNanos;
        }

        void add(@Transient final RecordedEvent e, final long bytes, final Interner interner) {
            if (bytes <= 0) {
                return;
            }
            // The timestamp costs an Instant: the counters are set against the samples of their own stretch.
            add(interner.thread(e), timed ? Events.startNanos(e) : Nulls.LONG_NULL, bytes,
                    Events.className(e, Fields.OBJECT_CLASS, interner), Events.stack(e, interner));
        }

        /** One event, its fields already read; {@code thread} and {@code cls} may be {@code null}. */
        void add(final ThreadRef thread, final long time, final long bytes, final String cls, final Stack site) {
            if (bytes <= 0) {
                return;
            }
            final String threadName = thread == null ? NO_THREAD : thread.name();
            final String className = cls == null ? NO_CLASS : cls;
            add(bytes, threadName, className, site);
            if (thread != null && thread.isVirtual()) {
                keepIfHeavy(thread, time, bytes, threadName, className, site);
            }
            if (thread != null) {
                final int index = firsts.keyIndex(thread);
                final First f;
                if (index >= 0) {
                    f = firsts.putAt(index, thread, new First().of(time, bytes, threadName, className, site));
                    f.buckets = thread.isVirtual() || !timed ? null : new LongList(4);
                } else {
                    f = firsts.valueAtQuick(index);
                    if (time < f.time) {
                        f.of(time, bytes, threadName, className, site);
                    }
                }
                if (f.buckets != null) {
                    final long bucket = Math.floorDiv(time, bucketNanos);
                    final int n = f.buckets.size();
                    if (n > 0 && f.buckets.getQuick(n - 2) == bucket) {
                        f.buckets.setQuick(n - 1, f.buckets.getQuick(n - 1) + bytes);
                    } else {
                        f.buckets.add(bucket);
                        f.buckets.add(bytes);
                    }
                }
            }
        }

        private void keepIfHeavy(final ThreadRef thread, final long time, final long bytes, final String threadName,
                                 final String cls, final Stack site) {
            final int at;
            if (heavyCount < HEAVY_KEPT) {
                at = heavyCount++;
                heavy[at] = new Heavy();
            } else if (bytes > heavy[heavyMin].bytes) {
                at = heavyMin;
            } else {
                return;
            }
            final Heavy h = heavy[at];
            h.thread = thread;
            h.time = time;
            h.bytes = bytes;
            h.threadName = threadName;
            h.cls = cls;
            h.site = site;
            if (heavyCount == HEAVY_KEPT) {
                // a full array only changes its lightest entry, so the scan runs when a sample beats it
                int min = 0;
                for (int i = 1; i < HEAVY_KEPT; i++) {
                    if (heavy[i].bytes < heavy[min].bytes) {
                        min = i;
                    }
                }
                heavyMin = min;
            }
        }

        /**
         * Takes out of the totals every virtual-thread sample, other than its thread's first, that weighs more than
         * {@code bound}, every thread's counted allocation in the recording: the carrier it ran on had not been sampled
         * in the recording, so the weight reaches back before it. A thread's first sample is left to
         * {@link #dropFirsts}, which counts it as such. Called before it, while the firsts are known.
         */
        void dropHeavierThan(final long bound) {
            for (int i = 0; i < heavyCount; i++) {
                final Heavy h = heavy[i];
                if (h.bytes <= bound) {
                    continue;
                }
                final First f = firsts.get(h.thread);
                if (f != null && f.time == h.time && f.bytes == h.bytes) {
                    continue;
                }
                carrierHistories++;
                carrierHistoryBytes += h.bytes;
                takeOut(h.threadName, h.cls, h.site, h.bytes);
            }
            heavyCount = 0;
        }

        private void add(final long bytes, final String threadName, final String cls, final Stack site) {
            total += bytes;
            samples++;
            events++;
            byThread.increment(threadName, bytes);
            byClass.increment(cls, bytes);
            bySite.increment(site, bytes);
            countByThread.increment(threadName, 1);
            countByClass.increment(cls, 1);
            countBySite.increment(site, 1);
            perThread(classByThread, threadName).increment(cls, bytes);
            perThread(siteByThread, threadName).increment(site, bytes);
        }

        private static <K> ObjLongHashMap<K> perThread(final ObjObjHashMap<String, ObjLongHashMap<K>> maps, final String thread) {
            final int index = maps.keyIndex(thread);
            return index < 0 ? maps.valueAtQuick(index) : maps.putAt(index, thread, new ObjLongHashMap<>(64));
        }

        /** Stops keeping time, and forgets what was kept. */
        void untimed() {
            timed = false;
            for (int s = 0, n = firsts.slots(); s < n; s++) {
                if (firsts.hasKeyAtSlot(s)) {
                    firsts.valueAtSlot(s).buckets = null;
                }
            }
        }

        /** The time of the thread's earliest sample; {@code Long.MAX_VALUE} when it has none. */
        long earliest(final ThreadRef thread) {
            final First f = firsts.get(thread);
            return f == null ? Long.MAX_VALUE : f.time;
        }

        /**
         * The weight of the thread's samples the estimate keeps that fall in {@code [from, to]}:
         * all of them for a thread born in the file or for events with no history, all but its
         * earliest otherwise.
         */
        long within(final ThreadRef thread, final boolean bornInFile, final long from, final long to) {
            final First f = firsts.get(thread);
            if (f == null || f.buckets == null) {
                return 0;
            }
            long sum = 0;
            for (int i = 0, n = f.buckets.size(); i < n; i += 2) {
                if (inside(f.buckets.getQuick(i), from, to)) {
                    sum += f.buckets.getQuick(i + 1);
                }
            }
            // The earliest sample is not in the estimate unless the thread was born in the file.
            if (dropFirstPerThread && !bornInFile && inside(Math.floorDiv(f.time, bucketNanos), from, to)) {
                sum -= f.bytes;
            }
            return sum;
        }

        private boolean inside(final long bucket, final long from, final long to) {
            final long middle = bucket * bucketNanos + bucketNanos / 2;
            return middle >= from && middle <= to;
        }

        /**
         * Takes every thread's first sample back out of the totals, counting the virtual ones,
         * except a platform thread's that was {@code born} in the file: its history is all in the window.
         */
        void dropFirsts(final ObjHashSet<ThreadRef> born) {
            if (!dropFirstPerThread) {
                return;
            }
            for (int s = 0, n = firsts.slots(); s < n; s++) {
                if (!firsts.hasKeyAtSlot(s) || born.contains(firsts.keyAtSlot(s))) {
                    continue;
                }
                final First f = firsts.valueAtSlot(s);
                if (firsts.keyAtSlot(s).isVirtual()) {
                    virtualFirsts++;
                    virtualFirstBytes += f.bytes;
                }
                takeOut(f.threadName, f.cls, f.site, f.bytes);
            }
            firsts.clear();
        }

        /** Takes one sample back out of the totals and every breakdown. */
        private void takeOut(final String threadName, final String cls, final Stack site, final long bytes) {
            total -= bytes;
            samples--;
            subtract(byThread, threadName, bytes);
            subtract(byClass, cls, bytes);
            subtract(bySite, site, bytes);
            subtract(countByThread, threadName, 1);
            subtract(countByClass, cls, 1);
            subtract(countBySite, site, 1);
            // Two threads can share a name (a pool that recycles them); the per-name maps may
            // already be gone after the first of them.
            subtract(classByThread.get(threadName), cls, bytes);
            subtract(siteByThread.get(threadName), site, bytes);
            if (byThread.excludes(threadName)) {
                classByThread.remove(threadName);
                siteByThread.remove(threadName);
            }
        }

        private static <K> void subtract(final ObjLongHashMap<K> map, final K key, final long bytes) {
            if (map == null) {
                return;
            }
            final int index = map.keyIndex(key);
            final long left = (index < 0 ? map.valueAtQuick(index) : 0) - bytes;
            if (left > 0) {
                if (index < 0) {
                    map.put(key, left);
                } else {
                    map.putAt(index, key, left);
                }
            } else if (index < 0) {
                map.removeAt(index);
            }
        }
    }
}
