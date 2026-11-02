// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.procs;

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

/// `procs`' analysis on hand-built starts: the command name, the rows by command and by thread family, rates.
class ProcessCollectorTest {

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
        return new RecordingInfo(Path.of("t.jfr"), new Interval(T0, T0 + 120 * SECOND), 1, Map.of(), Map.of(), Set.of(),
                List.of());
    }

    /// A command is the basename of the first word, quotes stripped, on either separator; no word is `(empty)`.
    @Test
    void aCommandIsTheBasenameOfItsFirstWord() {
        assertEquals("git", ProcessCollector.name("git --no-optional-locks -C /repo status"));
        assertEquals("git", ProcessCollector.name("  /usr/bin/git status"));
        assertEquals("python3", ProcessCollector.name("\"/usr/bin/python3\" kit.py"));
        assertEquals("python3", ProcessCollector.name("'python3'"));
        assertEquals("a.exe", ProcessCollector.name("C:\\bin\\a.exe /c"));
        assertEquals(ProcessReport.EMPTY, ProcessCollector.name(""));
        assertEquals(ProcessReport.EMPTY, ProcessCollector.name("   "));
        assertEquals(ProcessReport.EMPTY, ProcessCollector.name("\"\" x"));
        assertEquals(ProcessReport.EMPTY, ProcessCollector.name("/usr/bin/ x"));
    }

    /// Starts are ranked by command with their families and sites, the earliest line is the example, and the
    /// family table sums each family's commands; rates are per minute of the span.
    @Test
    void startsByCommandAndByFamily() {
        final ProcessCollector c = new ProcessCollector(SiteKey.inPackages(List.of("com.app")));
        final ThreadRef poll = new ThreadRef(1, "folder-poll");
        final ThreadRef job1 = new ThreadRef(2, "kit-jobs-1");
        final ThreadRef job2 = new ThreadRef(3, "kit-jobs-2");
        final Stack status = stack("com.app.Processes.run", "com.app.Git.status", "java.lang.Thread.run");
        final Stack jobs = stack("com.lib.Exec.run", "com.app.Jobs.list");
        c.start(poll, T0 + 5 * SECOND, "git -C /b status", status);
        c.start(poll, T0 + SECOND, "git -C /a status", status);
        c.start(poll, T0 + 9 * SECOND, "/usr/bin/git -C /c status", status);
        c.start(job1, T0, "python3 kit.py jobs", jobs);
        c.start(job2, T0 + 2 * SECOND, "python3 kit.py jobs", jobs);
        c.start(job2, T0 + 3 * SECOND, "git log", jobs);
        c.finish(info());
        final ProcessReport r = c.report();

        assertEquals(6, r.starts());
        assertEquals(3.0, r.perMinute(r.starts()), 1e-9);
        assertEquals(List.of("git", "python3"), r.commands().stream().map(ProcessReport.Command::command).toList());
        final ProcessReport.Command git = r.commands().getFirst();
        assertEquals(4, git.starts());
        assertEquals("git -C /a status", git.example());
        assertEquals(List.of(new ProcessReport.Count("folder-poll", 3), new ProcessReport.Count("kit-jobs-N", 1)),
                git.threads());
        assertEquals(List.of(new ProcessReport.Count("com.app.Processes.run", 3),
                new ProcessReport.Count("com.app.Jobs.list", 1)), git.sites());

        assertEquals(List.of(new ProcessReport.Family("folder-poll", 1, 3, List.of(new ProcessReport.Count("git", 3))),
                new ProcessReport.Family("kit-jobs-N", 2, 3, List.of(new ProcessReport.Count("python3", 2),
                        new ProcessReport.Count("git", 1)))), r.families());
        assertEquals("folder-poll 3, kit-jobs-N 1", ProcessReport.listed(git.threads()));
    }

    /// A list longer than the rows show sums its rest on one entry.
    @Test
    void aLongListSumsItsRest() {
        assertEquals("a 4, b 3, c 2, 2 more 2", ProcessReport.listed(List.of(new ProcessReport.Count("a", 4),
                new ProcessReport.Count("b", 3), new ProcessReport.Count("c", 2), new ProcessReport.Count("d", 1),
                new ProcessReport.Count("e", 1))));
    }

    /// More distinct command lines than the name cache holds: the cache is emptied and every start still counts.
    @Test
    void theNameCacheIsBoundedAndLosesNoStart() {
        final ProcessCollector c = new ProcessCollector(SiteKey.culpritMethod());
        final ThreadRef t = new ThreadRef(1, "fork-storm");
        final Stack s = stack("com.app.Fork.run");
        final int n = ProcessCollector.NAMES_CACHED * 2 + 3;
        for (int i = 0; i < n; i++) {
            c.start(t, T0 + i, "touch /tmp/f" + i, s);
        }
        c.finish(info());
        assertEquals(n, c.report().starts());
        assertEquals(List.of(new ProcessReport.Command("touch", n, "touch /tmp/f0",
                List.of(new ProcessReport.Count("fork-storm", n)),
                List.of(new ProcessReport.Count("com.app.Fork.run", n)))), c.report().commands());
    }

    /// No start is an empty report with a zero rate, and asking before `finish` is an error.
    @Test
    void noStarts() {
        final ProcessCollector c = new ProcessCollector(SiteKey.culpritMethod());
        assertThrows(IllegalStateException.class, c::report);
        c.finish(new RecordingInfo(Path.of("t.jfr"), new Interval(T0, T0), 1, Map.of(), Map.of(), Set.of(), List.of()));
        assertEquals(0, c.report().starts());
        assertEquals(0.0, c.report().perMinute(0));
        assertEquals(List.of(), c.report().families());
    }
}
