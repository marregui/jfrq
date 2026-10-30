// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.health;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.coll.LongList;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.health.HealthReport.ClassRow;
import dev.jfrq.core.health.HealthReport.Finding;
import dev.jfrq.core.health.HealthReport.Gc;
import dev.jfrq.core.health.HealthReport.Series;
import dev.jfrq.core.health.HealthReport.SiteRow;
import dev.jfrq.core.health.HealthReport.Threads;
import dev.jfrq.core.health.HealthReport.Throwables;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Durations;
import dev.jfrq.core.util.Sorts;

/**
 * Reads what the other commands leave: the garbage collector's own events, the JVM's
 * once-a-second statistics (CPU, resident set, threads, throwables) and the throwables
 * created, then builds a {@link HealthReport}.
 *
 * <p>Every source but the throwables arrives about once a second or once a collection, so
 * the per-event work is an append to a flat list. The throwables can arrive by the
 * thousand; each is one probe for its class and one for its stack, whose site row is
 * resolved once per distinct stack (G-2.2).
 */
public final class HealthCollector implements JfrReader.Sink {

    private static final Set<String> TYPES = EventKinds.names(EventKinds.GARBAGE_COLLECTION,
            EventKinds.OLD_GARBAGE_COLLECTION, EventKinds.GC_HEAP_SUMMARY,
            EventKinds.GC_CONFIGURATION, EventKinds.GC_HEAP_CONFIGURATION, EventKinds.CPU_LOAD,
            EventKinds.JAVA_THREAD_STATISTICS, EventKinds.RESIDENT_SET_SIZE, EventKinds.EXCEPTION_STATISTICS,
            EventKinds.JAVA_EXCEPTION_THROW, EventKinds.JAVA_ERROR_THROW, EventKinds.EVACUATION_FAILED,
            EventKinds.THREAD_CPU_LOAD, EventKinds.THREAD_START, EventKinds.NATIVE_MEMORY_USAGE,
            EventKinds.NATIVE_MEMORY_USAGE_TOTAL, EventKinds.OS_INFORMATION, EventKinds.JVM_INFORMATION);

    /** Collectors whose every collection is of the whole heap, in a pause. */
    static final Set<String> FULL_COLLECTORS = Set.of("G1Full", "SerialOld", "ParallelOld");
    private static final String HUMONGOUS = "G1 Humongous Allocation";
    private static final String METASPACE = "Metadata GC Threshold";
    private static final String SYSTEM_GC = "System.gc()";
    private static final String OUT_OF_MEMORY = "java.lang.OutOfMemoryError";
    private static final String ERROR = "java.lang.Error";

    private final ObjLongHashMap<String> collections = new ObjLongHashMap<>(8);
    private final ObjLongHashMap<String> causes = new ObjLongHashMap<>(8);
    /** Each collection's longest pause, and when it started, checked against the pause target once it is known. */
    private final LongList longestPauses = new LongList(64);
    private final LongList pauseTimes = new LongList(64);
    /** When each {@link Finding.Kind} first and last happened, epoch nanoseconds, by ordinal. */
    private final long[] first = new long[Finding.Kind.values().length];
    private final long[] last = new long[Finding.Kind.values().length];
    private long oldCycles;
    /** The collection of each {@code jdk.EvacuationFailed}: one collection can report several. */
    private final LongList evacuationFailures = new LongList(8);
    private long pauseNanos;
    private int gcTimeRatio = Nulls.INT_NULL;
    private long pauseTargetNanos = Nulls.LONG_NULL;
    private long maxHeapBytes = Nulls.LONG_NULL;

    private final Points heapAfterGc = new Points();
    private final Points residentSet = new Points();
    private final Points liveThreads = new Points();
    private final Points jvmCpu = new Points();
    private final Points machineCpu = new Points();
    private final Points threadsStarted = new Points();
    private final Points throwablesCreated = new Points();
    private long peakThreads = Nulls.LONG_NULL;
    private final ThreadCpu threadCpu = new ThreadCpu();
    /** Native memory committed, per NMT category, and in total. */
    private final ObjObjHashMap<String, Points> nativeMemory = new ObjObjHashMap<>(32);
    private final Points nativeTotal = new Points();
    /** The operating system, as {@code jdk.OSInformation} names it; null until read. */
    private String osVersion;
    /** The JVM, as {@code jdk.JVMInformation} names it; null until read. */
    private String jvmVersion;

    /** Per throwable class, when each was created, and an example message. */
    private final ObjObjHashMap<String, Thrown> byClass = new ObjObjHashMap<>(64);
    /** Site rows by site and class, with the first stack seen at each. */
    private final ObjObjHashMap<String, Site> bySite = new ObjObjHashMap<>(64);
    /** A stack's site row, resolved once per distinct stack: the construction frames name the class. */
    private final ObjObjHashMap<Stack, Site> sites = new ObjObjHashMap<>(256);
    private final ObjLongHashMap<String> errors = new ObjLongHashMap<>(8);
    /** When each {@code jdk.JavaErrorThrow} happened: each is one throwable the running total counted twice. */
    private final LongList errorTimes = new LongList(8);
    private long samples;

    private Interner interner = new Interner();
    private HealthReport report;

    public HealthCollector() {
        Arrays.fill(first, Nulls.LONG_NULL);
        Arrays.fill(last, Nulls.LONG_NULL);
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
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
            case EventKinds.GARBAGE_COLLECTION -> collection(e);
            case EventKinds.OLD_GARBAGE_COLLECTION -> oldCycles++;
            case EventKinds.EVACUATION_FAILED -> {
                evacuationFailures.add(Events.longOr(e, Fields.GC_ID, Nulls.LONG_NULL, interner));
                when(Finding.Kind.EVACUATION_FAILED, Events.startNanos(e));
            }
            case EventKinds.GC_HEAP_SUMMARY -> {
                if ("After GC".equals(Events.stringOr(e, Fields.WHEN, "", interner))) {
                    heapAfterGc.add(Events.startNanos(e), Events.longOr(e, Fields.HEAP_USED, 0, interner));
                }
            }
            case EventKinds.GC_CONFIGURATION -> {
                gcTimeRatio = (int) Events.longOr(e, Fields.GC_TIME_RATIO, Nulls.INT_NULL, interner);
                final long millis = Events.longOr(e, Fields.PAUSE_TARGET, Nulls.LONG_NULL, interner);
                pauseTargetNanos = millis == Nulls.LONG_NULL || millis <= 0 ? Nulls.LONG_NULL : millis * 1_000_000L;
            }
            case EventKinds.GC_HEAP_CONFIGURATION -> maxHeapBytes = Events.longOr(e, Fields.MAX_SIZE, Nulls.LONG_NULL, interner);
            case EventKinds.CPU_LOAD -> {
                final long t = Events.startNanos(e);
                jvmCpu.add(t, Events.doubleOr(e, Fields.JVM_USER, 0, interner) + Events.doubleOr(e, Fields.JVM_SYSTEM, 0, interner));
                machineCpu.add(t, Events.doubleOr(e, Fields.MACHINE_TOTAL, 0, interner));
            }
            case EventKinds.JAVA_THREAD_STATISTICS -> {
                final long t = Events.startNanos(e);
                liveThreads.add(t, Events.longOr(e, Fields.ACTIVE_COUNT, 0, interner));
                threadsStarted.add(t, Events.longOr(e, Fields.ACCUMULATED_COUNT, 0, interner));
                peakThreads = Math.max(peakThreads, Events.longOr(e, Fields.PEAK_COUNT, Nulls.LONG_NULL, interner));
            }
            case EventKinds.RESIDENT_SET_SIZE -> residentSet.add(Events.startNanos(e), Events.longOr(e, Fields.SIZE, 0, interner));
            case EventKinds.EXCEPTION_STATISTICS ->
                    throwablesCreated.add(Events.startNanos(e), Events.longOr(e, Fields.THROWABLES, 0, interner));
            case EventKinds.JAVA_EXCEPTION_THROW -> throwable(e);
            case EventKinds.THREAD_CPU_LOAD -> {
                final ThreadRef thread = Events.thread(e, Fields.EVENT_THREAD, interner);
                if (thread != null) {
                    threadCpu.add(thread, Events.startNanos(e), Events.doubleOr(e, Fields.USER, 0, interner)
                            + Events.doubleOr(e, Fields.SYSTEM, 0, interner));
                }
            }
            case EventKinds.THREAD_START -> {
                // When a thread's first CPU reading began: see ThreadCpu.
                final ThreadRef thread = Events.thread(e, Fields.THREAD, interner);
                if (thread != null) {
                    threadCpu.start(thread, Events.startNanos(e), Events.isAttach(e,
                            Events.thread(e, Fields.PARENT_THREAD, interner), Events.stack(e, interner), interner));
                }
            }
            case EventKinds.NATIVE_MEMORY_USAGE -> {
                final String category = Events.stringOr(e, Fields.TYPE, "?", interner);
                final int index = nativeMemory.keyIndex(category);
                final Points points = index < 0 ? nativeMemory.valueAtQuick(index)
                        : nativeMemory.putAt(index, category, new Points());
                points.add(Events.startNanos(e), Events.longOr(e, Fields.COMMITTED, 0, interner));
            }
            case EventKinds.NATIVE_MEMORY_USAGE_TOTAL ->
                    nativeTotal.add(Events.startNanos(e), Events.longOr(e, Fields.COMMITTED, 0, interner));
            case EventKinds.OS_INFORMATION -> {
                if (osVersion == null) {
                    osVersion = Events.stringOr(e, Fields.OS_VERSION, null, interner);
                }
            }
            case EventKinds.JVM_INFORMATION -> {
                if (jvmVersion == null) {
                    jvmVersion = Events.stringOr(e, Fields.JVM_VERSION, null, interner);
                }
            }
            case EventKinds.JAVA_ERROR_THROW -> {
                final String cls = Events.className(e, Fields.THROWN_CLASS, interner);
                errors.increment(cls == null ? "?" : cls, 1);
                errorTimes.add(Events.startNanos(e));
            }
            default -> {
            }
        }
    }

    /**
     * One collection. A G1 concurrent cycle ({@code G1Old}) is reported with the cause of the
     * young pause that started it, a millisecond earlier: counting both would count every
     * humongous or metaspace trigger twice, so the cycle counts as a collection and a pause
     * but not as a cause.
     */
    private void collection(@Transient final RecordedEvent e) {
        final long t = Events.startNanos(e);
        final String name = Events.stringOr(e, Fields.NAME, "?", interner);
        collections.increment(name, 1);
        if (!HealthReport.CONCURRENT_CYCLE.equals(name)) {
            final String cause = Events.stringOr(e, Fields.CAUSE, "?", interner);
            causes.increment(cause, 1);
            switch (cause) {
                case HUMONGOUS -> when(Finding.Kind.HUMONGOUS_ALLOCATION, t);
                case METASPACE -> when(Finding.Kind.METASPACE_GC, t);
                case SYSTEM_GC -> when(Finding.Kind.SYSTEM_GC, t);
                default -> {
                }
            }
        }
        if (FULL_COLLECTORS.contains(name)) {
            when(Finding.Kind.FULL_GC, t);
        }
        pauseNanos += Events.durationNanosOr(e, Fields.SUM_OF_PAUSES, 0, interner);
        longestPauses.add(Events.durationNanosOr(e, Fields.LONGEST_PAUSE, 0, interner));
        pauseTimes.add(t);
    }

    private void when(final Finding.Kind kind, final long t) {
        final int k = kind.ordinal();
        first[k] = first[k] == Nulls.LONG_NULL ? t : Math.min(first[k], t);
        last[k] = Math.max(last[k], t);
    }

    /**
     * One {@code jdk.JavaExceptionThrow}. The JDK emits it from {@code Throwable}'s
     * constructors and, for an {@code Error}, once more from {@code Error}'s: the second one
     * has {@code Error.<init>} on top and is skipped, so every throwable is one event. An
     * {@code OutOfMemoryError} is here only when Java code constructed it (direct buffer
     * memory): the JVM's own, for the heap and metaspace, is never recorded.
     */
    private void throwable(@Transient final RecordedEvent e) {
        final Stack stack = Events.stack(e, interner);
        final Frame top = stack.topOrNull();
        if (top != null && top.type().equals(ERROR) && top.method().equals("<init>")) {
            return;
        }
        samples++;
        final String raw = Events.className(e, Fields.THROWN_CLASS, interner);
        final String cls = raw == null ? "?" : raw;
        final long time = Events.startNanos(e);
        if (cls.equals(OUT_OF_MEMORY)) {
            when(Finding.Kind.OUT_OF_MEMORY, time);
        }
        final int index = byClass.keyIndex(cls);
        if (index < 0) {
            byClass.valueAtQuick(index).times.add(time);
        } else {
            final String message = Events.stringOr(e, Fields.MESSAGE, null, interner);
            byClass.putAt(index, cls, new Thrown(time, message == null || message.length() <= MESSAGE_CHARS ? message
                    : message.substring(0, MESSAGE_CHARS) + "…"));
        }
        site(stack, cls).samples++;
    }

    /**
     * One throwable class so far. A burst at start-up and a steady trickle differ only in the
     * times: the first and last alone do not tell them apart when one straggler comes late.
     */
    private static final class Thrown {
        /** Creation times, in file order until {@link #classRows} sorts them. */
        final LongList times = new LongList(16);
        /** The first event's message, as an example; {@code null} when it had none. */
        final String message;

        Thrown(final long time, final String message) {
            times.add(time);
            this.message = message;
        }
    }

    /** The first characters of a message kept as its class's example. */
    static final int MESSAGE_CHARS = 120;

    /**
     * Where a throwable was made. The top of its stack is its own construction, the same for
     * every throwable of its class: {@code Throwable.<init>}, the superclass constructors (an
     * application's own base exception among them), the class's constructor, and sometimes a
     * static factory of the class. The site is the first frame below all that, outside the
     * JDK; the first frame there at all when every one is the JDK's. The stack kept for it
     * starts there too, so it shows the code that made the throwable, not how a throwable is
     * built. Resolved once per distinct stack: the construction frames name the class.
     */
    private Site site(final Stack stack, final String cls) {
        final int index = sites.keyIndex(stack);
        if (index < 0) {
            return sites.valueAtQuick(index);
        }
        final int depth = stack.depth();
        int from = 0;
        for (int i = 0; i < depth; i++) {
            final Frame f = stack.frameQuick(i);
            if (f.type().equals(cls) && f.method().equals("<init>")) {
                from = i + 1;
                break;
            }
        }
        while (from < depth && stack.frameQuick(from).type().equals(cls)) {
            from++;
        }
        int site = -1;
        for (int i = from; i < depth; i++) {
            if (!stack.frameQuick(i).isJdk()) {
                site = i;
                break;
            }
        }
        if (site < 0 && from < depth) {
            site = from;
        }
        final String name = site < 0 ? "<no stack>" : stack.frameQuick(site).stableName();
        // Stacks that differ above the site, or in their line numbers, are one row.
        final String key = name + "\u0000" + cls;
        final int row = bySite.keyIndex(key);
        final Site found = row < 0 ? bySite.valueAtQuick(row) : bySite.putAt(row, key, new Site(name, cls,
                site < 0 ? stack : new Stack(stack.frames().subList(from, depth), stack.isTruncated())));
        return sites.putAt(index, stack, found);
    }

    /** One site of one class. */
    private static final class Site {
        final String name;
        final String className;
        final Stack stack;
        long samples;

        Site(final String name, final String className, final Stack stack) {
            this.name = name;
            this.className = className;
            this.stack = stack;
        }
    }

    @Override
    public void finish(final RecordingInfo info) {
        final Map<String, Long> collectionsMap = sorted(collections);
        final Map<String, Long> causesMap = sorted(causes);
        final Gc gc = new Gc(collectionsMap, causesMap, oldCycles, pauseNanos, longest(), gcTimeRatio,
                pauseTargetNanos, maxHeapBytes);
        final List<Series> trends = new ArrayList<>(5);
        heapAfterGc.series(HealthReport.HEAP_AFTER_GC, Series.Unit.BYTES, trends);
        residentSet.series(HealthReport.RESIDENT_SET, Series.Unit.BYTES, trends);
        liveThreads.series(HealthReport.LIVE_THREADS, Series.Unit.COUNT, trends);
        jvmCpu.series(HealthReport.JVM_CPU, Series.Unit.FRACTION, trends);
        machineCpu.series("Machine CPU", Series.Unit.FRACTION, trends);
        final Threads threads = new Threads(threadsStarted.span(), peakThreads);
        final Throwables throwables = new Throwables(created(), throwablesCreated.duration(), samples,
                info.throttle(EventKinds.nameOf(EventKinds.JAVA_EXCEPTION_THROW)).orElse(null), classRows(),
                siteRows(), sorted(errors));
        final ThreadCpu.Result cpu = threadCpu.result(info);
        final List<String> warnings = new ArrayList<>(1);
        final String jvmCpuWarning = jvmCpuWarning(trends, cpu, osVersion, jvmVersion);
        if (jvmCpuWarning != null) {
            warnings.add(jvmCpuWarning);
        }
        report = new HealthReport(info, findings(info, gc, throwables), gc, trends, threads, throwables, cpu,
                nativeMemory(), warnings);
    }

    /**
     * Native memory as NMT committed it, the total first and then each category, largest at the
     * end of the window first; empty without NMT ({@code -XX:NativeMemoryTracking}), which is the
     * only way the JVM writes these events.
     */
    private List<Series> nativeMemory() {
        final List<Series> out = new ArrayList<>(nativeMemory.size() + 1);
        nativeTotal.series("Total", Series.Unit.BYTES, out);
        final List<Series> categories = new ArrayList<>(nativeMemory.size());
        for (int s = 0, n = nativeMemory.slots(); s < n; s++) {
            if (nativeMemory.hasKeyAtSlot(s)) {
                nativeMemory.valueAtSlot(s).series(nativeMemory.keyAtSlot(s), Series.Unit.BYTES, categories);
            }
        }
        categories.sort(Comparator.comparingDouble(Series::end).reversed().thenComparing(Series::name));
        out.addAll(categories);
        return out;
    }

    /**
     * What makes the {@code JVM CPU} trend wrong, or null. First a JVM known to write it wrong:
     * JDK-8326446, where on Apple silicon the JVM took the task's CPU times, which are 24 MHz
     * Mach ticks, for nanoseconds, so its user and system figures are 3/125 of the truth (fixed
     * in 17.0.13, 21.0.4, 22.0.2 and 23). Three busy threads on 12 cores read 0.60 % on
     * 21.0.2, 21.0.3 and 22.0.1 and 24.8 % on 25.0.4, the 25 % they used. The trend is
     * labelled, not corrected: the factor is the JDK's arithmetic, not a reading.
     *
     * <p>Otherwise, a process uses at least the CPU its Java threads use, so a JVM total below
     * theirs is wrong: the JDK 21.0.3 JVMs of one macOS soak, in 67 recordings (killed nodes'
     * readable chunks among them), averaged 0.02 % to 0.45 % of their CPUs in
     * {@code jdk.CPULoad} (no reading above 1.02 %) while {@code ps} saw them at 150 % to 236 %
     * of a core, and their threads' own readings summed to 0.8 % to 16.9 % (JDK-8326446 again,
     * found later). The margin (half again, plus a point) keeps two figures read over slightly
     * different stretches from tripping it: on the 22 JDK 25 recordings of the same soak the
     * threads read 0.28 to 1.03 times the JVM's figure.
     *
     * <p>The two compare only where they divide by the same count. {@code jdk.ThreadCPULoad}
     * divides by the JVM's active processor count everywhere; {@code jdk.CPULoad}'s JVM figure
     * does too on macOS, but on Linux it divides by the host's CPUs ({@code /proc/stat}), so a
     * JVM held to fewer CPUs reads lower there than its threads with nothing wrong. The JDK 25
     * sources show both; no other system was checked. So that comparison is made only for a
     * recording whose {@code jdk.OSInformation} names Darwin.
     */
    static String jvmCpuWarning(final List<Series> trends, final ThreadCpu.Result cpu, final String osVersion,
            final String jvmVersion) {
        Series jvm = null;
        for (final Series s : trends) {
            if (s.name().equals(HealthReport.JVM_CPU)) {
                jvm = s;
            }
        }
        if (jvm == null) {
            return null;
        }
        if (cpuInTicks(jvmVersion)) {
            return String.format(Locale.ROOT, "JVM CPU (jdk.CPULoad) averages %s of the JVM's CPUs, 3/125 of what it "
                    + "used: this JVM, a JDK before 17.0.13, 21.0.4, 22.0.2 and 23 on Apple silicon, reads its CPU "
                    + "time in 24 MHz ticks as nanoseconds (JDK-8326446); times 125/3 the average is %s",
                    jvm.format(jvm.mean()), jvm.format(jvm.mean() * 125 / 3));
        }
        if (cpu.isKnown() && osVersion != null && osVersion.contains("Darwin")
                && cpu.share() > jvm.mean() * 1.5 + 0.01) {
            return String.format(Locale.ROOT, "JVM CPU (jdk.CPULoad) averages %s of the JVM's CPUs, below the %s its "
                    + "own Java threads used (jdk.ThreadCPULoad): the JVM's figure is wrong in this recording; the "
                    + "threads' figure is a floor, as it leaves out the threads that are not Java threads (the "
                    + "collector's)", jvm.format(jvm.mean()), jvm.format(cpu.share()));
        }
        return null;
    }

    /**
     * Whether {@code jvmVersion}, {@code jdk.JVMInformation}'s
     * {@code "... for bsd-aarch64 JRE (21.0.3+7-LTS-152), ..."}, is a JVM with JDK-8326446:
     * macOS on Apple silicon, before 17.0.13, 21.0.4, 22.0.2 or 23. The bug is as old as the
     * event, so every earlier release has it. A version it cannot read is not one.
     */
    static boolean cpuInTicks(final String jvmVersion) {
        final String marker = "for bsd-aarch64 JRE (";
        final int at = jvmVersion == null ? -1 : jvmVersion.indexOf(marker);
        if (at < 0) {
            return false;
        }
        final int from = at + marker.length();
        int to = from;
        while (to < jvmVersion.length() && (Character.isDigit(jvmVersion.charAt(to)) || jvmVersion.charAt(to) == '.')) {
            to++;
        }
        final String[] parts = jvmVersion.substring(from, to).split("\\.");
        final int feature;
        final int update;
        try {
            feature = Integer.parseInt(parts[0]);
            update = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        } catch (final NumberFormatException e) {
            return false;
        }
        return switch (feature) {
            case 17 -> update < 13;
            case 21 -> update < 4;
            case 22 -> update < 2;
            default -> feature < 23;
        };
    }

    /**
     * The throwables created between the first and last {@code jdk.ExceptionStatistics}
     * reading. The JDK's running total counts an {@code Error} twice, once in
     * {@code Throwable}'s constructor and once in {@code Error}'s ({@code OutOfMemoryError}
     * excepted, which the second skips), and {@code jdk.JavaErrorThrow} is emitted by the
     * second: one fewer for each inside the stretch.
     */
    private long created() {
        final long span = throwablesCreated.span();
        if (span == Nulls.LONG_NULL) {
            return Nulls.LONG_NULL;
        }
        final long from = throwablesCreated.firstTime();
        final long to = throwablesCreated.lastTime();
        long twice = 0;
        for (int i = 0, n = errorTimes.size(); i < n; i++) {
            final long t = errorTimes.getQuick(i);
            twice += t > from && t <= to ? 1 : 0;
        }
        return span - twice;
    }

    private long longest() {
        long max = 0;
        for (int i = 0, n = longestPauses.size(); i < n; i++) {
            max = Math.max(max, longestPauses.getQuick(i));
        }
        return max;
    }

    /** The conditions the JVM reports about itself, in {@link Finding.Kind} order. */
    private List<Finding> findings(final RecordingInfo info, final Gc gc, final Throwables throwables) {
        final List<Finding> out = new ArrayList<>();
        final Thrown thrownOom = byClass.get(OUT_OF_MEMORY);
        final long oom = thrownOom == null ? 0 : thrownOom.times.size();
        if (oom > 0) {
            final String message = thrownOom.message;
            add(out, info, Finding.Kind.OUT_OF_MEMORY, oom, oom + " OutOfMemoryError created", (message == null ? ""
                    : "\"" + message + "\" ") + "(JFR records one only when Java code constructs it, as for direct "
                    + "buffer memory; the JVM's own, for the heap or metaspace, never)");
        }
        final long failed = distinct(evacuationFailures);
        if (failed > 0) {
            add(out, info, Finding.Kind.EVACUATION_FAILED, failed, failed + (failed == 1 ? " collection" : " collections")
                    + " failed to evacuate", "live objects had nowhere to be copied to, so the collector left them in "
                    + "place: a heap this close to full is the step before a full collection and an OutOfMemoryError");
        }
        long full = 0;
        final StringBuilder fullNames = new StringBuilder();
        for (final Map.Entry<String, Long> c : gc.collections().entrySet()) {
            if (FULL_COLLECTORS.contains(c.getKey())) {
                full += c.getValue();
                fullNames.append(fullNames.isEmpty() ? "" : ", ").append(c.getKey()).append(' ').append(c.getValue());
            }
        }
        if (full > 0) {
            add(out, info, Finding.Kind.FULL_GC, full, full + (full == 1 ? " full collection" : " full collections")
                    + " (" + fullNames + ")", "the whole heap collected in one pause, usually because the old "
                    + "generation filled faster than the collector could reclaim it");
        }
        if (gc.gcTimeRatio() != Nulls.INT_NULL && info.span().duration() > 0) {
            final double share = (double) gc.pauseNanos() / info.span().duration();
            final double goal = 1.0 / (1 + gc.gcTimeRatio());
            if (share > goal) {
                out.add(new Finding(Finding.Kind.GC_TIME_OVER_GOAL, gc.count(), Nulls.LONG_NULL, Nulls.LONG_NULL,
                        String.format(Locale.ROOT,
                        "%s paused in %s is %.1f%% of the time, over the JVM's own goal of %.1f%% (GCTimeRatio %d)",
                        Durations.format(gc.pauseNanos()), Durations.format(info.span().duration()), share * 100,
                        goal * 100, gc.gcTimeRatio())));
            }
        }
        if (gc.pauseTargetNanos() != Nulls.LONG_NULL) {
            int over = 0;
            for (int i = 0, n = longestPauses.size(); i < n; i++) {
                if (longestPauses.getQuick(i) > gc.pauseTargetNanos()) {
                    over++;
                    when(Finding.Kind.PAUSE_OVER_TARGET, pauseTimes.getQuick(i));
                }
            }
            if (over > 0) {
                add(out, info, Finding.Kind.PAUSE_OVER_TARGET, over, over + (over == 1 ? " collection" : " collections")
                        + " paused longer than the target of " + Durations.format(gc.pauseTargetNanos())
                        + " (MaxGCPauseMillis)", "the longest " + Durations.format(gc.longestPauseNanos()));
            }
        }
        cause(gc, info, HUMONGOUS, Finding.Kind.HUMONGOUS_ALLOCATION, "an object of half a G1 region or more, "
                + "allocated straight into the old generation, forced a collection", out);
        cause(gc, info, METASPACE, Finding.Kind.METASPACE_GC, "class metadata reached the size at which the JVM "
                + "collects to unload classes, a size it raises after each such collection: a few while the "
                + "application starts are routine, ones that keep coming are classes loaded (or generated) faster "
                + "than they are unloaded", out);
        cause(gc, info, SYSTEM_GC, Finding.Kind.SYSTEM_GC, "something called System.gc()", out);
        return out;
    }

    private void cause(final Gc gc, final RecordingInfo info, final String cause, final Finding.Kind kind,
                       final String why, final List<Finding> out) {
        final long n = gc.causes().getOrDefault(cause, 0L);
        if (n > 0) {
            add(out, info, kind, n, n + (n == 1 ? " collection" : " collections") + " caused by " + cause, why);
        }
    }

    /** A finding that happened at instants: when goes between what and why. */
    private void add(final List<Finding> out, final RecordingInfo info, final Finding.Kind kind, final long count,
                     final String what, final String why) {
        final long f = first[kind.ordinal()];
        final long l = last[kind.ordinal()];
        final String when = f == Nulls.LONG_NULL ? ""
                : f == l ? ", at " + Durations.offset(f - info.startNanos())
                : ", from " + Durations.offset(f - info.startNanos()) + " to " + Durations.offset(l - info.startNanos());
        out.add(new Finding(kind, count, f, l, what + when + ": " + why));
    }

    /** How many distinct values, the unknown one counted like any other. */
    private static long distinct(final LongList values) {
        values.sort();
        long n = 0;
        for (int i = 0, size = values.size(); i < size; i++) {
            n += i == 0 || values.getQuick(i) != values.getQuick(i - 1) ? 1 : 0;
        }
        return n;
    }

    private List<ClassRow> classRows() {
        final List<ClassRow> rows = new ArrayList<>(byClass.size());
        for (int s = 0, n = byClass.slots(); s < n; s++) {
            if (byClass.hasKeyAtSlot(s)) {
                final Thrown t = byClass.valueAtSlot(s);
                final LongList times = t.times;
                times.sort();
                final int count = times.size();
                rows.add(new ClassRow(byClass.keyAtSlot(s), count, samples == 0 ? 0 : (double) count / samples,
                        t.message, times.getQuick(0), times.getQuick(count / 2), times.getQuick(count - 1)));
            }
        }
        rows.sort(Comparator.comparingLong(ClassRow::samples).reversed().thenComparing(ClassRow::className));
        return rows;
    }

    private List<SiteRow> siteRows() {
        final List<SiteRow> rows = new ArrayList<>(bySite.size());
        for (int s = 0, n = bySite.slots(); s < n; s++) {
            if (bySite.hasKeyAtSlot(s)) {
                final Site site = bySite.valueAtSlot(s);
                rows.add(new SiteRow(site.name, site.className, site.samples,
                        samples == 0 ? 0 : (double) site.samples / samples, site.stack));
            }
        }
        rows.sort(Comparator.comparingLong(SiteRow::samples).reversed().thenComparing(SiteRow::site)
                .thenComparing(SiteRow::className));
        return rows;
    }

    /** A counting table as a map, most first, ties by name, so two runs print alike. */
    private static Map<String, Long> sorted(final ObjLongHashMap<String> counts) {
        final List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.size());
        for (int s = 0, n = counts.slots(); s < n; s++) {
            if (counts.hasKeyAtSlot(s)) {
                entries.add(Map.entry(counts.keyAtSlot(s), counts.valueAtSlot(s)));
            }
        }
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        final Map<String, Long> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Long> e : entries) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public HealthReport report() {
        if (report == null) {
            throw new IllegalStateException("no recording has been read");
        }
        return report;
    }

    /**
     * Timestamped values, flat until the end and sorted there: delivery is file order, not
     * time order. Doubles are kept as their bits so one list type serves every series.
     */
    private static final class Points {
        private final LongList times = new LongList(256);
        private final LongList values = new LongList(256);
        private final ObjList<double[]> sorted = new ObjList<>(1);

        void add(final long time, final long value) {
            add(time, (double) value);
        }

        void add(final long time, final double value) {
            times.add(time);
            values.add(Double.doubleToRawLongBits(value));
            sorted.clear();
        }

        /** Values in time order, sorted once. */
        private double[] inOrder() {
            if (sorted.notEmpty()) {
                return sorted.getQuick(0);
            }
            final int n = times.size();
            final int[] order = Sorts.order(times);
            final double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = Double.longBitsToDouble(values.getQuick(order[i]));
            }
            sorted.add(out);
            return out;
        }

        /** The last value minus the first, for a running total; {@code Nulls.LONG_NULL} under two. */
        long span() {
            if (times.size() < 2) {
                return Nulls.LONG_NULL;
            }
            final double[] v = inOrder();
            return (long) (v[v.length - 1] - v[0]);
        }

        /** The time between the first reading and the last; 0 under two. */
        long duration() {
            return times.size() < 2 ? 0 : lastTime() - firstTime();
        }

        long firstTime() {
            long min = Long.MAX_VALUE;
            for (int i = 0, n = times.size(); i < n; i++) {
                min = Math.min(min, times.getQuick(i));
            }
            return min;
        }

        long lastTime() {
            long max = Long.MIN_VALUE;
            for (int i = 0, n = times.size(); i < n; i++) {
                max = Math.max(max, times.getQuick(i));
            }
            return max;
        }

        void series(final String name, final Series.Unit unit, final List<Series> out) {
            final int n = times.size();
            if (n == 0) {
                return;
            }
            final double[] v = inOrder();
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double sum = 0;
            for (final double x : v) {
                min = Math.min(min, x);
                max = Math.max(max, x);
                sum += x;
            }
            final int third = n / 3;
            out.add(new Series(name, unit, n, v[0], v[n - 1], min, max, sum / n,
                    third == 0 ? Double.NaN : floor(v, 0, third), third == 0 ? Double.NaN : floor(v, n - third, n)));
        }

        private static double floor(final double[] v, final int from, final int to) {
            double min = Double.MAX_VALUE;
            for (int i = from; i < to; i++) {
                min = Math.min(min, v[i]);
            }
            return min;
        }
    }
}
