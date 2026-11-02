// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Class histograms in the format `jcmd GC.class_histogram` prints, and the growth across them. The fixtures are
/// rows of two histograms of a Swing application taken an hour apart, cut to a few classes, with the module left off
/// the longest rows, and filled in by hand where a test needs a shape the real rows do not have.
class HistogramTest {

    private static final String START = """
            23059:
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:         78239       23957512  [B (java.base@25)
               6:          5639        1010928  [I (java.base@25)
               3:         75790        1818960  java.lang.String (java.base@25)
             812:    698   33504  javax.swing.text.AbstractDocument$DefaultDocumentEventUndoableWrapper
            4875:             1             16  tools.jackson.databind.util.RecordUtil$$Lambda/0x00003000010b7a40
            Total        160365       26820920
            """;

    private static final String MIDDLE = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:         85000       28000000  [B (java.base@25)
               6:          5400         600000  [I (java.base@25)
               3:         80000        1920000  java.lang.String (java.base@25)
             812:   2000   96000  javax.swing.text.AbstractDocument$DefaultDocumentEventUndoableWrapper
             900:             3             72  dev.marregui.comando.Undo$Record
            Total        172403       30616072
            """;

    private static final String END = """
            23059:
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:         91408       31721472  [B (java.base@25)
               6:          5180         211456  [I (java.base@25)
               3:         88996        2135904  java.lang.String (java.base@25)
             812:   3390  162720  javax.swing.text.AbstractDocument$DefaultDocumentEventUndoableWrapper
             900:             3             72  dev.marregui.comando.Undo$Record
            Total        188977       34231624
            """;

    @TempDir
    Path dir;

    /// The rows are read with or without a module; the pid line, the header and the total are not rows.
    @Test
    void parsesTheRowsAndNothingElse() throws IOException {
        final Histogram h = Histogram.parse(START, "START");
        assertEquals(5, h.classes().size());
        assertEquals(78239, h.instances("[B"));
        assertEquals(23957512, h.bytes("[B"));
        assertEquals(1, h.instances("tools.jackson.databind.util.RecordUtil$$Lambda/0x00003000010b7a40"));
        assertEquals(0, h.instances("Total"));
        assertEquals(0, h.bytes("absent"));
    }

    /// Two rows of one name, a class loaded by two loaders, count as one class.
    @Test
    void twoRowsOfOneNameAreAddedTogether() throws IOException {
        final Histogram h = Histogram.parse("""
                   1:   10   100  a.B
                   2:    5    50  a.B
                """, "inline");
        assertEquals(15, h.instances("a.B"));
        assertEquals(150, h.bytes("a.B"));
    }

    /// A grower grew in instances and bytes at every step; a class that shrank once, or held still once, is not one.
    @Test
    void growersGrewAtEveryStep() throws IOException {
        final List<Histogram.Grower> growers = Histogram.growers(
                List.of(Histogram.parse(START, "START"), Histogram.parse(MIDDLE, "MIDDLE"), Histogram.parse(END, "END")));
        // [I shrank; Undo$Record held still from the middle to the end; the lambda left.
        assertEquals(List.of("[B", "java.lang.String",
                "javax.swing.text.AbstractDocument$DefaultDocumentEventUndoableWrapper"),
                growers.stream().map(Histogram.Grower::name).toList());
        assertArrayEquals(new long[] {698, 2000, 3390}, growers.get(2).instances());
        assertArrayEquals(new long[] {33504, 96000, 162720}, growers.get(2).bytes());
    }

    /// A class absent from the first histogram counts as 0 there, so a class that appeared and kept growing is a
    /// grower.
    @Test
    void anAbsentClassCountsAsZero() throws IOException {
        final List<Histogram.Grower> growers = Histogram.growers(
                List.of(Histogram.parse(START, "START"), Histogram.parse(MIDDLE, "MIDDLE")));
        assertTrue(growers.stream().anyMatch(g -> g.name().equals("dev.marregui.comando.Undo$Record")));
        assertThrows(IllegalArgumentException.class, () -> Histogram.growers(List.of(Histogram.parse(START, "START"))));
    }

    /// The table names the files in order, ranks growers by bytes and lists each one's counts per file.
    @Test
    void theTableListsEachGrowersCountsPerFile() throws IOException {
        final String table = Histogram.table(List.of("start.txt", "middle.txt", "end.txt"),
                List.of(Histogram.parse(START, "START"), Histogram.parse(MIDDLE, "MIDDLE"), Histogram.parse(END, "END")), 2);
        assertTrue(table.contains("   1  start.txt\n   2  middle.txt\n   3  end.txt\n"), table);
        assertTrue(table.contains("Growers    3 of 6 classes grew in instances and bytes from each histogram to "
                + "the next (top 2 by bytes)"), table);
        assertTrue(table.contains("instances 78,239 -> 85,000 -> 91,408"), table);
        assertTrue(table.contains("instances 75,790 -> 80,000 -> 88,996"), table);
        assertFalse(table.contains("DefaultDocumentEventUndoableWrapper"), table);
        assertTrue(table.indexOf("[B") < table.indexOf("java.lang.String"), table);
        // [I shrank and Undo$Record held still: nothing grew in bytes alone at every step.
        assertTrue(table.contains("Bytes only 0 of 6 classes\n"), table);
    }

    /// Arrays that grew in bytes at every step while their number fell are listed in a table of their own: `int[]`
    /// and `long[]` between the two histograms an hour apart (the real rows), next to `byte[]`, which grew in both.
    @Test
    void bytesThatGrewWhileInstancesDidNotHaveTheirOwnTable() throws IOException {
        final Histogram start = Histogram.parse("""
                   1:         78239       23957512  [B (java.base@25)
                   6:          5639        1010928  [I (java.base@25)
                  17:           889         181752  [J (java.base@25)
                """, "inline");
        final Histogram end = Histogram.parse("""
                   1:         91408       31721472  [B (java.base@25)
                   5:          5180        1810640  [I (java.base@25)
                  18:           686         222992  [J (java.base@25)
                """, "inline");
        assertEquals(List.of("[B"), Histogram.growers(List.of(start, end)).stream().map(Histogram.Grower::name)
                .toList());
        assertEquals(List.of("[I", "[J"), Histogram.bytesOnly(List.of(start, end)).stream()
                .map(Histogram.Grower::name).toList());
        final String table = Histogram.table(List.of("start", "end"), List.of(start, end), 15);
        final int heading = table.indexOf("BYTES GREW AT EVERY STEP (instances did not)");
        assertTrue(heading > table.indexOf("  [B"), table);
        assertTrue(table.indexOf("[I", heading) > heading, table);
        assertTrue(table.contains("Bytes only 2 of 3 classes"), table);
        assertTrue(table.contains("instances 5,639 -> 5,180"), table);
        assertTrue(table.contains("bytes     1.01 MB -> 1.81 MB"), table);
    }

    /// `jfrq-live growth` reads the files; fewer than two is a usage error, a file with no rows a failure.
    @Test
    void theGrowthCommand() throws IOException {
        final Path a = Files.writeString(dir.resolve("a.txt"), START, StandardCharsets.UTF_8);
        final Path b = Files.writeString(dir.resolve("b.txt"), END, StandardCharsets.UTF_8);
        final LiveTest.Run ok = LiveTest.run("growth", a.toString(), b.toString(), "--top", "1");
        assertEquals(0, ok.status(), ok.err());
        assertTrue(ok.out().contains("instances 78,239 -> 91,408"), ok.out());
        assertTrue(ok.out().contains("(top 1 by bytes)"), ok.out());
        assertEquals(2, LiveTest.run("growth", a.toString()).status());
        final Path empty = Files.writeString(dir.resolve("empty.txt"), "nothing\n", StandardCharsets.UTF_8);
        final LiveTest.Run none = LiveTest.run("growth", a.toString(), empty.toString());
        assertEquals(1, none.status());
        assertTrue(none.err().contains("holds no class histogram rows"), none.err());
        final Path huge = Files.writeString(dir.resolve("huge.txt"), "   2:  99999999999999999999  10  Foo\n",
                StandardCharsets.UTF_8);
        final LiveTest.Run bad = LiveTest.run("growth", a.toString(), huge.toString());
        assertEquals(1, bad.status());
        assertTrue(bad.err().contains(huge + " line 1: a count does not fit 64 bits"), bad.err());
        assertFalse(bad.err().contains("the JVM"), bad.err());
        assertTrue(LiveTest.run("growth", "--help").out().contains("growth H1 H2"));
    }
}
