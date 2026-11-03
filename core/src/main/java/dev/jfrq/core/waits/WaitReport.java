// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.waits;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.jfrq.core.jfr.Absent;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.SiteKey;
import dev.jfrq.core.util.Durations;

/**
 * What threads waited on, from their own side: {@code jdk.JavaMonitorWait} and
 * {@code jdk.ThreadPark} by the class waited on, by site and by the frame below the site. Every
 * time counts only the part inside the recording's span, and every list is ranked by that time,
 * most first, then by name ({@code docs/design/waits.md}).
 *
 * @param info       the recording
 * @param siteKey    what a site is, in the words the report prints ({@code SiteKey.description})
 * @param totalNanos the time of every wait reported
 * @param waits      how many waits are reported
 * @param clipped    how many of them began before the span or ended after it, and count only the part inside
 * @param classes    the waits by event type and class waited on
 */
public record WaitReport(RecordingInfo info, String siteKey, long totalNanos, long waits, long clipped,
        List<Monitor> classes) {

    /** How many classes were waited on: a class waited on and parked on is one, though it is two rows. */
    public int distinctClasses() {
        final Set<String> names = new HashSet<>();
        for (final Monitor m : classes) {
            names.add(m.className());
        }
        return names.size();
    }

    /** What text and HTML say was waited: {@code 25.3 s across 1137 waits on 2 classes}. */
    public String summary() {
        final int n = distinctClasses();
        return Durations.format(totalNanos) + " across " + waits + (waits == 1 ? " wait" : " waits") + " on " + n
                + (n == 1 ? " class" : " classes");
    }

    /** What text and HTML say of the {@link #clipped} waits; only printed when there are any. */
    public String clippedNote() {
        return clipped + (clipped == 1 ? " wait" : " waits") + " began before the recording or outlived it; only the "
                + "part inside it is counted";
    }

    /** The event a wait came from; JSON names it by the constant's name. */
    public enum Kind {
        /** {@code Object.wait}: a {@code jdk.JavaMonitorWait} on its monitor's class. */
        WAIT,
        /** {@code LockSupport.park}: a {@code jdk.ThreadPark} on its blocker's class. */
        PARK;

        /** {@code wait} or {@code park}, as the report prints it. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One class waited on, by one event type.
     *
     * @param kind       the event type
     * @param className  the JVM name of the monitor's or blocker's class, or {@link Absent#NO_BLOCKER}
     * @param totalNanos its waits' time
     * @param waits      how many
     * @param maxNanos   the longest single wait
     * @param sites      its waits by site
     */
    public record Monitor(Kind kind, String className, long totalNanos, long waits, long maxNanos, List<Site> sites) {
    }

    /**
     * The frame a site key names in the waiting stacks, and the code below it.
     *
     * @param frame      {@code pkg.Class.method}, or {@link SiteKey#NO_STACK}
     * @param totalNanos the time of the waits whose stack names this site
     * @param waits      how many
     * @param maxNanos   the longest single wait
     * @param below      the same waits by {@link SiteKey#below}
     */
    public record Site(String frame, long totalNanos, long waits, long maxNanos, List<Below> below) {
    }

    /** A frame below a site, and the time and number of the waits it accounts for. */
    public record Below(String frame, long totalNanos, long waits) {
    }
}
