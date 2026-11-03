// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.model.ThreadRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The thread filter decides once per thread and answers the same verdict after.
class ThreadFilterTest {

    /// Each thread's name is tested once, whatever the verdict, however often the thread is asked about.
    @Test
    void decidesOncePerThread() {
        final AtomicInteger tests = new AtomicInteger();
        final ThreadFilter f = new ThreadFilter(name -> {
            tests.incrementAndGet();
            return name.startsWith("edt");
        });
        final ThreadRef edt = new ThreadRef(1, "edt-0");
        final ThreadRef pool = new ThreadRef(2, "pool-1");
        for (int i = 0; i < 3; i++) {
            assertTrue(f.passes(edt));
            assertFalse(f.passes(pool));
        }
        assertEquals(2, tests.get());
    }
}
