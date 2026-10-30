// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import jdk.jfr.EventType;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.consumer.RecordedEvent;

import dev.jfrq.core.coll.IdentityObjObjHashMap;
import dev.jfrq.core.coll.LongObjHashMap;
import dev.jfrq.core.coll.Nulls;
import dev.jfrq.core.coll.ObjHashSet;
import dev.jfrq.core.coll.ObjList;
import dev.jfrq.core.coll.ObjObjHashMap;
import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Durations;

/**
 * One streaming pass over a recording that feeds every event to the sinks interested in
 * its type and collects the {@link RecordingInfo} on the way.
 *
 * <p>The pass goes through {@link EventStream} rather than {@code RecordingFile} because
 * the stream parser <em>skips</em> event types nobody subscribed to at the byte level,
 * instead of materialising them. A profile recording is dominated by events no analysis
 * reads ({@code jdk.GCPhaseParallel} alone can be most of the file), so for a filtered
 * read this is the difference between parsing the file and parsing a tenth of it.
 * Event objects are reused between callbacks ({@code setReuse(true)}), so sinks copy what
 * they keep, and delivery is in file order rather than time order
 * ({@code setOrdered(false)}); every sink sorts what it keeps at the end.
 *
 * <p>Per event, the reader resolves the {@link EventType} object once (by identity; the
 * parser hands out one instance per type per chunk) to the type's name, its
 * {@link EventKinds} tag, its counter and the sinks that want it, so the per-event path
 * hashes no strings (G-2.2).
 *
 * <p>Before the pass, the chunk headers are read directly ({@link Chunks}). They give the
 * recording's data span independently of which events are subscribed, and they reveal a
 * truncated, damaged or still-being-written file, which the JDK parser would otherwise
 * turn into a silently empty answer. Complete chunks behind a damaged one are read from a
 * temporary copy without it, because the parser reads nothing after the damage. Consequences for {@link RecordingInfo}: event counts and
 * threads cover the event types that were read; the span is the file's own, taken from
 * the headers alone, so every command reports the same span for the same file (an event
 * that sticks out of it, such as a safepoint the final chunk rotation ends, is clipped by
 * the analyses like one that began before the recording). A read with an "everything"
 * sink (as {@code jfrq info} does) sees the whole file.
 */
public final class JfrReader {

    private static final String ACTIVE_SETTING = EventKinds.nameOf(EventKinds.ACTIVE_SETTING);
    private static final Sink[] NO_SINKS = new Sink[0];
    /** Consecutive chunks of one recording abut exactly; this only absorbs clock rounding. */
    private static final long JOIN_TOLERANCE_NANOS = 1_000_000L;

    /**
     * A consumer of events of the types it names. An empty set means every event. The
     * event object is the parser's flyweight, recycled after the call: a sink copies what
     * it keeps and never retains the event.
     */
    public interface Sink {
        Set<String> eventTypes();

        void accept(@Transient RecordedEvent event);

        /**
         * {@link #accept(RecordedEvent)} with the event type's {@link EventKinds} tag,
         * resolved once per type rather than per event; sinks that dispatch on the type
         * override this one and switch on the tag.
         */
        default void accept(@Transient final RecordedEvent event, final int kind) {
            accept(event);
        }

        /** Called before the first event with the interner to build stacks through. */
        default void begin(final Interner interner) {
        }

        /** Called once after the last event, with the recording facts now known. */
        default void finish(final RecordingInfo info) {
        }
    }

    private JfrReader() {
    }

    /**
     * How long the last {@link #read} spent in the sinks' {@code finish()}, which is where
     * every analysis happens; the rest of the read is parsing. Set per call, read by
     * {@code --timing} right after it, so a program that reads two recordings concurrently
     * gets one of the two numbers.
     */
    public static long analyseNanos() {
        return analyseNanos;
    }

    private static volatile long analyseNanos;

    /**
     * @throws IOException when the file is missing, is not a recording, holds no complete
     *                     chunk, or ends in a chunk that is still being written. The last
     *                     case is refused outright because the JDK parser does not fail on
     *                     it: it waits for the chunk to finish, forever.
     */
    public static RecordingInfo read(final Path file, final Sink... sinks) throws IOException {
        final Chunks chunks = Chunks.scan(file);
        final List<String> warnings = new ArrayList<>();
        if (chunks.isInProgress()) {
            final Chunks.Header last = chunks.headers().getLast();
            final String dump = "Dump the recording from the JVM (jcmd <pid> JFR.dump, or jfrq-live)";
            if (chunks.complete() == 0) {
                throw new IOException("recording is still being written: its only chunk is not finished. " + dump
                        + " and read the dump");
            }
            throw new IOException("recording is still being written: its last chunk (at byte " + last.offset()
                    + ") is not finished. " + dump + " or, to read the " + chunks.complete()
                    + " complete chunk(s), truncate the file to " + last.offset() + " bytes");
        }
        final long length = Files.size(file);
        if (chunks.complete() == 0) {
            throw new IOException("recording holds no complete chunk: " + damage(chunks.damaged().getFirst(), length));
        }
        for (final Chunks.Damage d : chunks.damaged()) {
            warnings.add(damage(d, length) + "; " + chunks.complete() + " complete chunk(s) were read");
        }
        joins(chunks, warnings);

        final Pass pass = new Pass(sinks);
        for (final Sink s : sinks) {
            s.begin(pass.interner);
        }
        // The parser reads nothing after the first damage, so complete chunks behind one are
        // handed to it in a copy without the damage.
        final Path source = chunks.readableInPlace() ? file : Files.createTempFile("jfrq-", ".jfr");
        if (source != file) {
            // A copy can be as large as the recording: gone even if the process is stopped mid-read.
            source.toFile().deleteOnExit();
        }
        try {
            if (source != file) {
                chunks.copyComplete(file, source);
            }
            stream(source, pass);
            if (pass.failures > 0) {
                warnings.add(pass.failures + " event(s) could not be handled and are missing from every figure;"
                        + " the first failure: " + pass.firstFailure);
            }
        } finally {
            if (source != file) {
                Files.deleteIfExists(source);
            }
        }

        // The chunk headers alone bound the span. Widening it to the last event read made the
        // span depend on which event types a command subscribed to.
        final RecordingInfo info = new RecordingInfo(file, new Interval(chunks.startNanos(), chunks.endNanos()),
                chunks.count(), pass.eventCounts(), pass.settings(), pass.threads(), warnings);
        final long parsed = System.nanoTime();
        for (final Sink s : sinks) {
            s.finish(info);
        }
        // The analysis runs in finish(), so without this the two are one number and
        // "where did the time go" cannot be answered from the outside.
        analyseNanos = System.nanoTime() - parsed;
        return info;
    }

    /**
     * What a damaged stretch of the file is and what it costs, in the terms a reader can
     * check: byte offsets, and the time span its header declares when it has one.
     */
    private static String damage(final Chunks.Damage d, final long length) {
        final boolean tail = d.end() == length;
        if (!d.hasHeader()) {
            return tail ? "the file is truncated: it ends " + d.length() + " bytes into a chunk header at byte "
                    + d.offset() + ", after the last complete chunk"
                    : "bytes " + d.offset() + " to " + d.end() + " are not a Flight Recorder chunk and were skipped";
        }
        final String chunk = "the chunk at byte " + d.offset() + ", recorded from "
                + Instant.ofEpochSecond(0, d.startNanos()) + " for " + Durations.format(d.durationNanos());
        return tail ? "the file is truncated: it ends inside " + chunk + ", whose events are missing"
                : chunk + ", is cut off or damaged (bytes " + d.offset() + " to " + d.end()
                        + ", then a complete chunk): its events are missing";
    }

    /** The pass itself, over a file the parser can read from end to end. */
    private static void stream(final Path file, final Pass pass) throws IOException {
        try (final EventStream stream = EventStream.openFile(file)) {
            stream.setReuse(true);
            stream.setOrdered(false);
            // Without a handler of its own the stream prints the stack trace of a sink's
            // exception and drops that event, which leaves every figure short without a word.
            stream.onError(pass::failed);
            stream.onMetadata(m -> {
                for (final EventType t : m.getEventTypes()) {
                    pass.typeNames.put(t.getId(), t.getName());
                }
            });
            // No sinks at all means "tell me about the file": read everything.
            if (pass.all.length > 0 || pass.subscribed.isEmpty()) {
                stream.onEvent(pass);
            } else {
                // Read on every pass so a filtered read still learns the thresholds and periods.
                pass.subscribe(ACTIVE_SETTING);
                for (int i = 0, n = pass.subscribed.size(); i < n; i++) {
                    stream.onEvent(pass.subscribed.getQuick(i), pass);
                }
            }
            stream.start();
        }
    }

    /**
     * The JVM starts each chunk of a recording where the previous one ended, so a chunk
     * that starts elsewhere joins files from different recordings, runs or JVMs (JFR files
     * concatenate): a hole with no data, or a chunk from before the previous one. Rates
     * over the span and silences across the join are then not facts about one run. A
     * damaged chunk in between counts as the previous one when its header declares a span
     * (a stretch with no header says nothing about time, and the pair is not judged), and
     * the time it covers is warned about on its own: the span includes it, no event does.
     */
    private static void joins(final Chunks chunks, final List<String> warnings) {
        final List<Chunks.Header> headers = chunks.headers();
        for (int i = 1, n = headers.size(); i < n; i++) {
            final Chunks.Header before = headers.get(i - 1);
            final Chunks.Header after = headers.get(i);
            final long start = after.startNanos();
            // A damaged chunk between two read ones is time the span covers and no event does.
            final long missing = start - before.endNanos();
            if (damagedBetween(chunks, before, after) && missing > JOIN_TOLERANCE_NANOS) {
                warnings.add("chunk " + (i + 1) + " of " + n + " follows " + Durations.format(missing)
                        + " of damaged chunks whose events are missing, so rates over the span are diluted and a "
                        + "silence across the hole is not a stall");
            }
            final long previousEnd = previousEnd(chunks, before, after);
            if (previousEnd == Nulls.LONG_NULL) {
                continue;
            }
            if (start < previousEnd - JOIN_TOLERANCE_NANOS) {
                warnings.add("chunk " + (i + 1) + " of " + n + " starts " + Durations.format(previousEnd - start)
                        + " before the previous one ends: the file joins recordings from different runs, so rates "
                        + "over the span and silences across the join describe no single run");
            } else if (start > previousEnd + JOIN_TOLERANCE_NANOS) {
                warnings.add("chunk " + (i + 1) + " of " + n + " starts " + Durations.format(start - previousEnd)
                        + " after the previous one ends: nothing was recorded in between (a restarted recording, "
                        + "or files from different runs joined), so rates over the span are diluted and a silence "
                        + "across the hole is not a stall");
            }
        }
    }

    private static boolean damagedBetween(final Chunks chunks, final Chunks.Header before, final Chunks.Header after) {
        for (final Chunks.Damage d : chunks.damaged()) {
            if (d.hasHeader() && d.offset() > before.offset() && d.offset() < after.offset()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the chunk before {@code after} ends: {@code before}, or the last damaged chunk
     * between the two; {@link Nulls#LONG_NULL} when the last stretch between them has no header.
     */
    private static long previousEnd(final Chunks chunks, final Chunks.Header before, final Chunks.Header after) {
        long end = before.endNanos();
        for (final Chunks.Damage d : chunks.damaged()) {
            if (d.offset() > before.offset() && d.offset() < after.offset()) {
                end = d.hasHeader() ? d.startNanos() + d.durationNanos() : Nulls.LONG_NULL;
            }
        }
        return end;
    }

    /** What the reader knows about one event type name: its tag, its count and its sinks. */
    private static final class Dispatch {
        final String name;
        final int kind;
        final Sink[] targets;
        long count;

        Dispatch(final String name, final int kind, final Sink[] targets) {
            this.name = name;
            this.kind = kind;
            this.targets = targets;
        }
    }

    /** The state of one read: everything the per-event callback touches, allocated once. */
    private static final class Pass implements Consumer<RecordedEvent> {
        final Interner interner = new Interner();
        /** Sinks that want every event. */
        final Sink[] all;
        /** Event type names at least one sink asked for, in subscription order. */
        final ObjList<String> subscribed = new ObjList<>();
        /** Event type id (per the metadata event) to name, for {@code jdk.ActiveSetting}. */
        final LongObjHashMap<String> typeNames = new LongObjHashMap<>(512);

        private final ObjObjHashMap<String, ObjList<Sink>> sinksByName = new ObjObjHashMap<>(64);
        private final ObjObjHashMap<String, Dispatch> byName = new ObjObjHashMap<>(256);
        /** Bounded like the interner's identity caches: event types are per chunk. */
        private final IdentityObjObjHashMap<EventType, Dispatch> byType = new IdentityObjObjHashMap<>(256);
        private final ObjObjHashMap<String, ObjObjHashMap<String, String>> settings = new ObjObjHashMap<>(256);
        private final ObjHashSet<ThreadRef> threads = new ObjHashSet<>(256);
        /** Events a sink failed on (a damaged constant pool can hand a sink nulls), and the first failure. */
        long failures;
        Throwable firstFailure;

        void failed(final Throwable t) {
            if (failures++ == 0) {
                firstFailure = t;
            }
        }

        Pass(final Sink[] sinks) {
            final ObjList<Sink> everything = new ObjList<>();
            for (final Sink s : sinks) {
                if (s.eventTypes().isEmpty()) {
                    everything.add(s);
                } else {
                    for (final String t : s.eventTypes()) {
                        final int index = sinksByName.keyIndex(t);
                        final ObjList<Sink> targets = index < 0 ? sinksByName.valueAtQuick(index) : subscribe(t, index);
                        targets.add(s);
                    }
                }
            }
            all = everything.isEmpty() ? NO_SINKS : everything.toList().toArray(NO_SINKS);
        }

        void subscribe(final String type) {
            final int index = sinksByName.keyIndex(type);
            if (index >= 0) {
                subscribe(type, index);
            }
        }

        private ObjList<Sink> subscribe(final String type, final int index) {
            subscribed.add(type);
            return sinksByName.putAt(index, type, new ObjList<>(2));
        }

        @Override
        public void accept(@Transient final RecordedEvent e) {
            final EventType type = e.getEventType();
            final int index = byType.keyIndex(type);
            final Dispatch dispatch = index < 0 ? byType.valueAtQuick(index) : resolve(type, index);
            dispatch.count++;
            final ThreadRef thread = interner.thread(e);
            if (thread != null) {
                final int t = threads.keyIndex(thread);
                if (t >= 0) {
                    threads.addAt(t, thread);
                }
            }
            if (dispatch.kind == EventKinds.ACTIVE_SETTING) {
                setting(e);
            }
            final Sink[] targets = dispatch.targets;
            for (int i = 0; i < targets.length; i++) {
                targets[i].accept(e, dispatch.kind);
            }
        }

        /** Settings can change between chunks; the last chunk's values are the ones reported. */
        private void setting(@Transient final RecordedEvent e) {
            final String owner = typeNames.get(e.getLong("id"));
            if (owner == null) {
                return;
            }
            final int index = settings.keyIndex(owner);
            final ObjObjHashMap<String, String> values = index < 0 ? settings.valueAtQuick(index)
                    : settings.putAt(index, owner, new ObjObjHashMap<>(16));
            values.put(e.getString("name"), e.getString("value"));
        }

        /** First sight of an {@link EventType} object: one string hash, then identity. */
        private Dispatch resolve(final EventType type, int index) {
            final String name = type.getName();
            final int byNameIndex = byName.keyIndex(name);
            final Dispatch dispatch;
            if (byNameIndex < 0) {
                dispatch = byName.valueAtQuick(byNameIndex);
            } else {
                final ObjList<Sink> targets = new ObjList<>(all.length + 2);
                for (final Sink s : all) {
                    targets.add(s);
                }
                final ObjList<Sink> named = sinksByName.get(name);
                if (named != null) {
                    targets.addAll(named);
                }
                dispatch = byName.putAt(byNameIndex, name, new Dispatch(name, EventKinds.kindOf(name),
                        targets.isEmpty() ? NO_SINKS : targets.toList().toArray(NO_SINKS)));
            }
            if (byType.size() >= Interner.IDENTITY_LIMIT) {
                byType.clear();
                index = byType.keyIndex(type);
            }
            return byType.putAt(index, type, dispatch);
        }

        Map<String, Long> eventCounts() {
            final Map<String, Long> counts = new HashMap<>(byName.size() * 2);
            for (int s = 0, n = byName.slots(); s < n; s++) {
                if (byName.hasKeyAtSlot(s)) {
                    final Dispatch d = byName.valueAtSlot(s);
                    counts.put(d.name, d.count);
                }
            }
            return counts;
        }

        Map<String, Map<String, String>> settings() {
            final Map<String, Map<String, String>> out = new HashMap<>(settings.size() * 2);
            for (int s = 0, n = settings.slots(); s < n; s++) {
                if (settings.hasKeyAtSlot(s)) {
                    final ObjObjHashMap<String, String> values = settings.valueAtSlot(s);
                    final Map<String, String> copy = new HashMap<>(values.size() * 2);
                    for (int v = 0, m = values.slots(); v < m; v++) {
                        if (values.hasKeyAtSlot(v)) {
                            copy.put(values.keyAtSlot(v), values.valueAtSlot(v));
                        }
                    }
                    out.put(settings.keyAtSlot(s), copy);
                }
            }
            return out;
        }

        Set<ThreadRef> threads() {
            final Set<ThreadRef> out = new HashSet<>(threads.size() * 2);
            for (int s = 0, n = threads.slots(); s < n; s++) {
                if (threads.hasKeyAtSlot(s)) {
                    out.add(threads.keyAtSlot(s));
                }
            }
            return out;
        }
    }
}
