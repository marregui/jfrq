// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.util;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.jfrq.core.coll.Nulls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

    @ParameterizedTest
    @CsvSource({
            "50ms, 50000000",
            "10 ms, 10000000",
            "1.5s, 1500000000",
            "2m, 120000000000",
            "1h, 3600000000000",
            "100us, 100000",
            "100µs, 100000",
            "5ns, 5",
            "42, 42",
            " 20 MS , 20000000",
            "2d, 172800000000000",
            // The compound forms format prints parse back.
            "2m18s, 138000000000",
            "13h11m, 47460000000000",
            "3d04h, 273600000000000",
            "1m 30.5s, 90500000000",
            "infinity, 9223372036854775807",
            "Infinity, 9223372036854775807",
    })
    void parsesJfrAndCommandLineForms(final String text, final long nanos) {
        assertEquals(nanos, Durations.parseNanos(text));
        assertEquals(Duration.ofNanos(nanos), Durations.parse(text));
    }

    @ParameterizedTest
    @CsvSource({"abc", "10 parsecs", "''", "ms", "1..2s", "2m18", "18 2m", "infinity s", "1ms-"})
    void rejectsNonDurations(final String text) {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(text));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(null));
    }

    @Test
    void quietVariantAnswersTheSentinelInsteadOfThrowing() {
        assertEquals(50_000_000L, Durations.parseNanosQuiet("50ms"));
        assertEquals(20_000_000L, Durations.parseNanosQuiet(new StringBuilder(" 20 MS ")));
        assertEquals(1_050_000_000L, Durations.parseNanosQuiet("1.05s"));
        assertEquals(120_000_000_000L, Durations.parseNanosQuiet("2min"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("everyChunk"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("10 parsecs"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("1.s"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet("5 m s"));
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet(null));
        // Mantissas past fifteen digits are parsed by the JDK, still correctly rounded.
        assertEquals(1_234_567_890_123_456L, Durations.parseNanosQuiet("1234567890123456"));
        assertEquals(1_500_000L, Durations.parseNanosQuiet("1.500000000000000ms"));
        final String unit = assertThrows(IllegalArgumentException.class, () -> Durations.parse("10 parsecs")).getMessage();
        assertTrue(unit.startsWith("unknown duration unit"), unit);
    }

    /** A duration a long of nanoseconds cannot hold is refused, not clamped to the largest one. */
    @ParameterizedTest
    @CsvSource({"10000000000000000000", "9999999999h", "106752d", "106751d23h59m59s1s", "9223372036854775807ns"})
    void rejectsDurationsPastTheRangeInsteadOfClamping(final String text) {
        final String message = assertThrows(IllegalArgumentException.class, () -> Durations.parseNanos(text)).getMessage();
        assertTrue(message.startsWith("duration out of range"), message);
        assertTrue(message.contains("infinity"), message);
        assertEquals(Nulls.LONG_NULL, Durations.parseNanosQuiet(text));
    }

    @Test
    void infinityRoundTrips() {
        assertEquals(Durations.INFINITE, Durations.parseNanos("infinity"));
        assertEquals("infinity", Durations.format(Durations.INFINITE));
        assertEquals(Durations.INFINITE, Durations.parseNanos(Durations.format(Durations.INFINITE)));
        assertEquals(106_751L * 86_400_000_000_000L, Durations.parseNanos("106751d"));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0 ns",
            "999, 999 ns",
            "1500, 1.50 µs",
            "312000000, 312 ms",
            "12345678, 12.3 ms",
            "1420000000, 1.42 s",
            "59900000000, 59.9 s",
            "67000000000, 1m07s",
            "-312000000, -312 ms",
            // Above an hour the minutes tier stops being readable: a warning that said 790m55s
            // is a number the reader has to divide before it means anything.
            "3599000000000, 59m59s",
            "3600000000000, 1h00m",
            "47455000000000, 13h10m",
            "86399000000000, 23h59m",
            "86400000000000, 1d00h",
            "273600000000000, 3d04h",
            "-47455000000000, -13h10m",
            // The unit and the precision are chosen after rounding, not before.
            "999999, 1.00 ms",
            "999500, 1.00 ms",
            "999499, 999 µs",
            "999999999, 1.00 s",
            "9995000, 10.0 ms",
            "99950000, 100 ms",
            "59950000000, 1m00s",
            "59999999999, 1m00s",
            "59940000000, 59.9 s",
    })
    void formatsCompactly(final long nanos, final String expected) {
        assertEquals(expected, Durations.format(nanos));
    }

    @Test
    void anUnmeasuredCadenceIsADashRatherThanZero() {
        // A cadence needs two samples; a thread with fewer has none, and "0 ns" would claim a
        // measurement that was never taken. Every other zero is still a duration.
        assertEquals("—", Durations.formatOrDash(0));
        assertEquals("12.6 ms", Durations.formatOrDash(12_600_000));
        assertEquals("0 ns", Durations.format(0));
    }

    @Test
    void formatsDurationsAndOffsets() {
        assertEquals("2.00 s", Durations.format(Duration.ofSeconds(2)));
        assertEquals("+3.412s", Durations.offset(3_412_000_000L));
        assertEquals("-0.500s", Durations.offset(-500_000_000L));
        // The time of day in UTC beside the offset, so a log line beside the recording can be found.
        assertEquals("23:37:15.286Z", Durations.clock(1_790_811_435_286_000_000L));
        assertEquals("+13.682s 23:37:15.286Z", Durations.at(1_790_811_435_286_000_000L,
                1_790_811_435_286_000_000L - 13_682_000_000L));
    }
}
