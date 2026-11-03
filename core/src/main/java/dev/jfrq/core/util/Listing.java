// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * How the text and HTML reports cut a ranked list: the first rows, then the rest summed on one
 * line that says how many rows it holds and, where an option lists them, which. {@code hot} and
 * {@code waits} list sites per group and the frames below each site with the same limits and
 * words; JSON lists {@code --top} of each.
 */
public final class Listing {

    /** Sites listed per thread family ({@code hot}) or class ({@code waits}) unless {@code --sites} says how many. */
    public static final int SITES_SHOWN = 4;

    /** Frames listed below each site ({@code SiteKey.below}). */
    public static final int BELOW_SHOWN = 3;

    private Listing() {
    }

    /** What a site is and what is listed below it, as {@code hot} and {@code waits} print it. */
    public static String sites(final String siteKey) {
        return "a site is " + siteKey + "; below it, the outermost frame outside the JDK under the site, else the "
                + "frame the site called";
    }

    /** {@code measure} summed over {@code items}. */
    public static <T> long sum(final List<T> items, final ToLongFunction<T> measure) {
        long sum = 0;
        for (final T item : items) {
            sum += measure.applyAsLong(item);
        }
        return sum;
    }

    /** {@code measure} summed over what follows the first {@code shown} of {@code items}; 0 when nothing does. */
    public static <T> long rest(final List<T> items, final int shown, final ToLongFunction<T> measure) {
        return items.size() <= shown ? 0 : sum(items.subList(shown, items.size()), measure);
    }

    /**
     * The first {@code shown} of {@code items} on one line, each its name and its value, then the rest
     * as one entry: {@code folder-poll 400, kit-jobs-N 21, 2 more 3}.
     *
     * @param name  an item's name
     * @param value the value of a list of items, one shown or the rest together
     */
    public static <T> String listed(final List<T> items, final int shown, final Function<T, String> name,
            final Function<List<T>, String> value) {
        final StringBuilder sb = new StringBuilder();
        final int n = Math.min(shown, items.size());
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? ", " : "").append(name.apply(items.get(i))).append(' ')
                    .append(value.apply(items.subList(i, i + 1)));
        }
        if (items.size() > shown) {
            sb.append(", ").append(items.size() - shown).append(" more ")
                    .append(value.apply(items.subList(shown, items.size())));
        }
        return sb.toString();
    }

    /** {@link #listed(List, int, Function, Function)} of counts, each valued by its count. */
    public static String listed(final List<Count> counts, final int shown) {
        return listed(counts, shown, Count::name, rows -> Long.toString(sum(rows, Count::count)));
    }

    /** The line that stands for the rows not listed: {@code 2 more sites, --sites to list them}. */
    public static String more(final int hidden, final String what, final String option) {
        return hidden + " more " + what + ", " + option + " to list them";
    }
}
