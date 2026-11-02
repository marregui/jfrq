// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.hot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.alloc.SiteKey;
import dev.jfrq.core.jfr.RecordingInfo;
import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Interval;
import dev.jfrq.core.model.Stack;
import dev.jfrq.core.model.ThreadRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `hot`'s analysis on hand-built samples: families, sites, the frame below a site, leaves and the timeline.
class HotCollectorTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long T0 = 1_000 * SECOND;

    /** Innermost first, each {@code "pkg.Class.method"}. */
    private static Stack stack(final String... frames) {
        final Frame[] out = new Frame[frames.length];
        for (int i = 0; i < frames.length; i++) {
            final int dot = frames[i].lastIndexOf('.');
            out[i] = new Frame(frames[i].substring(0, dot), frames[i].substring(dot + 1), i + 1, "JIT compiled");
        }
        return new Stack(List.of(out), false);
    }

    private static RecordingInfo info() {
        return new RecordingInfo(Path.of("t.jfr"), new Interval(T0, T0 + 600 * SECOND), 1, Map.of(), Map.of(), Set.of(),
                List.of());
    }

    private static HotReport finish(final HotCollector c) {
        c.finish(info());
        return c.report();
    }

    /// Pool threads fold into one family, and a container site is split by the first non-JDK code below it.
    @Test
    void poolThreadsAreOneFamilyAndAContainerIsSplitByTheCodeItDispatchedTo() {
        final HotCollector c = new HotCollector(SiteKey.inPackages(List.of("com.app")), 60 * SECOND);
        final ThreadRef edt = new ThreadRef(1, "AWT-EventQueue-0");
        final ThreadRef w1 = new ThreadRef(2, "pool-1-thread-1");
        final ThreadRef w2 = new ThreadRef(3, "pool-1-thread-2");
        // A container's paint: JDK dispatch, then a library's painter, then JDK rendering at the leaf.
        final Stack viaLib = stack("sun.java2d.Renderer.end", "org.lib.TabsUI.update", "javax.swing.JComponent.paint",
                "com.app.Tabs.paint", "java.awt.EventQueue.dispatch");
        // Only JDK code below the site: the frame the site called says what it asked the JDK for.
        final Stack jdkOnly = stack("sun.java2d.Renderer.end", "javax.swing.JComponent.paint", "com.app.Tabs.paint");
        // The site is the leaf.
        final Stack self = stack("com.app.Worker.run", "java.lang.Thread.run");
        for (int i = 0; i < 3; i++) {
            c.sample(edt, T0 + i * SECOND, viaLib);
        }
        c.sample(edt, T0 + 3 * SECOND, jdkOnly);
        c.sample(w1, T0 + 61 * SECOND, self);
        c.sample(w2, T0 + 62 * SECOND, self);

        final HotReport r = finish(c);
        assertEquals(6, r.samples());
        assertEquals(List.of("AWT-EventQueue-N", "pool-N-thread-N"),
                r.groups().stream().map(HotReport.Group::name).toList());
        final HotReport.Group edtGroup = r.groups().getFirst();
        assertEquals(4, edtGroup.samples());
        assertEquals(1, edtGroup.threads());
        final HotReport.Site tabs = edtGroup.sites().getFirst();
        assertEquals("com.app.Tabs.paint", tabs.frame());
        assertEquals(4, tabs.samples());
        assertEquals(List.of(new HotReport.Count("org.lib.TabsUI.update", 3),
                new HotReport.Count("javax.swing.JComponent.paint", 1)),
                tabs.next());

        final HotReport.Group pool = r.groups().get(1);
        assertEquals(2, pool.threads());
        assertEquals(List.of(new HotReport.Count(SiteKey.AT_SITE, 2)), pool.sites().getFirst().next());

        assertEquals(new HotReport.Count("sun.java2d.Renderer.end", 4), r.leaves().getFirst());
        assertEquals(new HotReport.Count("com.app.Worker.run", 2), r.leaves().get(1));

        // Minute buckets from the minute T0 falls in: the EDT in the first, the pool in the second.
        assertEquals(Math.floorDiv(T0, 60 * SECOND) * 60 * SECOND, r.originNanos());
        assertEquals(2, r.buckets());
        assertEquals(List.of(4L, 0L), edtGroup.timeline());
        assertEquals(List.of(0L, 2L), pool.timeline());
        assertEquals(2, r.bucketTotal(1));
        assertEquals(1.0 / 3, r.share(pool.samples()), 1e-9);
    }

    /// Without `--app` the site is the culprit frame; a stack with no frames is a row of its own.
    @Test
    void withoutAppTheSiteIsTheInnermostFrameOutsideTheJdkAndAnEmptyStackIsNamedSo() {
        final HotCollector c = new HotCollector(SiteKey.culpritMethod(), SECOND);
        final ThreadRef t = new ThreadRef(1, "main");
        c.sample(t, T0, stack("java.util.HashMap.get", "org.lib.Cache.get", "com.app.Main.main"));
        c.sample(t, T0, Stack.EMPTY);
        final HotReport r = finish(c);
        final List<HotReport.Site> sites = r.groups().getFirst().sites();
        // One sample each: ties rank by name, and '(' sorts before letters.
        assertEquals(SiteKey.NO_STACK, sites.getFirst().frame());
        assertEquals("org.lib.Cache.get", sites.get(1).frame());
        assertEquals(List.of(new HotReport.Count("java.util.HashMap.get", 1)), sites.get(1).next());
        assertTrue(r.leaves().contains(new HotReport.Count(SiteKey.NO_STACK, 1)), r.leaves().toString());
    }

    /// A bucket too fine for the span is widened; a recording with no samples is an empty report.
    @Test
    void aBucketThatWouldMakeTooManyIsWidenedAndNoSamplesIsAnEmptyReport() {
        final HotCollector c = new HotCollector(SiteKey.culpritMethod(), 1);
        final ThreadRef t = new ThreadRef(1, "main");
        c.sample(t, T0, stack("com.app.Main.main"));
        c.sample(t, T0 + 3600 * SECOND, stack("com.app.Main.main"));
        final HotReport r = finish(c);
        assertTrue(r.buckets() <= HotCollector.MAX_BUCKETS, String.valueOf(r.buckets()));
        assertEquals(List.of(1L, 1L), List.of(r.groups().getFirst().timeline().getFirst(),
                r.groups().getFirst().timeline().getLast()));

        final HotReport none = finish(new HotCollector(SiteKey.culpritMethod(), SECOND));
        assertEquals(0, none.samples());
        assertEquals(0, none.buckets());
        assertTrue(none.groups().isEmpty());

        // Widened to a multiple of the bucket asked for, so the buckets stay on the clock: 7 s over
        // 10,000 s would be 1,429 buckets; 14 s is the smallest multiple within 1,000.
        final HotCollector wide = new HotCollector(SiteKey.culpritMethod(), 7 * SECOND);
        wide.sample(t, T0 + 3 * SECOND, stack("com.app.Main.main"));
        wide.sample(t, T0 + 10_000 * SECOND, stack("com.app.Main.main"));
        final HotReport widened = finish(wide);
        assertEquals(14 * SECOND, widened.bucketNanos());
        assertEquals(0, widened.originNanos() % (14 * SECOND));
        assertEquals(2, widened.samples());
        assertEquals(0, none.share(0));
        assertThrows(IllegalArgumentException.class, () -> new HotCollector(SiteKey.culpritMethod(), 0));
        assertThrows(IllegalStateException.class, () -> new HotCollector(SiteKey.culpritMethod(), SECOND).report());
    }
}
