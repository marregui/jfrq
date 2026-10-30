// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    static Frame jdk(final String type, final String method, final int line) {
        return new Frame(type, method, line, "JIT compiled");
    }

    static Frame app(final String method, final int line) {
        return new Frame("dev.app.Handler", method, line, "Interpreted");
    }

    @Nested
    class IntervalTest {
        @Test
        void overlapAndContainment() {
            final Interval a = new Interval(10, 20);
            final Interval b = new Interval(15, 30);
            assertEquals(5, a.overlap(b));
            assertEquals(5, b.overlap(a));
            assertTrue(a.overlaps(b));
            assertEquals(0, a.overlap(new Interval(20, 25)));
            assertFalse(a.overlaps(new Interval(20, 25)));
            assertTrue(a.contains(10));
            assertFalse(a.contains(20));
            assertEquals(new Interval(10, 30), a.union(b));
            assertEquals(10, a.duration());
            assertEquals(new Interval(5, 12), Interval.ofDuration(5, 7));
        }

        @Test
        void ordersByStartThenEnd() {
            assertTrue(new Interval(1, 5).compareTo(new Interval(2, 3)) < 0);
            assertTrue(new Interval(1, 5).compareTo(new Interval(1, 6)) < 0);
            assertEquals(0, new Interval(1, 5).compareTo(new Interval(1, 5)));
        }

        @Test
        void rejectsNegativeLength() {
            assertThrows(IllegalArgumentException.class, () -> new Interval(5, 4));
        }

        @Test
        void clampsToAWindowAtEitherEnd() {
            final Interval window = new Interval(10, 20);
            assertEquals(new Interval(10, 15), new Interval(5, 15).clampTo(window));
            assertEquals(new Interval(15, 20), new Interval(15, 25).clampTo(window));
            assertEquals(window, new Interval(0, 100).clampTo(window));
            // Wholly inside: the same object, so clipping a report allocates nothing in the common case.
            final Interval inside = new Interval(12, 14);
            assertSame(inside, inside.clampTo(window));
            assertSame(window, window.clampTo(window));
            // Disjoint on either side collapses to a point inside the window, never to a negative length.
            assertEquals(0, new Interval(0, 5).clampTo(window).duration());
            assertEquals(10, new Interval(0, 5).clampTo(window).start());
            assertEquals(0, new Interval(30, 40).clampTo(window).duration());
            assertEquals(20, new Interval(30, 40).clampTo(window).start());
        }
    }

    @Nested
    class FrameTest {
        @Test
        void prettyLooksLikeAStackTraceLine() {
            assertEquals("java.lang.Thread.sleep(Thread.java:509)", jdk("java.lang.Thread", "sleep", 509).pretty());
            assertEquals("sun.nio.ch.KQueue.poll(Native Method)",
                    new Frame("sun.nio.ch.KQueue", "poll", 0, "Native").pretty());
            assertEquals("java.util.HashMap$Node.hash(HashMap.java)", jdk("java.util.HashMap$Node", "hash", 0).pretty());
            assertEquals("dev.app.Handler.read(Handler.java:12)", app("read", 12).toString());
        }

        @Test
        void hiddenLambdaClassesAreShortened() {
            final Frame f = new Frame("dev.app.Background$$Lambda.0x00003800010a1e10", "run", 0, "JIT compiled");
            assertTrue(f.isHidden());
            assertEquals("dev.app.Background$$Lambda.run(lambda)", f.pretty());
            final Frame g = new Frame("dev.app.Hidden/0x1234", "run", 0, "JIT compiled");
            assertTrue(g.isHidden());
            assertEquals("dev.app.Hidden.run(hidden)", g.pretty());
            assertEquals("dev.app.Hidden.run", g.stableName());
            assertFalse(app("x", 1).isHidden());
        }

        @Test
        void aHiddenClassIsNamedAlikeInEveryRun() {
            assertEquals("java.util.regex.Pattern$$Lambda", Frame.stableClass("java.util.regex.Pattern$$Lambda.0x800000030"));
            assertEquals("dev.app.Handler$$Lambda", Frame.stableClass("dev.app.Handler$$Lambda$14/0x0000000800c02a00"));
            assertEquals("dev.app.Handler$$Lambda", Frame.stableClass("dev.app.Handler$$Lambda"));
            assertEquals("java.lang.invoke.LambdaForm$MH",
                    Frame.stableClass("java.lang.invoke.LambdaForm$MH.0x000007e00115c400"));
            assertEquals("dev.app.Hidden", Frame.stableClass("dev.app.Hidden/0xAbC1"));
            // Anything that is not a hex address after a '.' or a '/' is a name, and stays.
            for (final String name : List.of("[B", "java.lang.String", "dev.app.v0x12", "dev.app.Hidden.0x",
                    "dev.app.Hidden.0x12g", "0x12", "a.0x")) {
                assertSame(name, Frame.stableClass(name), name);
            }
        }

        @Test
        void howTheJitRanTheMethodIsNotPartOfTheFrame() {
            // The same line sampled interpreted, then compiled, then inlined is one frame: the kind
            // is never printed, and keeping it split one allocation site into stacks that print alike.
            final Frame interpreted = new Frame("org.lib.NodeId", "parse", 453, "Interpreted");
            final Frame compiled = new Frame("org.lib.NodeId", "parse", 453, "JIT compiled");
            final Frame inlined = new Frame("org.lib.NodeId", "parse", 453, "Inlined");
            assertEquals(interpreted, compiled);
            assertEquals(interpreted, inlined);
            assertEquals(interpreted.hashCode(), compiled.hashCode());
            assertEquals(new Stack(List.of(interpreted), false), new Stack(List.of(compiled), false));
            // The native bit stays: it is how a frame without a line prints.
            final Frame poll = new Frame("sun.nio.ch.KQueue", "poll", 0, Frame.NATIVE);
            assertTrue(poll.isNative());
            assertFalse(interpreted.isNative());
            assertFalse(poll.equals(new Frame("sun.nio.ch.KQueue", "poll", 0, "JIT compiled")));

            final Interner interner = new Interner();
            assertSame(interner.frame(interpreted), interner.frame(compiled));
            assertSame(interner.frame(compiled), interner.frame(inlined));
            assertEquals(1, interner.distinctFrames());
            assertSame(interner.frame(poll), interner.frame(new Frame("sun.nio.ch.KQueue", "poll", 0, "Native")));
            assertEquals(2, interner.distinctFrames());
        }

        @Test
        void jdkDetection() {
            assertTrue(jdk("java.lang.Thread", "run", 1).isJdk());
            assertTrue(jdk("jdk.internal.misc.Unsafe", "park", 1).isJdk());
            assertTrue(jdk("sun.nio.ch.KQueue", "poll", 1).isJdk());
            assertTrue(jdk("javax.net.ssl.SSLSocket", "read", 1).isJdk());
            assertTrue(jdk("com.sun.crypto.provider.X", "y", 1).isJdk());
            assertFalse(app("x", 1).isJdk());
            assertFalse(jdk("io.netty.channel.NioEventLoop", "run", 1).isJdk());
        }
    }

    @Nested
    class StackTest {
        final Stack stack = new Stack(List.of(
                new Frame("sun.nio.ch.SocketDispatcher", "read0", 0, "Native"),
                jdk("java.io.BufferedReader", "readLine", 333),
                app("lookup", 80),
                app("channelRead0", 55),
                new Frame("io.netty.channel.NioEventLoop", "run", 500, "JIT compiled")), false);

        @Test
        void culpritIsTheInnermostNonJdkFrame() {
            assertEquals(app("lookup", 80), stack.culprit().orElseThrow());
            assertEquals("sun.nio.ch.SocketDispatcher.read0", stack.top().orElseThrow().qualifiedName());
            final Stack allJdk = new Stack(List.of(jdk("java.lang.Thread", "sleep", 1)), false);
            assertEquals(jdk("java.lang.Thread", "sleep", 1), allJdk.culprit().orElseThrow());
            assertTrue(Stack.EMPTY.culprit().isEmpty());
            assertTrue(Stack.EMPTY.top().isEmpty());
            assertTrue(Stack.EMPTY.isEmpty());
        }

        @Test
        void headTruncates() {
            assertEquals(2, stack.head(2).frames().size());
            assertTrue(stack.head(2).isTruncated());
            assertEquals(stack, stack.head(10));
        }

        @Test
        void prettyShowsTheCulpritEvenWhenItLiesBeyondTheLimit() {
            final String out = stack.pretty("  ", 1);
            assertEquals("""
                      at sun.nio.ch.SocketDispatcher.read0(Native Method)
                      ... 1 more
                      at dev.app.Handler.lookup(Handler.java:80)
                      ... 2 more
                    """, out);
        }

        @Test
        void prettyWithoutElisionWhenEverythingFits() {
            final String out = stack.pretty("", 10);
            assertEquals(5, out.lines().count());
            assertFalse(out.contains("more"));
            final Stack truncated = new Stack(stack.frames(), true);
            assertTrue(truncated.pretty("", 10).endsWith("... 0 more\n"));
        }

        @Test
        void prettyWhenCulpritIsAlreadyVisible() {
            final String out = stack.pretty("", 3);
            assertTrue(out.contains("Handler.lookup"));
            assertTrue(out.endsWith("... 2 more\n"));
            assertEquals(4, out.lines().count());
        }
    }

    @Test
    void threadRefUsesNameForDisplay() {
        final ThreadRef t = new ThreadRef(7, "event-loop-1");
        assertEquals("event-loop-1", t.toString());
        assertEquals(new ThreadRef(7, "event-loop-1"), t);
        assertFalse(t.isVirtual());
        assertTrue(new ThreadRef(8, "vt-1", true).isVirtual());
        assertNull(ThreadRef.of(null));
    }
}
