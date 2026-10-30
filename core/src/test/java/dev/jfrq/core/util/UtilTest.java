// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @Nested
    class GlobTest {
        @Test
        void starAndQuestionMark() {
            final Glob g = Glob.of("event-loop-*,worker-?");
            assertTrue(g.test("event-loop-2-1"));
            assertTrue(g.test("event-loop-"));
            assertTrue(g.test("worker-7"));
            assertFalse(g.test("worker-77"));
            assertFalse(g.test("main"));
            assertFalse(g.test(null));
            assertEquals("event-loop-*,worker-?", g.toString());
        }

        @Test
        void literalCharactersAreQuoted() {
            assertTrue(Glob.of("pool.1\\[a]").test("pool.1[a]"));
            assertFalse(Glob.of("pool.1\\[a]").test("poolX1[a]"));
            assertTrue(Glob.of("a\\*b").test("a*b"));
            assertFalse(Glob.of("a\\*b").test("axb"));
            assertTrue(Glob.of("a\\,b").test("a,b"));
            assertTrue(Glob.of("tail\\").test("tail\\"));
            // A bracket with no closing one is a literal, as in the shell: array class names stay matchable.
            assertTrue(Glob.of("Object[]").test("Object[]"));
            assertTrue(Glob.of("[B").test("[B"));
            assertTrue(Glob.of(".*").test(".*"));
            assertFalse(Glob.of(".*").test("abc"));
            // No regex quoting survives into the pattern: an escaped backslash is one backslash.
            assertTrue(Glob.of("\\\\E*").test("\\Efoo"));
            assertFalse(Glob.of("\\\\E*").test("Efoo"));
        }

        /** "Shell globs" means the bracket classes too, not only {@code *} and {@code ?}. */
        @Test
        void bracketClassesMatchOneCharacterOfASet() {
            final Glob g = Glob.of("worker-[0-2],io-[!0-9],x[^a]y,[]z]");
            assertTrue(g.test("worker-0"));
            assertTrue(g.test("worker-2"));
            assertFalse(g.test("worker-3"));
            assertFalse(g.test("worker-[0-2]"));
            assertTrue(g.test("io-a"));
            assertFalse(g.test("io-7"));
            assertTrue(g.test("xby"));
            assertFalse(g.test("xay"));
            assertTrue(g.test("]"));
            assertTrue(g.test("z"));
            // A comma inside brackets belongs to the set, not to the list.
            final Glob comma = Glob.of("a[,b]c");
            assertTrue(comma.test("a,c"));
            assertTrue(comma.test("abc"));
            // Regex metacharacters inside a set are plain characters; a reversed range is empty.
            assertTrue(Glob.of("[.\\]]").test("."));
            assertFalse(Glob.of("[.]").test("x"));
            assertTrue(Glob.of("p[-a]").test("p-"));
            assertFalse(Glob.of("[z-a]").test("m"));
            assertTrue(Glob.of("[!z-a]").test("m"));
        }

        /** Thread names are whatever the application set, line breaks included. */
        @Test
        void everyCharacterIsACharacter() {
            assertTrue(Glob.any().test("two\nlines"));
            assertTrue(Glob.of("a?b").test("a\nb"));
            assertTrue(Glob.of("pool-*").test("pool-\r\n1"));
            assertTrue(Glob.of("a[!x]b").test("a\nb"));
        }

        @Test
        void emptySpecMatchesNothingAndAnyMatchesEverything() {
            assertTrue(Glob.of("").isEmpty());
            assertTrue(Glob.of(null).isEmpty());
            assertTrue(Glob.of(" , ").isEmpty());
            assertFalse(Glob.of("").test("x"));
            assertTrue(Glob.any().test("anything at all"));
        }
    }

    @Nested
    class ClassNamesTest {
        @Test
        void prettyPrintsArraysAndPrimitives() {
            assertEquals("byte[]", ClassNames.pretty("[B"));
            assertEquals("int[][]", ClassNames.pretty("[[I"));
            assertEquals("java.lang.String[]", ClassNames.pretty("[Ljava.lang.String;"));
            assertEquals("boolean[]", ClassNames.pretty("[Z"));
            assertEquals("char[]", ClassNames.pretty("[C"));
            assertEquals("double[]", ClassNames.pretty("[D"));
            assertEquals("float[]", ClassNames.pretty("[F"));
            assertEquals("long[]", ClassNames.pretty("[J"));
            assertEquals("short[]", ClassNames.pretty("[S"));
            assertEquals("java.util.HashMap$Node", ClassNames.pretty("java.util.HashMap$Node"));
            assertEquals("?", ClassNames.pretty(null));
            assertEquals("?", ClassNames.pretty(""));
            assertEquals("Q[]", ClassNames.pretty("[Q"));
        }

        @Test
        void simpleDropsThePackage() {
            assertEquals("HashMap$Node", ClassNames.simple("java.util.HashMap$Node"));
            assertEquals("String[]", ClassNames.simple("[Ljava.lang.String;"));
            assertEquals("byte[]", ClassNames.simple("[B"));
        }
    }

    @Nested
    class BytesTest {
        @Test
        void formatsWithDecimalUnits() {
            assertEquals("0 B", Bytes.format(0));
            assertEquals("999 B", Bytes.format(999));
            assertEquals("1.00 KB", Bytes.format(1000));
            assertEquals("12.3 MB", Bytes.format(12_300_000));
            assertEquals("282 GB", Bytes.format(282_000_000_000L));
            assertEquals("1.50 TB", Bytes.format(1_500_000_000_000L));
            assertEquals("-850 B", Bytes.format(-850));
            // Long.MIN_VALUE has no positive counterpart; it must not recurse forever.
            assertEquals("-9223372 TB", Bytes.format(Long.MIN_VALUE));
            assertEquals("-infinity", Durations.format(Long.MIN_VALUE));
        }

        @Test
        void theUnitIsChosenAfterRounding() {
            assertEquals("999 KB", Bytes.format(999_499));
            assertEquals("1.00 MB", Bytes.format(999_999));
            assertEquals("1.00 GB", Bytes.format(999_999_999));
            assertEquals("10.0 KB", Bytes.format(9_995));
            assertEquals("100 KB", Bytes.format(99_950));
            assertEquals("1.00 MB/s", Bytes.rate(999_999.6));
        }

        @Test
        void signedAndRates() {
            assertEquals("+1.00 KB", Bytes.signed(1000));
            assertEquals("-1.00 KB", Bytes.signed(-1000));
            assertEquals("0 B", Bytes.signed(0));
            assertEquals("45.1 MB/s", Bytes.rate(45_100_000.0));
            assertEquals("+45.1 MB/s", Bytes.signedRate(45_100_000.0));
            assertEquals("-45.1 MB/s", Bytes.signedRate(-45_100_000.0));
            assertEquals("0 B/s", Bytes.signedRate(0));
        }
    }

    @Nested
    class TextTableTest {
        @Test
        void alignsColumnsAndRightAlignsNumerics() {
            final String out = new TextTable("Name", "Count", "Note").numeric(1)
                    .row("alpha", 1, "x")
                    .row("b", 12345, "")
                    .render("> ");
            assertEquals("""
                    > Name   Count  Note
                    > alpha      1  x
                    > b      12345  \n""", out);
        }

        @Test
        void rejectsWrongArity() {
            final TextTable t = new TextTable("a", "b");
            assertThrows(IllegalArgumentException.class, () -> t.row("only one"));
            assertEquals(0, t.size());
            t.row(null, 2);
            assertEquals(1, t.size());
            assertTrue(t.render().startsWith("a  b\n"));
        }
    }
}
