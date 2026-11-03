// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.waits;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.jfr.Absent;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.SiteKey;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;
import dev.jfrq.core.util.Glob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `waits`' analysis on hand-built waits and parks: filters, classes, sites, the frame below a site, clipping.
class WaitCollectorTest {

    private static final long MS = 1_000_000L;
    private static final long T0 = 1_000_000 * MS;
    private static final long SPAN_NANOS = 60_000 * MS;

    /** Innermost first, each {@code "pkg.Class.method"}. */
    private static Stack stack(final String... frames) {
        final Frame[] out = new Frame[frames.length];
        for (int i = 0; i < frames.length; i++) {
            final int dot = frames[i].lastIndexOf('.');
            out[i] = new Frame(frames[i].substring(0, dot), frames[i].substring(dot + 1), i + 1, "JIT compiled");
        }
        return new Stack(List.of(out), false);
    }

    /// A wait read from an event, through the two steps `accept` takes once the event's fields are read.
    private static void waited(final WaitCollector c, final WaitReport.Kind kind, final ThreadRef thread,
            final String cls, final long startNanos, final long endNanos, final Stack stack) {
        final WaitCollector.Waits into = c.watches(thread) ? c.waitsOf(kind, cls) : null;
        if (into != null) {
            into.add(startNanos, endNanos, stack);
        }
    }

    private static WaitReport finish(final WaitCollector c) {
        c.finish(new RecordingInfo(Path.of("t.jfr"), new Interval(T0, T0 + SPAN_NANOS), 1, Map.of(), Map.of(),
                Set.of(), List.of()));
        return c.report();
    }

    /// The waiting thread's flush waits are split by site and by the code below it, the way `hot` splits samples;
    /// another thread's waits and another class are left out by the filters.
    @Test
    void waitsBySiteAndTheCodeBelow() {
        final WaitCollector c = new WaitCollector(Glob.of("AWT-EventQueue-*"), Glob.of("*QueueFlusher"),
                SiteKey.inPackages(List.of("javax.swing.text.html")));
        final ThreadRef edt = new ThreadRef(1, "AWT-EventQueue-0");
        final ThreadRef flusher = new ThreadRef(2, "Java2D Queue Flusher");
        final String queue = "sun.java2d.metal.MTLRenderQueue$QueueFlusher";
        final Stack border = stack("java.lang.Object.wait", "sun.java2d.metal.MTLRenderQueue.flushNow",
                "java.awt.Graphics.fillPolygon", "javax.swing.text.html.CSSBorder$SolidPainter.paint",
                "javax.swing.text.html.CSSBorder.paintBorder", "com.app.Veil.paint");
        final Stack glyphs = stack("java.lang.Object.wait", "sun.java2d.metal.MTLRenderQueue.flushNow",
                "org.lib.Proxy.draw", "javax.swing.text.html.ParagraphView.paint");
        final Stack bare = stack("java.lang.Object.wait", "sun.java2d.metal.MTLRenderQueue.flushNow");
        waited(c, WaitReport.Kind.WAIT, edt, queue, T0, T0 + 30 * MS, border);
        waited(c, WaitReport.Kind.WAIT, edt, queue, T0 + 100 * MS, T0 + 150 * MS, border);
        waited(c, WaitReport.Kind.WAIT, edt, queue, T0 + 200 * MS, T0 + 220 * MS, glyphs);
        waited(c, WaitReport.Kind.WAIT, edt, queue, T0 + 300 * MS, T0 + 310 * MS, bare);
        // Left out: the notifier's side, and a class the filter does not name.
        waited(c, WaitReport.Kind.WAIT, flusher, queue, T0, T0 + 500 * MS, border);
        waited(c, WaitReport.Kind.WAIT, edt, "java.lang.Object", T0, T0 + 500 * MS, border);

        final WaitReport r = finish(c);
        assertEquals(110 * MS, r.totalNanos());
        assertEquals(4, r.waits());
        assertEquals(0, r.clipped());
        assertEquals(1, r.classes().size());
        final WaitReport.Monitor m = r.classes().getFirst();
        assertEquals(WaitReport.Kind.WAIT, m.kind());
        assertEquals(queue, m.className());
        assertEquals(50 * MS, m.maxNanos());
        assertEquals(List.of(
                new WaitReport.Site("javax.swing.text.html.CSSBorder$SolidPainter.paint", 80 * MS, 2, 50 * MS,
                        List.of(new WaitReport.Below("java.awt.Graphics.fillPolygon", 80 * MS, 2))),
                new WaitReport.Site("javax.swing.text.html.ParagraphView.paint", 20 * MS, 1, 20 * MS,
                        List.of(new WaitReport.Below("org.lib.Proxy.draw", 20 * MS, 1))),
                // No frame outside the JDK: the culprit key's fallback names the leaf, which is the site itself.
                new WaitReport.Site("java.lang.Object.wait", 10 * MS, 1, 10 * MS,
                        List.of(new WaitReport.Below(SiteKey.AT_SITE, 10 * MS, 1)))),
                m.sites());
    }

    /// A wait and a park on one class are two rows; a park with no blocker class is named so and matched by that
    /// name; a class filter matches the printed name too.
    @Test
    void waitsAndParksAreRowsOfTheirOwn() {
        final WaitCollector c = new WaitCollector(Glob.any(), Glob.of("*Lock*,byte[],(no blocker object)"),
                SiteKey.culpritMethod());
        final ThreadRef t = new ThreadRef(1, "worker");
        final Stack s = stack("jdk.internal.misc.Unsafe.park", "com.app.Worker.run");
        final String lock = "java.util.concurrent.locks.ReentrantLock$NonfairSync";
        waited(c, WaitReport.Kind.PARK, t, lock, T0, T0 + 40 * MS, s);
        waited(c, WaitReport.Kind.WAIT, t, lock, T0, T0 + 30 * MS, s);
        waited(c, WaitReport.Kind.PARK, t, "[B", T0, T0 + 20 * MS, s);
        waited(c, WaitReport.Kind.PARK, t, null, T0, T0 + 10 * MS, s);
        waited(c, WaitReport.Kind.PARK, t, "java.lang.Object", T0, T0 + 90 * MS, s);

        final WaitReport r = finish(c);
        assertEquals(3, r.distinctClasses());
        assertEquals("100 ms across 4 waits on 3 classes", r.summary());
        assertEquals(List.of("park " + lock, "wait " + lock, "park [B", "park " + Absent.NO_BLOCKER),
                r.classes().stream().map(m -> m.kind().label() + " " + m.className()).toList());
        // Only JDK code below the site: the frame it called.
        assertEquals(List.of(new WaitReport.Site("com.app.Worker.run", 40 * MS, 1, 40 * MS,
                List.of(new WaitReport.Below("jdk.internal.misc.Unsafe.park", 40 * MS, 1)))),
                r.classes().getFirst().sites());
    }

    /// A wait that crosses an end of the span counts only the part inside it and is counted as clipped; one
    /// wholly outside is not reported.
    @Test
    void waitsAreClippedToTheSpan() {
        final WaitCollector c = new WaitCollector(Glob.any(), Glob.any(), SiteKey.culpritMethod());
        final ThreadRef t = new ThreadRef(1, "worker");
        final Stack s = stack("java.lang.Object.wait", "com.app.Worker.run");
        waited(c, WaitReport.Kind.WAIT, t, "java.lang.Object", T0 - 40 * MS, T0 + 10 * MS, s);
        waited(c, WaitReport.Kind.WAIT, t, "java.lang.Object", T0 + SPAN_NANOS - 5 * MS, T0 + SPAN_NANOS + 50 * MS, s);
        waited(c, WaitReport.Kind.WAIT, t, "java.lang.Object", T0 + 10 * MS, T0 + 30 * MS, s);
        waited(c, WaitReport.Kind.WAIT, t, "java.lang.Object", T0 - 90 * MS, T0 - 50 * MS, s);

        final WaitReport r = finish(c);
        assertEquals(3, r.waits());
        assertEquals(2, r.clipped());
        assertEquals(35 * MS, r.totalNanos());
        assertEquals(20 * MS, r.classes().getFirst().maxNanos());
    }

    /// An end read before its start, a clock step between the two reads, is a wait of no time, not an error.
    @Test
    void anEndBeforeItsStartIsAWaitOfNoTime() {
        final WaitCollector c = new WaitCollector(Glob.any(), Glob.any(), SiteKey.culpritMethod());
        final ThreadRef t = new ThreadRef(1, "worker");
        final Stack s = stack("java.lang.Object.wait", "com.app.Worker.run");
        waited(c, WaitReport.Kind.WAIT, t, "java.lang.Object", T0 + 20 * MS, T0 + 10 * MS, s);
        waited(c, WaitReport.Kind.WAIT, t, "java.lang.Object", T0 + 30 * MS, T0 + 40 * MS, s);

        final WaitReport r = finish(c);
        assertEquals(1, r.waits());
        assertEquals(10 * MS, r.totalNanos());
        assertEquals("10.0 ms across 1 wait on 1 class", r.summary());
    }

    /// Nothing kept is an empty report, and asking before `finish` is an error.
    @Test
    void noWaits() {
        final WaitCollector c = new WaitCollector(Glob.of("nobody"), Glob.any(), SiteKey.culpritMethod());
        assertThrows(IllegalStateException.class, c::report);
        waited(c, WaitReport.Kind.WAIT, new ThreadRef(1, "worker"), "java.lang.Object", T0, T0 + MS,
                stack("com.app.Worker.run"));
        final WaitReport r = finish(c);
        assertEquals(0, r.waits());
        assertEquals(List.of(), r.classes());
    }
}
