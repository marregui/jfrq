// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.procs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.jfr.Absent;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.Fields;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.jfr.Transient;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.SiteKey;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.report.RecordingSummary;
import dev.jfrq.core.util.Count;
import dev.jfrq.core.util.Listing;

/**
 * Which processes the JVM started: {@code jdk.ProcessStart} by command, by the family of the
 * thread that started it ({@link RecordingSummary#family}) and by site ({@link SiteKey}).
 *
 * <p>A command is the basename of the first word of its command line, quotes stripped:
 * {@code /usr/bin/git -C repo status} is {@code git}. The arguments are not a key, since they
 * carry paths and ids that make every start its own row. The event has no threshold and no
 * throttle, so a JVM that forks in a loop writes one per fork; the per-event path is probes:
 * the command line into a bounded cache of names already worked out, then the name's counters
 * per thread and per stack. Families and sites are resolved once per distinct thread and stack
 * in {@link #finish}.
 */
public final class ProcessCollector implements JfrReader.Sink {

    public static final String PROCESS_START = EventKinds.nameOf(EventKinds.PROCESS_START);

    private static final Set<String> TYPES = Set.of(PROCESS_START);

    /** Command lines whose name is cached; the cache is cleared when full, as the interner's are. */
    static final int NAMES_CACHED = 4_096;

    /** Who started a process when the event names no thread. */
    private static final ThreadRef NO_THREAD = new ThreadRef(-1, Absent.NO_THREAD);

    private final SiteKey key;
    private final ObjObjHashMap<String, String> names = new ObjObjHashMap<>(256);
    private final ObjObjHashMap<String, Command> commands = new ObjObjHashMap<>(64);
    private Interner interner = new Interner();
    private long total;
    private ProcessReport report;

    /** @param key which frame of a starting stack names its site, as {@code alloc --app} chooses it */
    public ProcessCollector(final SiteKey key) {
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
        if (kind != EventKinds.PROCESS_START) {
            return;
        }
        final ThreadRef thread = interner.thread(e);
        start(thread == null ? NO_THREAD : thread, Events.startNanos(e),
                Events.stringOr(e, Fields.COMMAND, "", interner), Events.stack(e, interner));
    }

    /** A start whose fields are already read: what {@link #accept(RecordedEvent, int)} does, for tests. */
    void start(final ThreadRef thread, final long timeNanos, final String line, final Stack stack) {
        int index = names.keyIndex(line);
        final String name;
        if (index < 0) {
            name = names.valueAtQuick(index);
        } else {
            if (names.size() >= NAMES_CACHED) {
                names.clear();
                index = names.keyIndex(line);
            }
            name = names.putAt(index, line, name(line));
        }
        final int at = commands.keyIndex(name);
        final Command command = at < 0 ? commands.valueAtQuick(at) : commands.putAt(at, name, new Command());
        command.starts++;
        command.threads.increment(thread, 1);
        command.stacks.increment(stack, 1);
        if (timeNanos < command.firstNanos) {
            command.firstNanos = timeNanos;
            command.example = line;
        }
        total++;
    }

    /**
     * The basename of the first whitespace-separated word of {@code line}, quotes stripped, or
     * {@link ProcessReport#EMPTY}: {@code "/usr/bin/git" -C x} is {@code git}, {@code C:\bin\a.exe} is
     * {@code a.exe}.
     */
    static String name(final String line) {
        final int n = line.length();
        int from = 0;
        while (from < n && Character.isWhitespace(line.charAt(from))) {
            from++;
        }
        int to = from;
        while (to < n && !Character.isWhitespace(line.charAt(to))) {
            to++;
        }
        while (from < to && isQuote(line.charAt(from))) {
            from++;
        }
        while (to > from && isQuote(line.charAt(to - 1))) {
            to--;
        }
        for (int i = to - 1; i >= from; i--) {
            final char c = line.charAt(i);
            if (c == '/' || c == '\\') {
                from = i + 1;
                break;
            }
        }
        return from == to ? ProcessReport.EMPTY : line.substring(from, to);
    }

    private static boolean isQuote(final char c) {
        return c == '"' || c == '\'';
    }

    @Override
    public void finish(final RecordingInfo info) {
        final List<ProcessReport.Command> rows = new ArrayList<>(commands.size());
        final Map<String, Map<String, Long>> byFamily = new HashMap<>();
        final Map<String, Set<ThreadRef>> familyThreads = new HashMap<>();
        for (int s = 0, n = commands.slots(); s < n; s++) {
            if (commands.hasKeyAtSlot(s)) {
                final String name = commands.keyAtSlot(s);
                final Command c = commands.valueAtSlot(s);
                final Map<String, Long> threads = new HashMap<>();
                for (int t = 0, m = c.threads.slots(); t < m; t++) {
                    if (c.threads.hasKeyAtSlot(t)) {
                        final ThreadRef thread = c.threads.keyAtSlot(t);
                        final long starts = c.threads.valueAtSlot(t);
                        final String family = RecordingSummary.family(thread.name());
                        threads.merge(family, starts, Long::sum);
                        byFamily.computeIfAbsent(family, _ -> new HashMap<>()).merge(name, starts, Long::sum);
                        familyThreads.computeIfAbsent(family, _ -> new HashSet<>()).add(thread);
                    }
                }
                final Map<String, Long> sites = new HashMap<>();
                for (int t = 0, m = c.stacks.slots(); t < m; t++) {
                    if (c.stacks.hasKeyAtSlot(t)) {
                        sites.merge(key.of(c.stacks.keyAtSlot(t)), c.stacks.valueAtSlot(t), Long::sum);
                    }
                }
                rows.add(new ProcessReport.Command(name, c.starts, c.example, Count.ranked(threads),
                        Count.ranked(sites)));
            }
        }
        rows.sort(Comparator.comparingLong(ProcessReport.Command::starts).reversed()
                .thenComparing(ProcessReport.Command::command));
        final List<ProcessReport.Family> families = new ArrayList<>(byFamily.size());
        for (final Map.Entry<String, Map<String, Long>> e : byFamily.entrySet()) {
            final List<Count> started = Count.ranked(e.getValue());
            families.add(new ProcessReport.Family(e.getKey(), familyThreads.get(e.getKey()).size(),
                    Listing.sum(started, Count::count), started));
        }
        families.sort(Comparator.comparingLong(ProcessReport.Family::starts).reversed()
                .thenComparing(ProcessReport.Family::name));
        report = new ProcessReport(info, total, key.description(), List.copyOf(rows), List.copyOf(families));
    }

    /** The answer, after {@link #finish}. */
    public ProcessReport report() {
        if (report == null) {
            throw new IllegalStateException("finish() has not run");
        }
        return report;
    }

    /** One command's starts while reading: per thread, per stack, and the earliest command line. */
    private static final class Command {

        final ObjLongHashMap<ThreadRef> threads = new ObjLongHashMap<>(16);
        final ObjLongHashMap<Stack> stacks = new ObjLongHashMap<>(16);
        long starts;
        long firstNanos = Long.MAX_VALUE;
        String example;
    }
}
