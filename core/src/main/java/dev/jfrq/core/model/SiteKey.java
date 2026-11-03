// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.model;

import java.util.List;

/**
 * What a stack is called when sites are ranked, and therefore which stacks are one row:
 * {@code alloc --sites}, {@code hot}, {@code procs}, {@code waits} and the sites of
 * {@code stalls} share it.
 *
 * <p>A single logical allocation reaches the sampler down many different paths: the same
 * method allocating on two of its lines, the same line reached through a different depth of
 * library frames, a string built by {@code substring} here and by {@code copyOfRange} there.
 * Ranked one path per row, a site that costs a fifth of the heap appears as a dozen rows of
 * two percent each and reads as noise. The key decides what is summed.
 *
 * <p>{@link #culpritMethod()} is the default: the innermost frame that is not JDK code,
 * without its line number. It needs nothing from the caller and merges the paths above the
 * method that did the allocating.
 *
 * <p>{@link #inPackages(List)} is what {@code --app} builds: the innermost frame in one of
 * the named packages, so the allocation is attributed to the code the reader owns rather
 * than to the library it called. That one cannot be the default, because nothing in a
 * recording says which packages those are — a JVM started from a jar records
 * {@code sun.java.command = app.jar} and no package name anywhere.
 */
@FunctionalInterface
public interface SiteKey {

    /** What a stack with no frames at all is called. */
    String NO_STACK = "(no stack)";

    /** What {@link #below} says for a stack whose site is its innermost frame. */
    String AT_SITE = "(the site itself)";

    /** The default key, held once: a strategy object belongs to the run, not to the call site. */
    SiteKey CULPRIT_METHOD = Stack::culpritOrTopIndex;

    /**
     * Where in {@code stack} the frame this key names is, innermost first, or {@code -1} for a
     * stack with no frames. {@code hot} reads the frames below it ({@code docs/design/hot.md}).
     */
    int frameIndex(Stack stack);

    /** The name this stack is ranked under; stacks with the same name are one row. */
    default String of(final Stack stack) {
        final int index = frameIndex(stack);
        return index < 0 ? NO_STACK : method(stack.frameQuick(index));
    }

    /**
     * Whether this key was asked for with {@code --app}: it names the innermost frame in the
     * packages given rather than the default culprit. A command whose output differs under
     * {@code --app} reads this, not the key's identity.
     */
    default boolean fromApp() {
        return false;
    }

    /** What the rows are, in the words the report prints above them. */
    default String description() {
        return "the innermost frame outside the JDK, or the top frame of a stack that is all JDK";
    }

    /**
     * What a stack was doing below its site, the frame at {@code at}: the outermost frame under
     * the site that is not JDK code, so a container that only dispatches ({@code paint} calling
     * its children's) is split by the code it dispatched to; else the frame the site called, where
     * everything below it is JDK code ({@code invokeLater}, not the {@code HashMap} inside it);
     * {@link #AT_SITE} when the site is the leaf. {@code hot} and {@code waits} split their sites by it.
     */
    static String below(final Stack stack, final int at) {
        if (at <= 0) {
            return AT_SITE;
        }
        for (int i = at - 1; i >= 0; i--) {
            if (!stack.frameQuick(i).isJdk()) {
                return stack.frameQuick(i).stableName();
            }
        }
        return stack.frameQuick(at - 1).stableName();
    }

    /** The innermost non-JDK frame, without its line: {@code com.example.Parser.parse}. */
    static SiteKey culpritMethod() {
        return CULPRIT_METHOD;
    }

    /**
     * The innermost frame whose class is in one of the packages {@code prefixes} names,
     * falling back to {@link #culpritMethod()} for a stack that never enters them. A prefix
     * matches on a name boundary, a {@code '.'} or a {@code '$'}: {@code io.netty} is the
     * class or package {@code io.netty} and everything under {@code io.netty.}, never
     * {@code io.nettyx}; {@code com.x.Handler} covers its nested classes and lambdas
     * ({@code com.x.Handler$Inner}, {@code com.x.Handler$$Lambda}), never
     * {@code com.x.HandlerFactory}. A prefix that ends in either already is its own boundary.
     */
    static SiteKey inPackages(final List<String> prefixes) {
        return new SiteKey() {

            @Override
            public int frameIndex(final Stack stack) {
                for (int i = 0, n = stack.depth(); i < n; i++) {
                    final String type = stack.frameQuick(i).type();
                    for (final String prefix : prefixes) {
                        if (inPackage(type, prefix)) {
                            return i;
                        }
                    }
                }
                return CULPRIT_METHOD.frameIndex(stack);
            }

            @Override
            public boolean fromApp() {
                return true;
            }

            @Override
            public String description() {
                return "the innermost frame in " + String.join(" or ", prefixes)
                        + ", or outside the JDK where there is none, or the top frame of a stack that is all JDK";
            }
        };
    }

    /** Whether {@code type} is {@code prefix} itself or a name under it: a member, a nested class, a lambda. */
    private static boolean inPackage(final String type, final String prefix) {
        if (!type.startsWith(prefix)) {
            return false;
        }
        if (type.length() == prefix.length() || prefix.endsWith(".") || prefix.endsWith("$")) {
            return true;
        }
        final char next = type.charAt(prefix.length());
        return next == '.' || next == '$';
    }

    /** Named without a hidden class's per-JVM address, so a lambda's row is the same row in two recordings. */
    private static String method(final Frame frame) {
        return frame.stableName();
    }
}
