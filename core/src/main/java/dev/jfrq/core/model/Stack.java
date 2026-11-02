// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A stack trace with the innermost frame first. Value semantics, so stacks can key maps
 * (allocation sites, busy-run histograms); the hash is computed once, because a stack is
 * looked up far more often than it is created and the same stack recurs thousands of
 * times in a recording. {@link Interner} makes recurring stacks share one instance, so
 * {@link #equals} is an identity check first.
 *
 * <p>Per-event code walks the frames with {@link #depth()} and {@link #frameQuick(int)};
 * {@link #frames()}, {@link #top()} and {@link #culprit()} allocate and are for reports.
 */
public final class Stack {

    public static final Stack EMPTY = new Stack(new Frame[0], false);

    private final Frame[] frames;
    private final boolean truncated;
    private final int hash;

    Stack(final Frame[] frames, final boolean truncated) {
        this.frames = frames;
        this.truncated = truncated;
        this.hash = hashOf(frames, frames.length, truncated);
    }

    /**
     * @param frames    innermost first; may be empty when JFR could not walk the stack
     * @param truncated true when JFR cut the stack at its depth limit (default 64)
     */
    public Stack(final List<Frame> frames, final boolean truncated) {
        this(frames.toArray(new Frame[0]), truncated);
    }

    /**
     * The hash a stack over the first {@code n} frames of {@code frames} would have,
     * which is what {@link #hashCode()} caches.
     */
    static int hashOf(final Frame[] frames, final int n, final boolean truncated) {
        int h = 1;
        for (int i = 0; i < n; i++) {
            h = 31 * h + frames[i].hashCode();
        }
        return 31 * h + (truncated ? 1 : 0);
    }

    /** Innermost first. */
    public List<Frame> frames() {
        return List.of(frames);
    }

    /** Number of frames. */
    public int depth() {
        return frames.length;
    }

    /** The frame at {@code index}, innermost first; unchecked (G-1.6). */
    public Frame frameQuick(final int index) {
        assert index >= 0 && index < frames.length;
        return frames[index];
    }

    public boolean isTruncated() {
        return truncated;
    }

    public boolean isEmpty() {
        return frames.length == 0;
    }

    public Optional<Frame> top() {
        return Optional.ofNullable(topOrNull());
    }

    /** {@link #top()} without the wrapper: {@code null} for an empty stack. */
    public Frame topOrNull() {
        return frames.length == 0 ? null : frames[0];
    }

    /**
     * The innermost frame that is not JDK code: the best single name for "what the
     * application was doing". Falls back to the top frame when the whole stack is JDK code.
     */
    public Optional<Frame> culprit() {
        return Optional.ofNullable(culpritOrNull());
    }

    /** {@link #culprit()} without the wrapper: {@code null} for an empty stack. */
    public Frame culpritOrNull() {
        final int index = culpritIndex();
        return index < 0 ? topOrNull() : frames[index];
    }

    /**
     * Where {@link #culpritOrNull()} is, innermost first: the innermost non-JDK frame, else the
     * top frame, else {@code -1} for an empty stack.
     */
    public int culpritOrTopIndex() {
        final int index = culpritIndex();
        return index < 0 && frames.length > 0 ? 0 : index;
    }

    /** Index of the innermost non-JDK frame, or -1. */
    private int culpritIndex() {
        for (int i = 0; i < frames.length; i++) {
            if (!frames[i].isJdk()) {
                return i;
            }
        }
        return -1;
    }

    /** The first {@code n} frames, innermost first, as a new stack. */
    public Stack head(final int n) {
        if (frames.length <= n) {
            return this;
        }
        return new Stack(Arrays.copyOf(frames, n), true);
    }

    /**
     * Multi-line rendering in stack-trace style with the given indent: the innermost
     * {@code maxFrames} frames, and if the {@linkplain #culprit() culprit} lies deeper, an
     * elision followed by the culprit's own line, so the application frame is always visible.
     */
    public String pretty(final String indent, final int maxFrames) {
        final StringBuilder sb = new StringBuilder();
        int shown = Math.min(maxFrames, frames.length);
        for (int i = 0; i < shown; i++) {
            sb.append(indent).append("at ").append(frames[i].pretty()).append('\n');
        }
        final int culpritIndex = culpritIndex();
        if (culpritIndex >= shown) {
            if (culpritIndex > shown) {
                sb.append(indent).append("... ").append(culpritIndex - shown).append(" more").append('\n');
            }
            sb.append(indent).append("at ").append(frames[culpritIndex].pretty()).append('\n');
            shown = culpritIndex + 1;
        }
        if (shown < frames.length || (truncated && shown == frames.length)) {
            sb.append(indent).append("... ").append(frames.length - shown).append(" more").append('\n');
        }
        return sb.toString();
    }

    /** Whether this stack is exactly the first {@code n} frames of {@code candidate} with the same truncation. */
    boolean sameAs(final Frame[] candidate, final int n, final boolean candidateTruncated) {
        if (truncated != candidateTruncated || frames.length != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            final Frame a = frames[i];
            final Frame b = candidate[i];
            if (a != b && !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Stack s && hash == s.hash && truncated == s.truncated && Arrays.equals(frames, s.frames);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "Stack" + Arrays.toString(frames) + (truncated ? "..." : "");
    }
}
