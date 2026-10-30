// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.time.Instant;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

import dev.jfrq.core.model.Interner;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

/**
 * Field access on {@link RecordedEvent} with the JFR quirks handled in one place:
 * timestamps are exposed as epoch nanoseconds; missing values become {@code null} or a
 * sentinel rather than an exception. (The other quirk, sampler events naming their thread
 * {@code sampledThread} where every other event says {@code eventThread}, is resolved once
 * per event type by {@link Interner#thread(RecordedEvent)}.) No method keeps the event.
 *
 * <p>Fields are named by {@link Fields} tag. Whether the event's type has one is a bit of
 * a mask resolved once per type ({@link Interner#fields}), not a by-name scan per event.
 * The consumer API offers no access by index, so reading the value is still one by-name
 * scan ({@code getValue}); the typed getters ({@code getThread}, {@code getClass},
 * {@code getString}, {@code getStackTrace}) would scan twice, once to check the declared
 * type and once to read, so they are called only when {@code getValue} returns
 * {@code null} or an unexpected type, which keeps their result and their exceptions.
 */
public final class Events {

    private Events() {
    }

    public static long nanos(final Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    public static long startNanos(@Transient final RecordedEvent e) {
        return nanos(e.getStartTime());
    }

    public static long endNanos(@Transient final RecordedEvent e) {
        return nanos(e.getEndTime());
    }

    public static Interval interval(@Transient final RecordedEvent e) {
        final long start = startNanos(e);
        final long end = endNanos(e);
        return new Interval(start, Math.max(start, end));
    }

    /**
     * A thread-valued field such as {@link Fields#PREVIOUS_OWNER} resolved through the interner's
     * identity cache, or {@code null}. The event's own thread is {@link Interner#thread(RecordedEvent)}.
     */
    public static ThreadRef thread(@Transient final RecordedEvent e, final int field, final Interner interner) {
        if (!has(e, field, interner)) {
            return null;
        }
        final String name = Fields.nameOf(field);
        final Object v = e.getValue(name);
        return interner.thread(v instanceof RecordedThread t ? t : e.getThread(name));
    }

    /** The event's stack, canonicalised through {@code interner}. */
    public static Stack stack(@Transient final RecordedEvent e, final Interner interner) {
        if (!has(e, Fields.STACK_TRACE, interner)) {
            return interner.stack(null);
        }
        final Object v = e.getValue(Fields.nameOf(Fields.STACK_TRACE));
        return interner.stack(v instanceof RecordedStackTrace t ? t : e.getStackTrace());
    }

    /**
     * Whether a {@code jdk.ThreadStart} is native code attaching its thread to the JVM: it names
     * no starting thread and has no stack, given as {@code parent} and {@code stack}. False in a
     * file without the {@code parentThread} field, which cannot tell: an older JDK's start is
     * not an attach.
     */
    public static boolean isAttach(@Transient final RecordedEvent e, final ThreadRef parent, final Stack stack,
                                   final Interner interner) {
        return has(e, Fields.PARENT_THREAD, interner) && parent == null && stack.isEmpty();
    }

    /**
     * The JVM name of a class-valued field ({@code [B}, {@code java.lang.Object}) resolved
     * through the interner's identity cache, or {@code null}.
     */
    public static String className(@Transient final RecordedEvent e, final int field, final Interner interner) {
        if (!has(e, field, interner)) {
            return null;
        }
        final String name = Fields.nameOf(field);
        final Object v = e.getValue(name);
        return interner.className(v instanceof RecordedClass c ? c : e.getClass(name));
    }

    public static long longOr(@Transient final RecordedEvent e, final int field, final long fallback,
            final Interner interner) {
        return has(e, field, interner) ? e.getLong(Fields.nameOf(field)) : fallback;
    }

    /**
     * A timespan field in nanoseconds, whatever unit the recording stores it in (GC pause sums
     * are in ticks), or {@code fallback}.
     */
    public static long durationNanosOr(@Transient final RecordedEvent e, final int field, final long fallback,
            final Interner interner) {
        return has(e, field, interner) ? e.getDuration(Fields.nameOf(field)).toNanos() : fallback;
    }

    public static double doubleOr(@Transient final RecordedEvent e, final int field, final double fallback,
            final Interner interner) {
        return has(e, field, interner) ? e.getDouble(Fields.nameOf(field)) : fallback;
    }

    public static boolean booleanOr(@Transient final RecordedEvent e, final int field, final boolean fallback,
            final Interner interner) {
        return has(e, field, interner) ? e.getBoolean(Fields.nameOf(field)) : fallback;
    }

    public static String stringOr(@Transient final RecordedEvent e, final int field, final String fallback,
            final Interner interner) {
        if (!has(e, field, interner)) {
            return fallback;
        }
        final String name = Fields.nameOf(field);
        final Object v = e.getValue(name);
        final String s = v instanceof String str ? str : e.getString(name);
        return s == null ? fallback : s;
    }

    /** Presence comes from the interner's per-type mask, not a by-name scan per event. */
    private static boolean has(@Transient final RecordedEvent e, final int field, final Interner interner) {
        return (interner.fields(e) & Fields.bit(field)) != 0;
    }
}
