// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

/**
 * What every report prints where an event leaves a field empty. One name per state, so that
 * {@code waits} and {@code stalls} call a park without a blocker the same, and a filter or a
 * JSON reader matches it in either.
 */
public final class Absent {

    /** What a {@code jdk.ThreadPark} with no {@code parkedClass} ({@code LockSupport.parkNanos}) waits on. */
    public static final String NO_BLOCKER = "(no blocker object)";

    /** Who an event that names no thread is attributed to. */
    public static final String NO_THREAD = "(no thread)";

    private Absent() {
    }
}
