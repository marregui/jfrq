// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.alloc;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jfrq.core.model.Frame;
import dev.jfrq.core.model.Stack;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiteKeyTest {

    private static Stack stack(final String... types) {
        final Frame[] frames = new Frame[types.length];
        for (int i = 0; i < types.length; i++) {
            frames[i] = new Frame(types[i], "m", i, "JIT compiled");
        }
        return new Stack(List.of(frames), false);
    }

    @Test
    void aPrefixMatchesOnAPackageBoundary() {
        // Innermost first: the JDK, a library, a handler in io.nettyx, the application.
        final Stack stack = stack("java.lang.String", "org.lib.Codec", "io.nettyx.Handler", "com.app.Main");
        final String fallback = "org.lib.Codec.m";
        assertEquals(fallback, SiteKey.culpritMethod().of(stack));
        // io.nettyx is neither io.nett nor under io.netty: both fall back to the culprit.
        assertEquals(fallback, SiteKey.inPackages(List.of("io.nett")).of(stack));
        assertEquals(fallback, SiteKey.inPackages(List.of("io.netty")).of(stack));
        assertEquals(fallback, SiteKey.inPackages(List.of("com.ap")).of(stack));
        assertEquals("io.nettyx.Handler.m", SiteKey.inPackages(List.of("io.nettyx")).of(stack));
        assertEquals("io.nettyx.Handler.m", SiteKey.inPackages(List.of("io")).of(stack));
        // A prefix ending in a dot is its own boundary, and a whole class name matches itself.
        assertEquals("io.nettyx.Handler.m", SiteKey.inPackages(List.of("io.")).of(stack));
        assertEquals("com.app.Main.m", SiteKey.inPackages(List.of("com.app.Main")).of(stack));
        assertEquals("com.app.Main.m", SiteKey.inPackages(List.of("io.netty", "com.app")).of(stack));
    }

    @Test
    void aClassPrefixCoversItsNestedClassesAndLambdas() {
        // A nested class and a lambda are named after the class with '$': both are under it.
        final Stack inner = stack("java.lang.String", "org.lib.Codec", "com.x.Handler$Inner");
        final Stack lambda = stack("java.lang.String", "org.lib.Codec", "com.x.Handler$$Lambda/0x1234");
        final Stack other = stack("java.lang.String", "org.lib.Codec", "com.x.HandlerFactory");
        final SiteKey handler = SiteKey.inPackages(List.of("com.x.Handler"));
        assertEquals("com.x.Handler$Inner.m", handler.of(inner));
        // Without its per-JVM address, so the row is the same row in another recording.
        assertEquals("com.x.Handler$$Lambda.m", handler.of(lambda));
        // A '$' is a boundary, not a wildcard: HandlerFactory is still another class.
        assertEquals("org.lib.Codec.m", handler.of(other));
        assertEquals("org.lib.Codec.m", SiteKey.inPackages(List.of("com.x.Handl")).of(other));
        // A prefix ending in '$' is its own boundary, as one ending in '.' is.
        assertEquals("com.x.Handler$Inner.m", SiteKey.inPackages(List.of("com.x.Handler$")).of(inner));
    }
}
