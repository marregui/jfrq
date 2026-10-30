// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.jfr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk scan is what stands between a damaged file and a silently empty answer, so
 * every damage shape gets a real file: cut mid-chunk, cut after a complete chunk, header
 * only, wrong magic, wrong version, empty.
 */
class ChunksTest {

    @TempDir
    Path dir;

    private Path recording() throws Exception {
        return JfrFixtures.record(dir, "chunks", r -> r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO),
                () -> JfrFixtures.sleep(30));
    }

    @Test
    void aFinishedRecordingIsOneOrMoreCompleteChunks() throws Exception {
        final Path file = recording();
        final Chunks chunks = Chunks.scan(file);

        assertFalse(chunks.isTruncated());
        assertFalse(chunks.isInProgress());
        assertTrue(chunks.count() >= 1);
        assertEquals(chunks.count(), chunks.complete());
        long sum = 0;
        for (final Chunks.Header h : chunks.headers()) {
            assertTrue(h.size() >= Chunks.HEADER_SIZE);
            assertTrue(h.durationNanos() > 0);
            assertEquals(sum, h.offset());
            sum += h.size();
        }
        assertEquals(Files.size(file), sum);
        assertTrue(chunks.endNanos() - chunks.startNanos() >= 25_000_000L, "span " + (chunks.endNanos() - chunks.startNanos()));
        // The reader takes its span from the headers and reports the count.
        final RecordingInfo info = JfrReader.read(file);
        assertEquals(chunks.startNanos(), info.startNanos());
        assertEquals(chunks.endNanos(), info.endNanos());
        assertEquals(chunks.count(), info.chunks());
        assertTrue(info.warnings().isEmpty(), info.warnings().toString());
    }

    @Test
    void aFileCutInsideItsOnlyChunkIsRefusedInsteadOfReadAsEmpty() throws Exception {
        final Path file = recording();
        final byte[] bytes = Files.readAllBytes(file);
        final Path cut = dir.resolve("cut.jfr");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length / 2));

        final Chunks chunks = Chunks.scan(cut);
        assertTrue(chunks.isTruncated());
        assertEquals(0, chunks.count());
        final IOException e = assertThrows(IOException.class, () -> JfrReader.read(cut));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());

        // Header only: the same.
        final Path headerOnly = dir.resolve("header.jfr");
        Files.write(headerOnly, Arrays.copyOf(bytes, Chunks.HEADER_SIZE));
        assertTrue(Chunks.scan(headerOnly).isTruncated());
        assertThrows(IOException.class, () -> JfrReader.read(headerOnly));
    }

    @Test
    void aFileCutAfterACompleteChunkReadsThatChunkAndWarns() throws Exception {
        // Two recordings: stopping the inner one rotates the chunk, so the outer file has
        // at least two, and the second can be cut off.
        final Path file = Files.createTempFile(dir, "two", ".jfr");
        try (final Recording outer = new Recording()) {
            outer.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            outer.setDestination(file);
            outer.start();
            JfrFixtures.onThread("first-chunk-sleeper", () -> JfrFixtures.sleep(30));
            try (final Recording inner = new Recording()) {
                inner.start();
                JfrFixtures.sleep(10);
                inner.stop();
            }
            JfrFixtures.onThread("second-chunk-sleeper", () -> JfrFixtures.sleep(30));
            outer.stop();
        }
        final Chunks whole = Chunks.scan(file);
        assertTrue(whole.count() >= 2, "chunks " + whole.count());
        final Chunks.Header first = whole.headers().getFirst();

        final byte[] bytes = Files.readAllBytes(file);
        final Path cut = dir.resolve("cut2.jfr");
        Files.write(cut, Arrays.copyOf(bytes, (int) (first.size() + Chunks.HEADER_SIZE + 10)));
        final Chunks chunks = Chunks.scan(cut);
        assertTrue(chunks.isTruncated());
        assertEquals(1, chunks.count());

        final RecordingInfo info = JfrReader.read(cut);
        assertEquals(1, info.chunks());
        assertEquals(1, info.warnings().size(), info.warnings().toString());
        assertTrue(info.warnings().getFirst().startsWith("the file is truncated"), info.warnings().getFirst());
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("first-chunk-sleeper")), info.threads().toString());
        assertFalse(info.threads().stream().anyMatch(t -> t.name().equals("second-chunk-sleeper")), info.threads().toString());
        assertEquals(first.startNanos(), info.startNanos());
        assertEquals(first.endNanos(), info.endNanos());
    }

    /**
     * The JDK parser does not fail on an unfinished chunk: it polls the header until the
     * file-state byte reads zero, forever when the file is a copy. The JVM rewrites size and
     * duration at every flush, so only that byte says whether the chunk is closed; the
     * fixture is a copy of a real repository chunk taken while the JVM is writing it.
     */
    @Test
    void aCopyOfALiveChunkIsRefusedBeforeTheParserCanHangOnIt() throws Exception {
        final Path live = dir.resolve("live.jfr");
        try (final Recording r = new Recording()) {
            r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            r.setToDisk(true);
            r.start();
            // The first flush (about a second in) writes size and duration into the header
            // while the chunk stays open; a copy taken then is the interesting one.
            JfrFixtures.sleep(1_500);
            final Path repository = Path.of(System.getProperty("jdk.jfr.repository"));
            Path chunk;
            try (final var files = Files.list(repository)) {
                chunk = files.filter(f -> f.toString().endsWith(".jfr"))
                        .max(Comparator.comparingLong(f -> f.toFile().lastModified())).orElseThrow();
            }
            Files.copy(chunk, live, StandardCopyOption.REPLACE_EXISTING);
            r.stop();
        }
        final Chunks chunks = Chunks.scan(live);
        assertEquals(1, chunks.count());
        final Chunks.Header h = chunks.headers().getFirst();
        assertTrue(h.fileState() != 0, "file state " + h.fileState());
        assertTrue(h.isInProgress());
        assertTrue(chunks.isInProgress());
        assertEquals(0, chunks.complete());
        final IOException e = assertThrows(IOException.class, () -> JfrReader.read(live));
        assertTrue(e.getMessage().contains("still being written"), e.getMessage());
        // With no complete chunk, truncating would leave nothing to read: only the dump is advice.
        assertFalse(e.getMessage().contains("truncate"), e.getMessage());
        assertTrue(e.getMessage().contains("JFR.dump"), e.getMessage());

        // Behind a complete chunk, the same open chunk can be cut off to read what is finished.
        final byte[] finished = Files.readAllBytes(recording());
        final Path behind = dir.resolve("behind.jfr");
        Files.write(behind, finished);
        Files.write(behind, Files.readAllBytes(live), StandardOpenOption.APPEND);
        final String cut = assertThrows(IOException.class, () -> JfrReader.read(behind)).getMessage();
        assertTrue(cut.contains("truncate the file to " + finished.length + " bytes"), cut);

        // A header with no size at all (the JVM's very first write) is refused the same way.
        final byte[] bytes = Files.readAllBytes(recording());
        ByteBuffer.wrap(bytes).putLong(8, 0).putLong(40, 0);
        final Path unsized = dir.resolve("unsized.jfr");
        Files.write(unsized, bytes);
        assertTrue(Chunks.scan(unsized).isInProgress());
        assertThrows(IOException.class, () -> JfrReader.read(unsized));
    }

    @Test
    void aFinishedFileHasEveryFileStateByteAtZero() throws Exception {
        for (final Chunks.Header h : Chunks.scan(recording()).headers()) {
            assertEquals(0, h.fileState());
        }
    }

    @Test
    void aFileCutInsideTheNextChunksHeaderIsTruncated() throws Exception {
        final byte[] bytes = Files.readAllBytes(recording());
        final Path cut = dir.resolve("cut-header.jfr");
        Files.write(cut, Arrays.copyOf(bytes, bytes.length + 30));
        final Chunks chunks = Chunks.scan(cut);
        assertEquals(1, chunks.count());
        assertTrue(chunks.isTruncated());
        assertEquals(1, JfrReader.read(cut).warnings().size());
    }

    /**
     * A size field no file can satisfy (damage, not a real length) must read as a damaged
     * tail, not overflow {@code offset + size} into a negative file position.
     */
    @Test
    void aHugeChunkSizeIsATruncatedTailNotAnOverflow() throws Exception {
        final byte[] bytes = Files.readAllBytes(recording());
        final byte[] header = Arrays.copyOf(bytes, Chunks.HEADER_SIZE);
        ByteBuffer.wrap(header).putLong(8, Long.MAX_VALUE);
        final Path damaged = dir.resolve("huge.jfr");
        Files.write(damaged, bytes);
        Files.write(damaged, header, StandardOpenOption.APPEND);

        final Chunks chunks = Chunks.scan(damaged);
        assertEquals(1, chunks.count());
        assertTrue(chunks.isTruncated());
        final RecordingInfo info = JfrReader.read(damaged);
        assertEquals(1, info.warnings().size(), info.warnings().toString());
        assertTrue(info.warnings().getFirst().startsWith("the file is truncated"), info.warnings().getFirst());
    }

    /**
     * The span is the chunk headers' and nothing else, so it cannot depend on which event
     * types a command read. The header here claims a shorter chunk than its events cover
     * (the way a safepoint ending the last rotation sticks out of a real one): a read of
     * everything and a read of one absent type must still agree, on the header's span.
     */
    @Test
    void theSpanIsTheSameWhateverTheEventTypesRead() throws Exception {
        final byte[] bytes = Files.readAllBytes(recording());
        ByteBuffer.wrap(bytes).putLong(40, 1_000L);
        final Path shortHeader = dir.resolve("short.jfr");
        Files.write(shortHeader, bytes);
        final Chunks chunks = Chunks.scan(shortHeader);

        final RecordingInfo everything = JfrReader.read(shortHeader);
        final RecordingInfo filtered = JfrReader.read(shortHeader, new JfrReader.Sink() {
            @Override
            public Set<String> eventTypes() {
                return Set.of("jdk.JavaMonitorEnter");
            }

            @Override
            public void accept(@Transient final RecordedEvent event) {
            }
        });
        assertTrue(everything.has("jdk.ThreadSleep"));
        assertEquals(everything.span(), filtered.span());
        assertEquals(chunks.startNanos(), everything.startNanos());
        assertEquals(chunks.endNanos(), everything.endNanos());
    }

    /**
     * JFR files concatenate, and the parser reads the result without a word. Chunks of one
     * recording abut exactly; two files joined leave a hole, or run backwards, and the span
     * then covers time no run recorded. Both are said.
     */
    @Test
    void joinedRecordingsAreWarnedAbout() throws Exception {
        final byte[] first = Files.readAllBytes(recording());
        JfrFixtures.sleep(20);
        final byte[] second = Files.readAllBytes(recording());

        final Path forward = dir.resolve("forward.jfr");
        Files.write(forward, first);
        Files.write(forward, second, StandardOpenOption.APPEND);
        final RecordingInfo joined = JfrReader.read(forward);
        assertEquals(2, joined.chunks());
        assertEquals(1, joined.warnings().size(), joined.warnings().toString());
        assertTrue(joined.warnings().getFirst().startsWith("chunk 2 of 2 starts "), joined.warnings().getFirst());
        assertTrue(joined.warnings().getFirst().contains("after the previous one ends"), joined.warnings().getFirst());

        final Path backward = dir.resolve("backward.jfr");
        Files.write(backward, second);
        Files.write(backward, first, StandardOpenOption.APPEND);
        final RecordingInfo reversed = JfrReader.read(backward);
        assertEquals(1, reversed.warnings().size(), reversed.warnings().toString());
        assertTrue(reversed.warnings().getFirst().contains("before the previous one ends"), reversed.warnings().getFirst());
        // The span is the envelope either way.
        assertEquals(joined.span(), reversed.span());
    }

    /**
     * A recording cut short with another appended: the cut chunk's declared size fits in the
     * file, so only its content shows the damage, and the JDK parser, reading from the start,
     * reads nothing at all. The cut chunk has been flushed, so it holds copies of its own
     * header (with the file-state byte of an open chunk) that the scan for the next chunk
     * must not take for one.
     */
    @Test
    void aCutChunkWithARecordingAppendedReadsTheRecordingAndNamesTheCut() throws Exception {
        final byte[] cut = cutAfterAFlush();
        final byte[] whole = Files.readAllBytes(sleeper("appended-sleeper"));
        final Path joined = dir.resolve("joined.jfr");
        Files.write(joined, cut);
        Files.write(joined, whole, StandardOpenOption.APPEND);

        final Chunks chunks = Chunks.scan(joined);
        assertEquals(1, chunks.count());
        assertEquals(cut.length, chunks.headers().getFirst().offset());
        assertEquals(1, chunks.damaged().size());
        final Chunks.Damage damage = chunks.damaged().getFirst();
        assertEquals(0, damage.offset());
        assertEquals(cut.length, damage.length());
        assertTrue(damage.hasHeader());
        assertFalse(chunks.readableInPlace());

        final RecordingInfo info = JfrReader.read(joined);
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("appended-sleeper")), info.threads().toString());
        assertFalse(info.threads().stream().anyMatch(t -> t.name().equals("cut-sleeper")), info.threads().toString());
        assertEquals(chunks.headers().getFirst().startNanos(), info.startNanos());
        assertEquals(1, info.warnings().size(), info.warnings().toString());
        final String warning = info.warnings().getFirst();
        assertTrue(warning.startsWith("the chunk at byte 0, recorded from "
                + Instant.ofEpochSecond(0, damage.startNanos()) + " for "), warning);
        assertTrue(warning.contains("is cut off or damaged (bytes 0 to " + cut.length + ", then a complete chunk)"),
                warning);
        assertTrue(warning.endsWith("1 complete chunk(s) were read"), warning);
        // The copy handed to the parser is gone.
        try (final var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertFalse(files.anyMatch(f -> f.getFileName().toString().startsWith("jfrq-")
                    && f.toFile().lastModified() >= damage.startNanos() / 1_000_000), "temporary copy left behind");
        }
    }

    /** The same cut between two whole recordings: both are read, and the join is judged against the cut one. */
    @Test
    void aCutChunkBetweenTwoRecordingsLeavesBothReadable() throws Exception {
        final byte[] before = Files.readAllBytes(sleeper("before-sleeper"));
        final byte[] cut = cutAfterAFlush();
        final byte[] after = Files.readAllBytes(sleeper("after-sleeper"));
        final Path joined = dir.resolve("between.jfr");
        Files.write(joined, before);
        Files.write(joined, cut, StandardOpenOption.APPEND);
        Files.write(joined, after, StandardOpenOption.APPEND);

        final RecordingInfo info = JfrReader.read(joined);
        assertEquals(2, info.chunks());
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("before-sleeper")), info.threads().toString());
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("after-sleeper")), info.threads().toString());
        assertTrue(info.warnings().getFirst().startsWith("the chunk at byte " + before.length + ", "),
                info.warnings().toString());
        assertTrue(info.warnings().getFirst().endsWith("2 complete chunk(s) were read"), info.warnings().toString());
        // The span covers the cut chunk, whose events are missing: said on its own, so the rates
        // over the span are not read as the whole story.
        assertEquals(3, info.warnings().size(), info.warnings().toString());
        assertTrue(info.warnings().get(1).startsWith("chunk 2 of 2 follows ")
                && info.warnings().get(1).contains("of damaged chunks whose events are missing"), info.warnings().toString());
        // The cut recording ran between the two, so the second whole one starts after it ends.
        assertTrue(info.warnings().get(2).startsWith("chunk 2 of 2 starts ")
                && info.warnings().get(2).contains("after the previous one ends"), info.warnings().toString());
    }

    @Test
    void bytesThatAreNoChunkAreSkippedAndCounted() throws Exception {
        final byte[] first = Files.readAllBytes(sleeper("first-sleeper"));
        final byte[] second = Files.readAllBytes(sleeper("second-sleeper"));
        final Path joined = dir.resolve("junk-between.jfr");
        Files.write(joined, first);
        Files.write(joined, "x".repeat(1000).getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.APPEND);
        Files.write(joined, second, StandardOpenOption.APPEND);

        final RecordingInfo info = JfrReader.read(joined);
        assertEquals(2, info.chunks());
        assertTrue(info.threads().stream().anyMatch(t -> t.name().equals("second-sleeper")), info.threads().toString());
        // No header, no time: the pair across it is not judged.
        assertEquals(List.of("bytes " + first.length + " to " + (first.length + 1000) + " are not a Flight Recorder "
                + "chunk and were skipped; 2 complete chunk(s) were read"), info.warnings());
    }

    private Path sleeper(final String thread) throws Exception {
        return JfrFixtures.record(dir, thread, r -> r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO),
                () -> JfrFixtures.onThread(thread, () -> JfrFixtures.sleep(30)));
    }

    /**
     * A recording that ran past its first flush (about a second), with its last 100 bytes cut
     * off: the final constant pool is gone, the flushed header copies are not.
     */
    private byte[] cutAfterAFlush() throws Exception {
        final Path file = JfrFixtures.record(dir, "cut", r -> r.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO),
                () -> JfrFixtures.onThread("cut-sleeper", () -> JfrFixtures.sleep(1_500)));
        final byte[] bytes = Files.readAllBytes(file);
        assertEquals(1, Chunks.scan(file).count());
        final byte[] cut = Arrays.copyOf(bytes, bytes.length - 100);
        boolean copy = false;
        for (int i = Chunks.HEADER_SIZE; i + 4 <= cut.length && !copy; i++) {
            copy = cut[i] == 'F' && cut[i + 1] == 'L' && cut[i + 2] == 'R' && cut[i + 3] == 0;
        }
        assertTrue(copy, "no flushed header copy in the cut chunk");
        return cut;
    }

    @Test
    void junkAndEmptyFilesAreRefusedWithAPlainMessage() throws Exception {
        final Path junk = dir.resolve("junk.jfr");
        Files.writeString(junk, "x".repeat(200));
        assertTrue(assertThrows(IOException.class, () -> Chunks.scan(junk)).getMessage().contains("not a Flight Recorder"));

        final Path empty = dir.resolve("empty.jfr");
        Files.write(empty, new byte[0]);
        assertTrue(assertThrows(IOException.class, () -> Chunks.scan(empty)).getMessage().contains("not a Flight Recorder"));

        final byte[] bytes = Files.readAllBytes(recording());
        ByteBuffer.wrap(bytes).putShort(4, (short) 3);
        final Path future = dir.resolve("v3.jfr");
        Files.write(future, bytes);
        assertTrue(assertThrows(IOException.class, () -> Chunks.scan(future)).getMessage().contains("version 3"));
    }
}
