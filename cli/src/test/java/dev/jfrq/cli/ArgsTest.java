// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.cli;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsTest {

    static final Set<String> VALUED = Set.of("top", "gap", "thread");
    static final Set<String> FLAGS = Set.of("sites", "help");

    static Args parse(final String... argv) {
        return Args.parse(argv, VALUED, FLAGS);
    }

    @Test
    void positionalsOptionsAndFlags() {
        final Args a = parse("rec.jfr", "--top", "5", "--gap=20ms", "--sites", "second");
        assertEquals(List.of("rec.jfr", "second"), a.positional());
        assertEquals("rec.jfr", a.first("file"));
        assertEquals(5, a.top());
        assertEquals(20_000_000L, a.durationOption("gap", "50ms", false));
        assertTrue(a.flag("sites"));
        assertFalse(a.flag("nope"));
        assertTrue(a.option("thread").isEmpty());
    }

    @Test
    void defaultsApplyWhenAbsent() {
        final Args a = parse();
        assertEquals(15, a.top());
        assertEquals(50_000_000L, a.durationOption("gap", "50ms", false));
        assertThrows(Args.UsageException.class, () -> a.first("recording file"));
    }

    @Test
    void usageErrors() {
        assertThrows(Args.UsageException.class, () -> parse("--bogus"));
        assertThrows(Args.UsageException.class, () -> parse("--top"));
        assertThrows(Args.UsageException.class, () -> parse("--sites=yes"));
        assertThrows(Args.UsageException.class, () -> parse("--top", "abc").top());
        assertThrows(Args.UsageException.class, () -> parse("--top", "0").top());
        assertThrows(Args.UsageException.class, () -> parse("--gap", "soon").durationOption("gap", "1s", false));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "0ms").durationOption("gap", "1s", false));
        assertEquals(0, parse("--gap", "0ms").durationOption("gap", "1s", true));
        assertEquals(0, parse("--gap", "0").durationOption("gap", "1s", true));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "50").durationOption("gap", "1s", false));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "-1ms").durationOption("gap", "1s", true));
        final String message = assertThrows(Args.UsageException.class, () -> parse("--bogus")).getMessage();
        assertEquals("unknown option --bogus", message);
    }

    @Test
    void slipsThatUsedToPassAreUsageErrors() {
        assertEquals("--gap has spaces around it: '50ms '",
                assertThrows(Args.UsageException.class, () -> parse("--gap", "50ms ").durationOption("gap", "1s", false))
                        .getMessage());
        assertThrows(Args.UsageException.class, () -> parse("--gap", " 50ms").durationOption("gap", "1s", false));
        assertThrows(Args.UsageException.class, () -> parse("--gap", "50 ").durationOption("gap", "1s", false));
        assertEquals("--thread needs a value", assertThrows(Args.UsageException.class, () -> parse("--thread=")).getMessage());
        assertThrows(Args.UsageException.class, () -> parse("--thread", ""));
        assertEquals("--top given twice", assertThrows(Args.UsageException.class, () -> parse("--top", "1", "--top=2"))
                .getMessage());
        assertThrows(Args.UsageException.class, () -> parse("--sites", "--sites"));
        assertEquals("unknown option -x", assertThrows(Args.UsageException.class, () -> parse("-x", "rec.jfr")).getMessage());
        // -h is --help where there is one; a lone '-' stays a positional.
        assertTrue(parse("rec.jfr", "-h").flag("help"));
        assertThrows(Args.UsageException.class, () -> Args.parse(new String[] {"-h"}, VALUED, Set.of()));
        assertEquals(List.of("-"), parse("-").positional());
        // A value that looks like an option is still the value of the option before it.
        assertEquals("-1", parse("--top", "-1").option("top").orElseThrow());
    }
}
