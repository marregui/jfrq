// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;

/**
 * Cleanup after a failure (G-4.3): every step is attempted, and no failure of one is dropped.
 * A step's {@link IOException} or {@link RuntimeException} is suppressed into the failure that
 * started the cleanup, which stays the one reported; an {@link Error} from a step wins and
 * carries that failure, since nothing the process does after it can be trusted.
 */
final class Closing {

    private Closing() {
    }

    /** A cleanup step: a close, a detach, a delete. */
    @FunctionalInterface
    interface Step {
        void run() throws IOException;
    }

    /**
     * Runs {@code step} after {@code primary} failed, as {@link #chain} adds its failure; the
     * caller then throws {@code primary}.
     *
     * @throws Error the step's own when {@code primary} is not one, with {@code primary} suppressed into it
     */
    static void after(final Throwable primary, final Step step) {
        try {
            step.run();
        } catch (final IOException | RuntimeException | Error e) {
            if (chain(primary, e) instanceof final Error error && error != primary) {
                throw error;
            }
        }
    }

    /**
     * {@code primary} with {@code next} added: {@code next} itself when there was no failure yet,
     * else {@code primary} with {@code next} suppressed into it, unless {@code next} is an
     * {@link Error} and {@code primary} is not, which makes {@code next} the failure.
     */
    static Throwable chain(final Throwable primary, final Throwable next) {
        if (primary == null) {
            return next;
        }
        if (next instanceof Error && !(primary instanceof Error)) {
            next.addSuppressed(primary);
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }
}
