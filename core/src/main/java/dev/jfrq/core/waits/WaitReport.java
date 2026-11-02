// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.waits;

import java.util.List;
import java.util.Locale;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.jfr.EventKinds;
import dev.jfrq.core.jfr.RecordingInfo;

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

    /** Frames listed below each site by text and HTML; the rest are summed on one line. */
    public static final int BELOW_SHOWN = 3;

    /** What a park with no blocker object ({@code LockSupport.parkNanos}) is waiting on. */
    public static final String NO_BLOCKER = "(no blocker)";

    /** The event a wait came from. */
    public enum Kind {
        /** {@code Object.wait}: a {@code jdk.JavaMonitorWait} on its monitor's class. */
        WAIT(EventKinds.JAVA_MONITOR_WAIT),
        /** {@code LockSupport.park}: a {@code jdk.ThreadPark} on its blocker's class. */
        PARK(EventKinds.THREAD_PARK);

        private final int eventKind;

        Kind(final int eventKind) {
            this.eventKind = eventKind;
        }

        /** The JFR event type: {@code jdk.JavaMonitorWait} or {@code jdk.ThreadPark}. */
        public String eventType() {
            return EventKinds.nameOf(eventKind);
        }

        /** {@code wait} or {@code park}, as the report prints it. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One class waited on, by one event type.
     *
     * @param kind       the event type
     * @param className  the JVM name of the monitor's or blocker's class, or {@link #NO_BLOCKER}
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
