// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.util.function.Predicate;

import dev.jfrq.core.coll.ObjLongHashMap;
import dev.jfrq.core.model.ThreadRef;

/**
 * A filter on thread names, decided once per thread rather than once per event (G-2.2): the
 * verdict is kept per {@link ThreadRef}, so the pattern runs on a thread's name the first time
 * the thread is seen and every later event is one probe.
 */
public final class ThreadFilter {

    private static final long PASSES = 1;
    private static final long FAILS = 0;

    private final Predicate<String> names;
    private final ObjLongHashMap<ThreadRef> verdicts = new ObjLongHashMap<>(256);

    /** @param names which thread names pass */
    public ThreadFilter(final Predicate<String> names) {
        this.names = names;
    }

    /** Whether {@code thread}'s name passes the filter. */
    public boolean passes(final ThreadRef thread) {
        final int index = verdicts.keyIndex(thread);
        if (index < 0) {
            return verdicts.valueAtQuick(index) == PASSES;
        }
        final boolean passes = names.test(thread.name());
        verdicts.putAt(index, thread, passes ? PASSES : FAILS);
        return passes;
    }
}
