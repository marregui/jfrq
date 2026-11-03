// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Cleanup after a failure keeps every failure: the first stays the one reported, unless a step fails with an `Error`.
class ClosingTest {

    /// A step that fails with an exception is suppressed into the failure that started the cleanup.
    @Test
    void aFailedStepIsSuppressedIntoThePrimary() {
        final IOException primary = new IOException("the dump failed");
        final IOException close = new IOException("close failed");
        Closing.after(primary, () -> {
            throw close;
        });
        final IllegalStateException state = new IllegalStateException("refused");
        Closing.after(primary, () -> {
            throw state;
        });
        Closing.after(primary, () -> { });
        assertArrayEquals(new Throwable[] {close, state}, primary.getSuppressed());
    }

    /// An `Error` from a step is not dropped into the primary: it is thrown, and carries the primary.
    @Test
    void anErrorFromAStepWinsAndCarriesThePrimary() {
        final IOException primary = new IOException("the dump failed");
        final OutOfMemoryError oom = new OutOfMemoryError("close");
        final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> Closing.after(primary, () -> {
            throw oom;
        }));
        assertSame(oom, thrown);
        assertArrayEquals(new Throwable[] {primary}, oom.getSuppressed());
        assertArrayEquals(new Throwable[0], primary.getSuppressed());
    }

    /// The chain the dump's cleanup keeps: the first failure, unless a later one is an `Error` and it is not.
    @Test
    void theChainKeepsTheFirstFailureUnlessAnErrorFollows() {
        final IOException first = new IOException("first");
        final IOException second = new IOException("second");
        assertSame(first, Closing.chain(null, first));
        assertSame(first, Closing.chain(first, second));
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
        final StackOverflowError error = new StackOverflowError("deep");
        assertSame(error, Closing.chain(first, error));
        assertArrayEquals(new Throwable[] {first}, error.getSuppressed());
        final AssertionError another = new AssertionError("another");
        assertSame(error, Closing.chain(error, another));
    }
}
