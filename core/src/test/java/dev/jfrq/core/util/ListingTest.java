// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// How the reports cut a ranked list: the first rows, then the rest summed as one entry or one line.
class ListingTest {

    private static final List<Count> FIVE = List.of(new Count("a", 4), new Count("b", 3), new Count("c", 2),
            new Count("d", 1), new Count("e", 1));

    /// A list longer than the rows shown sums its rest on one entry, with how many it holds.
    @Test
    void aLongListSumsItsRest() {
        assertEquals("a 4, b 3, c 2, 2 more 2", Listing.listed(FIVE, 3));
        assertEquals("a 4, b 3, c 2, d 1, e 1", Listing.listed(FIVE, 5));
        assertEquals("", Listing.listed(List.of(), 3));
    }

    /// The rest of a list of two measures carries both, in the format of each shown entry.
    @Test
    void theRestIsValuedLikeEachEntry() {
        final List<long[]> rows = List.of(new long[] {30, 2}, new long[] {20, 1}, new long[] {5, 1});
        assertEquals("r0 30/2, 2 more 25/2", Listing.listed(rows, 1, row -> "r" + rows.indexOf(row),
                part -> Listing.sum(part, row -> row[0]) + "/" + Listing.sum(part, row -> row[1])));
    }

    /// The rest after the rows shown, and nothing when every row is shown.
    @Test
    void restSumsWhatIsNotShown() {
        assertEquals(2, Listing.rest(FIVE, 3, Count::count));
        assertEquals(0, Listing.rest(FIVE, 5, Count::count));
        assertEquals(11, Listing.sum(FIVE, Count::count));
    }

    /// The line that stands for rows not listed names the option that lists them.
    @Test
    void moreNamesTheOption() {
        assertEquals("2 more sites, --sites to list them", Listing.more(2, "sites", "--sites"));
    }

    /// Counts rank most first, then by name.
    @Test
    void countsRankMostFirstThenByName() {
        assertEquals(List.of(new Count("b", 3), new Count("a", 1), new Count("c", 1)),
                Count.ranked(Map.of("a", 1L, "b", 3L, "c", 1L)));
    }
}
