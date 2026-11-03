// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A name and how many events it accounts for: a frame and its samples, a command or a thread
 * family and its process starts.
 *
 * @param name  the frame, command or family
 * @param count how many
 */
public record Count(String name, long count) {

    /** Most first, then by name. */
    private static final Comparator<Count> RANKED = Comparator.comparingLong(Count::count).reversed()
            .thenComparing(Count::name);

    /** {@code counts} as rows, most first, then by name. */
    public static List<Count> ranked(final Map<String, Long> counts) {
        final List<Count> rows = new ArrayList<>(counts.size());
        for (final Map.Entry<String, Long> e : counts.entrySet()) {
            rows.add(new Count(e.getKey(), e.getValue()));
        }
        rows.sort(RANKED);
        return List.copyOf(rows);
    }
}
