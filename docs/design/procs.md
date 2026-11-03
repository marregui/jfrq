# Design: `procs`

Part of the [design](README.md). How `procs` turns process-start events into which
processes a JVM started, how often, from which threads and from which code.

**Events.** `jdk.ProcessStart` only, which the JVM writes once per `ProcessBuilder.start`
and `Runtime.exec` that succeeded, with the new process's `pid`, its `directory` (often not
recorded) and its `command`: the whole command line as one string, the arguments joined by
spaces. Both JDK settings files enable it with a stack, and it has no threshold and no
throttle, so the file holds every start in its span. There is no settings line to print for
it; a recording without starts says so and names the settings that record them.

**Commands.** A command is the basename of the first whitespace-separated word of the
command line, quotes stripped: `/usr/bin/git -C repo status` is `git`, `C:\bin\a.exe` is
`a.exe`, a line with no word is `(empty)`. I key on the program and not on the line,
because the arguments carry paths, ids and temporary names: on `run5/d-2059.jfr` the 421
`git` starts are 421 rows by line and one by program. A program path with a space in it is
cut at the space, since the event joined the arguments without quoting them; the example
line beside each row shows the whole line.

**Threads and sites.** Each start is counted against the thread that made it, folded into
its family (`RecordingSummary.family`, the fold `info` and `hot` use: `kit-jobs` threads
started one at a time are one row), and against its stack, named by the `SiteKey` `alloc`
uses (the innermost frame outside the JDK, or under `--app`'s prefixes). A program that
starts every process through one helper has that helper as the site of every row:
on `d-2059.jfr`, `Processes.run` for `git`, `python3` and `claude`; the two commands that
do not go through it have their own (`CodexUsageReader.read` for `codex`, `Notifier.run`
for `osascript`). The thread family
then says which caller it was (`folder-poll` 418 of the `git` starts).

**Rates.** A row's rate is its starts per minute of the recording's span (the span from the
chunk headers, the same for every command), not of the stretch between the first and the
last start: a command started once in a 30-minute window is 0.03 a minute, not one a minute.

**Cost.** A fork storm writes one event per fork, so the per-event path is probes: the
command line into a cache of names already worked out (cleared at 4,096 distinct lines, as
the interner clears its caches), then the name's counters per thread and per interned
stack, and the earliest time and line. Families and sites are resolved once per distinct
thread and stack in `finish()`. Measured with `--timing`, median of three runs, against
`info` on the same file:

| File | Size | `jdk.ProcessStart` | `procs` read + analyse | `info` read + analyse |
|---|---|---|---|---|
| `run2/d-1357.jfr` | 20 MB | 449 | 174 + 2.7 ms | 258 + 4.3 ms |
| `jfrq-dogfood-3/exit-final.jfr` | 41 MB | 0 | 202 + 0.4 ms | 286 + 11.1 ms |

**Checked against.** `jfr print --events jdk.ProcessStart run5/d-2059.jfr`: 464 events,
first-word basenames `git` 421, `python3` 30, `claude` 7, `codex` 5, `osascript` 1, the
same as `procs`; the families `folder-poll`, `folder-read`, `kit-jobs`, `plan-usage`,
`topic-reader` and `notify` are the event threads that print shows.
