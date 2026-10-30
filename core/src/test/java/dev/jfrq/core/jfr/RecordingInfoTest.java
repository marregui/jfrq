// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.ThreadRef;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingInfoTest {

    @Test
    void countsSettingsAndThreadsIterateInOneOrderWhateverOrderTheyCameIn() {
        // Map.copyOf and Set.copyOf iterate in an order salted per JVM; a consumer that forgot to sort
        // would print differently on every run over the same file.
        final Map<String, Long> counts = new HashMap<>();
        final Map<String, Map<String, String>> settings = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            counts.put("jdk.Type" + (char) ('z' - i % 26) + i, (long) i);
            settings.put("jdk.Type" + i, Map.of("threshold", i + " ms", "enabled", "true", "period", "1 s"));
        }
        final Set<ThreadRef> threads = new HashSet<>(List.of(new ThreadRef(9, "worker"), new ThreadRef(3, "worker"),
                new ThreadRef(3, "worker", true), new ThreadRef(1, "main"), new ThreadRef(2, "Reference Handler")));
        final RecordingInfo info = new RecordingInfo(Path.of("a.jfr"), new Interval(0, 1), 1, counts, settings,
                threads, List.of());

        final List<String> types = new ArrayList<>(counts.keySet());
        types.sort(null);
        assertEquals(types, List.copyOf(info.eventCounts().keySet()));
        final List<String> settingTypes = new ArrayList<>(settings.keySet());
        settingTypes.sort(null);
        assertEquals(settingTypes, List.copyOf(info.settings().keySet()));
        assertEquals(List.of("enabled", "period", "threshold"), List.copyOf(info.settings().get("jdk.Type7").keySet()));
        // Name, then id, then platform before virtual.
        assertEquals(List.of(new ThreadRef(2, "Reference Handler"), new ThreadRef(1, "main"), new ThreadRef(3, "worker"),
                new ThreadRef(3, "worker", true), new ThreadRef(9, "worker")), List.copyOf(info.threads()));
        // Still a map and a set by value, and lookups still answer.
        assertEquals(counts, info.eventCounts());
        assertEquals(threads, info.threads());
        assertEquals(7L, info.count("jdk.Types7"));
        assertEquals("7 ms", info.setting("jdk.Type7", "threshold").orElseThrow());
    }
}
