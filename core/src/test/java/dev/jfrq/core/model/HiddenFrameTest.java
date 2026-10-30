// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jfrq.core.jfr.Events;
import dev.jfrq.core.jfr.JfrFixtures;
import dev.jfrq.core.jfr.JfrReader;
import dev.jfrq.core.jfr.Transient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lambda frame read from a real recording, through the {@link Interner}: it keeps the
 * address that tells it is hidden, and prints and names itself without it.
 */
class HiddenFrameTest {

    @TempDir
    Path dir;

    @Test
    void aLambdaFrameFromARecordingPrintsAsALambdaWithoutItsAddress() throws Exception {
        final Path file = JfrFixtures.record(dir, "hidden",
                r -> r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace(),
                () -> JfrFixtures.onThread("hidden-frames", () -> Thread.sleep(5)));
        final List<Frame> lambdas = new ArrayList<>();
        JfrReader.read(file, new JfrReader.Sink() {
            private Interner interner = new Interner();

            @Override
            public Set<String> eventTypes() {
                return Set.of("jdk.ThreadSleep");
            }

            @Override
            public void begin(final Interner interner) {
                this.interner = interner;
            }

            @Override
            public void accept(@Transient final RecordedEvent event) {
                for (final Frame f : Events.stack(event, interner).frames()) {
                    if (f.type().contains("$$Lambda")) {
                        lambdas.add(f);
                    }
                }
            }
        });
        assertFalse(lambdas.isEmpty(), "the fixture runs its body through a lambda");
        for (final Frame f : lambdas) {
            assertTrue(f.isHidden(), f.toString());
            assertTrue(f.pretty().endsWith("$$Lambda.run(lambda)"), f.pretty());
            assertTrue(f.stableName().endsWith("$$Lambda.run"), f.stableName());
            assertFalse(f.stableName().contains("0x"), f.stableName());
        }
    }
}
