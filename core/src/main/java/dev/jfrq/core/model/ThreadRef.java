// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jdk.jfr.consumer.RecordedThread;

/**
 * A thread as JFR identifies it: the Java thread id plus the name JFR recorded for it.
 * Two events attributed to the same thread compare equal.
 *
 * @param id      the Java thread id, or the OS thread id for threads without a Java identity
 * @param name    the Java thread name, falling back to the OS name, then {@code thread#<id>}
 * @param isVirtual whether it is a virtual thread; an allocation sample on one is weighted by
 *                what its carrier allocated, not by what it did ({@code AllocationCollector})
 */
public record ThreadRef(long id, String name, boolean isVirtual) {

    /**
     * Name, then id, then platform before virtual: a total order consistent with {@code equals}.
     * Threads printed in it come out the same on every run over the same file.
     */
    public static final Comparator<ThreadRef> ORDER = Comparator.comparing(ThreadRef::name)
            .thenComparingLong(ThreadRef::id)
            .thenComparing(ThreadRef::isVirtual);

    /** A platform thread. */
    public ThreadRef(final long id, final String name) {
        this(id, name, false);
    }

    /**
     * An unmodifiable copy of {@code threads} that iterates in {@link #ORDER} and is hashed, so
     * {@code contains} stays one probe. {@code Set.copyOf} iterates in an order salted per JVM.
     */
    public static Set<ThreadRef> ordered(final Collection<ThreadRef> threads) {
        final List<ThreadRef> sorted = new ArrayList<>(threads);
        sorted.sort(ORDER);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    public static ThreadRef of(final RecordedThread t) {
        if (t == null) {
            return null;
        }
        final long id = t.getJavaThreadId() > 0 ? t.getJavaThreadId() : t.getOSThreadId();
        String name = t.getJavaName();
        if (name == null || name.isEmpty()) {
            name = t.getOSName();
        }
        if (name == null || name.isEmpty()) {
            name = "thread#" + id;
        }
        return new ThreadRef(id, name, t.isVirtual());
    }

    @Override
    @SuppressWarnings("NullableProblems") // Record.toString() carries an external @NotNull; this never returns null
    public String toString() {
        return name;
    }
}
