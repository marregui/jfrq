# Usage

Every command, option and output rule of `jfrq`. [RECORDING.md](RECORDING.md) covers how
to make a recording; [LIVE.md](LIVE.md) covers `jfrq-live`; [JSON.md](JSON.md) lists the
`--json` fields; [design/](design/README.md) explains how each answer is reached.

## 1. Requirements

- JDK 25 to build and run (`jdk.jfr` is part of the JDK; the tool has no other dependency).
- Recordings from any JDK 17+ JVM. Allocation analysis prefers `jdk.ObjectAllocationSample`
  (JDK 16+) and falls back to the TLAB events on older files.

## 2. Build and install

```
./gradlew build          # compiles, runs 440+ tests, checks coverage, Javadoc (doclint) and imports
./gradlew installDist    # live/build/install/jfrq/bin/jfrq and jfrq-live,
                         # netty-demo/build/install/netty-demo/bin/netty-demo
```

Put `live/build/install/jfrq/bin` on your `PATH`, or call the scripts by path. A release
zip, `jfrq-<version>.zip` from the
[releases page](https://github.com/marregui/jfrq/releases), unzips to the same layout:
`bin/` with both launchers, `lib/` with the jars.

The launcher runs the JVM in `JAVA_HOME` when that is set and a `java` from the `PATH`
only when it is not; either way it must be JDK 25, so an older `JAVA_HOME` is not rescued
by a newer `java` on the `PATH`.

The first run writes an AppCDS archive to `lib/jfrq.jsa` next to the jars, which makes
every later run start in about a tenth of a second; if the directory is not writable
nothing is written and start-up is merely ordinary.

JVM options go in `JFRQ_OPTS` (`jfrq`) or `JFRQ_LIVE_OPTS` (`jfrq-live`):
`JFRQ_OPTS=-Xmx4g` for a very large recording, or
`JFRQ_OPTS="-XX:StartFlightRecording=filename=self.jfr"` to record jfrq itself.

## 3. Synopsis

```
jfrq info   recording.jfr [--thread GLOB [--top N]] [--html out.html] [--json]
jfrq health recording.jfr [more.jfr ...] [--top N] [--html out.html] [--json]
jfrq hot    recording.jfr [--app PREFIX] [--bucket 5m] [--sites N] [--top N] [--html out.html] [--json]
jfrq procs  recording.jfr [--app PREFIX] [--top N] [--html out.html] [--json]
jfrq waits  recording.jfr [--thread GLOB] [--monitor GLOB] [--app PREFIX] [--top N] [--html out.html] [--json]
jfrq alloc  recording.jfr [--baseline before.jfr] [--top N] [--sites] [--app PREFIX] [--html out.html] [--json]
jfrq locks  recording.jfr [--min 10ms] [--thread GLOB] [--lock GLOB] [--idle REGEX,...] [--by-site] [--top N] [--html out.html] [--json]
jfrq stalls recording.jfr --thread GLOB [--gap 50ms] [--idle REGEX,...] [--app PREFIX] [--top N] [--html out.html] [--json]
```

Common options: `--top N` (rows per table, default 15; for `info`, the creators `--thread`
lists), `--html FILE`, `--json`, `--timing`, `--version`, `--help`.

## 4. Output

Every command prints plain text for a terminal or a ticket, and writes a self-contained
HTML report with `--html`. A 40 MB recording is answered in about a quarter of a second;
`--timing` shows where the time went ([design, section 5](design/README.md#5-performance)).

`--json` prints the answer as one JSON document instead of text, for a program or an
agent to read: stable field names with the unit in the name, instants in UTC, `null` where
the recording cannot say, and a `schema` number that changes only when a field does.
[JSON.md](JSON.md) lists every field.

Every time `jfrq` and `jfrq-live` print is UTC and says so: a wait, a convoy, a stall or a
pause is printed as its offset from the recording's start and its time of day,
`+13.682s 11:37:15.286Z`, so the line in a log written beside the recording can be found
without converting by hand.

Every number states its basis. A number that rests on sampling says so and says how far
the sampling can be trusted; the allocation estimate is printed next to the JVM's own
counters; a truncated file is read as far as it goes and every report says where it
stops; a file still being written is refused with the command that produces a readable
one, instead of the parser hanging on it.

## 5. Arguments

**Thread globs.** `GLOB` is a comma-separated list of shell globs on thread names (`*`,
`?`, `[0-3]`, `[!0-9]`; a backslash makes the next character literal): `'event-loop-*'`,
`'nioEventLoopGroup-*,worker-?'`.

**Durations** take a unit (`50ms`, `1.5s`, `2m`).

**Options belong to their command**, so a `stalls` option on `locks` is an error rather
than silently ignored, and so is an option given twice.

**Waits at the edges.** A wait or a block that began before the recording, or outlived it,
is counted only for the part inside it, and that is the part `--min` and `--gap` are
measured against.

## 6. `info`

`jfrq info` says what is in the file: its span, its threads, event counts, and the
thresholds and periods that were active when it was made. It lists the threads in a `THREADS` table folded into families, one row per
family with its count, how many some event names, and an example (`load-client-N*`, `8`,
`8`, `load-client-1`).

**Census.** When the recording holds the JVM's thread census
(`jdk.ThreadAllocationStatistics`) and `jdk.ThreadStart`/`jdk.ThreadEnd`, as both JDK
profiles do, each family also says how many threads were alive when the recording began,
started, ended, and were alive when it ended: a leak is an `At end` that grows window
after window, which the count of threads seen in a window cannot show.

**CPU.** When the recording has `jdk.ThreadCPULoad` (both JDK profiles, every 10 s) each
family has its `CPU`, its share of the JVM's CPUs (the machine's, unless
`-XX:ActiveProcessorCount`, a container limit or a CPU affinity mask sets fewer) across
the window. Each reading is weighed by the stretch it covers, since the thread's last
evaluation or its start; a reading at the first evaluation of a thread alive before the
recording covers a stretch the file does not hold, and the readings of a thread native
code attached hold CPU the native thread used before the attach until one comes in below
one core, so both are left out, and the line under the table says how many were. A
recording shorter than two periods shows little for the threads that were already
running.

**Attached threads.** `Attached` counts the threads native code attached to the JVM (JNI
`AttachCurrentThread`, an upcall): their starts carry no stack and no parent thread, and a
library that attaches for every callback starts one each time, which by name looks like
any `Thread-N`.

**`--thread GLOB`** adds how the matching threads were started: how many, the most in
100 ms and in a second and when, and the code that started them (the innermost frame
outside the JDK in the starting thread's stack, with the threads that ran it, in name order).

## 7. `hot`

`hot` answers where the CPU went, from `jdk.ExecutionSample`: a sample is a thread found
running Java code, so a family's share of the samples is its share of the Java threads' CPU
as the sampler saw it. Threads in native code (`jdk.NativeMethodSample`) are not counted.

- **By thread family**: the threads folded by the numbers a pool varies per worker
  (`AWT-EventQueue-0` and `-1` are `AWT-EventQueue-N`), each with its samples, share and
  sites. A site is the innermost frame outside the JDK, or with `--app` the innermost frame
  under one of its prefixes (the same rule as `alloc --app`). Under each site, the outermost
  frame outside the JDK below it, else the frame the site called: a container whose `paint` only paints its
  children is split by the library or component code it dispatched to, not by the JDK's
  `JComponent.paint` that every one of its samples passes through. `(the site itself)` is
  the samples whose site is their leaf.
- **Leaf methods**: the innermost frame of every sample, across families.
- **Timeline**: samples per `--bucket` (default `5m`), buckets aligned to the clock in UTC,
  the total and the five busiest families. A bucket that would make more than 1,000 over
  the samples' span is widened to the smallest multiple of it that fits, and the heading says
  the width used.

`--sites N` sites per family (default 4) and three frames below each are listed; the rest
are summed on one line, which names `--sites`. `--top` bounds the families and the leaf
methods (in JSON, every list, the sites included).

## 8. `procs`

`procs` answers which processes the JVM started, from `jdk.ProcessStart`, which the
`default` and `profile` settings record with a stack and without a threshold: every start
is in the file.

- **By command**: a command is the basename of the first whitespace-separated word of the
  command line, quotes stripped, so `/usr/bin/git -C repo status` is `git`. The arguments
  are not part of the key: they carry paths and ids that would make each start its own row.
  Each row has its starts, the rate per minute over the recording's span, the thread
  families that started it, the sites that started it (the innermost frame outside the
  JDK, or under `--app`'s prefixes, as `alloc --app` chooses it) and one example command
  line: the earliest, cut to 100 characters (whole in JSON).
- **By thread family**: the starts by the family of the starting thread (the fold `info`
  and `hot` use), with its threads and the commands it started.

Three families and three sites are listed per command and three commands per family; the
rest are summed on one entry. `--top` bounds the rows of both tables (in JSON, every list).

## 9. `waits`

`waits` answers what threads waited on in `Object.wait` and `LockSupport.park`, from the
waiting thread's side: `jdk.JavaMonitorWait` by the monitor's class and `jdk.ThreadPark` by
the blocker's class, each row naming its event (`wait` or `park`) with its total time, count
and longest wait. A park with no blocker (`LockSupport.parkNanos`) is `(no blocker)`. The
thread that notified a wait is not used.

- `--thread GLOB`: only waits by these threads (default: every thread).
- `--monitor GLOB`: only waits on these classes, by JVM name or as printed:
  `'*QueueFlusher'`, `'*ReentrantLock*'`, `'byte[]'`, `'(no blocker)'`.
- `--app PREFIX`: the site rule, as `hot --app`.

Each class is split by site and, under each site, by the frame below it, the rule `hot`
uses: the outermost frame outside the JDK under the site, else the frame the site called.
The `Thresholds` line prints the events' thresholds: a wait shorter than its threshold is
not in the file (`20 ms` in `default`, `10 ms` in `profile`), so the totals are those of
the waits above it. A wait that crosses an end of the recording counts only the part inside
it. `--top` bounds the classes and the sites per class; three frames are listed below each
site (in JSON, `--top` of each).

To split by a library's frame, name its package in `--app`, a JDK package included. On a
Swing application on macOS Metal, the event thread's waits on the render queue's flusher:

```
jfrq waits d-1257.jfr --thread 'AWT-EventQueue-*' --monitor '*QueueFlusher' --app javax.swing.text.html
```

measured 25.3 s across 1,137 waits, 19.0 s of them in 829 waits under
`javax.swing.text.html.CSSBorder$SolidPainter.paint`, with `Graphics.fillPolygon` below it.
Without `--app` the same 1,030 waits sit under the look-and-feel's
`FlatEditorPaneUI.paintSafely`, the innermost frame outside the JDK.

## 10. `alloc`

`alloc --sites` ranks one row per allocating method — the innermost frame outside the JDK
— so every path that reaches it is summed instead of ranked separately; the row says how
many stacks it stands for and how many samples are behind all of them.

When that method is in a library you cannot change, `--app com.example` moves the
attribution to your own innermost frame; the report lists the packages it saw, so the
value to pass is in front of you. `--app` takes a comma-separated list of class or package
prefixes, each matching at a `.` or `$` boundary: `io.netty` matches `io.netty.buffer` and
`io.nett` matches neither, and `com.example.Handler` matches its nested classes and
lambdas (`com.example.Handler$Inner`) but not `com.example.HandlerFactory`.

`--baseline before.jfr` compares two recordings: what changed between the recording
before the fix and the one after.

## 11. `locks` and `stalls`

`locks --by-site` does for lock instances what `alloc --sites` does for allocations:
fifteen queues of the same kind become one row of fifteen instances, ranked across all of
them.

**Idle threads.** A thread parked on its own empty queue is not contention and is not a
stall: `locks` lists those apart and `stalls` leaves them out. They are recognised by the
frame of a pool waiting for work (`locks --idle` replaces the list; in `stalls`, a sleep,
wait or park under a frame `--idle` names is idle too) and, for a worker loop no list
knows about, by shape — one thread, no holder, most of the recording parked there.

**Moved locks.** A lock the collector moved has several addresses, so its pieces can each
fall short of that; `locks` lists them under `MOVED BY THE COLLECTOR`, still counted, and
`stalls` names them in a warning, because only timing says the pieces are one object.

**Timer loops.** `stalls` also leaves out a timer loop: a thread whose waits from one
place ran out the timeout it chose, at least twice and for more than half its life (a
`java.util.Timer`, a cleaner, a periodic poll); the recording says which waits timed out,
so no list is needed. One wait that timed out is still a stall, and a warning names the
five threads with the most time set aside and counts the rest.

**Always busy.** A thread the sampler never caught at an idle point, seen often enough to
have been running the whole time, gets a warning: its `BUSY` and `SATURATED` stalls are
one stretch of work, which for a batch thread is its job and for an event loop is lag.
They stay in the list either way.

**Culprits.** A busy run is named after the frame most of its samples share: the
innermost frame outside the JDK. On a program written over a framework or another
language's runtime that frame is always the framework's (`clojure.lang.RT.get`), so
`stalls --app` takes the same prefixes as `alloc --app` and names the innermost frame under
one of them instead, falling back to the default for a sample that never enters them.

**`--idle none`** turns all of this off, and in `stalls` it also empties the list of idle
frames, so no sample is idle: a loop sitting in its selector counts as working.

**Evidence.** Every stall says how it was found: nothing after the detail means a
blocking event, exact to its timestamps; `[samples]` means a run of sampler observations;
`[silence]` means an absence of samples explained by what covered it.

**Seen running meanwhile.** A park on a lock names no holder, and a monitor's holder can be
unknown. Under such a stall, `stalls` lists up to three other threads, watched or not, that
the sampler saw running while it lasted, most Java samples first, each with its sample counts
and the innermost frame most of them had (and the site, under `--app`). Samples in a native
wait that runs nothing (an accept, a socket poll, a wait for a child process, a blocking
read, a sleep) do not count. The list is labelled a correlation, never a holder, and a stall
with no such sample has no list ([design/stalls.md, section 6](design/stalls.md#6-who-ran-meanwhile)).

**Unseen.** Anything a blocking event or a pause event explains is exact; anything that
rests on samples alone is only as good as the sampling cadence. `jfrq` measures that
cadence per thread, and `stalls` opens with an `Unseen` line when it is too coarse:
first, in one line, how short a stall no event explains can be and still go unseen on the
threads you asked about; then, in an `UNSEEN` section that closes the report, whether the sampler's pace or the thread's own
absences are why, and the sampling period that would help.

## 12. `health`

`health` reads what the other commands leave: the collector's own events, the JVM's
once-a-second statistics and the throwables it created.

**Findings.** An `OutOfMemoryError` for direct memory, a failed evacuation, a full
collection, GC time or pauses over the collector's own goals, a collection forced by a
humongous allocation, metaspace or `System.gc()`. A finding is only something the JVM
itself reported, each with when it happened (`5 collections caused by Metadata GC Threshold, from +0.487s to +1.152s` is a
JVM starting; the same five spread over an hour are classes loaded faster than they are
unloaded).

**Trends** are numbers without a verdict: each series has its start, end, range and the
floor of its first and last thirds, so heap after GC whose floor climbs is growth that did
not come back down, and whether that matters is yours to say. When the floor of heap
after GC rose, by more than its printed figures round away, it says the recording cannot
tell what holds the heap (`jdk.OldObjectSample` names where objects were allocated), and
that a class histogram or a heap dump can.

**CPU.** Under the trends, `health` adds the CPU the Java threads used by their own
readings, and on macOS warns at the top when the JVM's own figure (`jdk.CPULoad`) is below
it: a process uses at least what its threads do, and on one JDK 21.0.3 macOS soak the JVM
reported 0.2 % while its threads reported 8.0 %. (On Linux the JVM's figure divides by the
host's CPUs, so the two do not compare.) The cause there was JDK-8326446: on Apple silicon,
JDKs before 17.0.13, 21.0.4, 22.0.2 and 23 write the JVM's figure at 3/125 of the truth, and
`health` names the bug for such a JVM whatever its threads read.

**Native memory.** A JVM run with `-XX:NativeMemoryTracking=summary` writes NMT's
committed memory by category, which `health` lists with the same floors; resident set
minus committed heap is not native growth, because the heap becomes resident as it is
touched.

**Throwables** are counted exactly from `jdk.ExceptionStatistics` and ranked from
`jdk.JavaExceptionThrow`, which fires in the constructor: a site is the code that made the
throwable, past its own constructors and factory. JFR records an `OutOfMemoryError` only
when Java code constructs one (direct buffer memory): the JVM makes its own, for the heap
or metaspace, and every `StackOverflowError`, without running a constructor, so they never
reach the file. A failed evacuation is the step before a heap one. A few
`java.lang.NoSuchMethodError`s whose message names `java.lang.invoke.Invokers$Holder` are
the JDK linking method handles, thrown and caught inside the JDK; they are not a fault.
When a recording says `jdk.JavaExceptionThrow` was off, `health` says so and prints the
setting that turns it on ([RECORDING.md](RECORDING.md), section 3).

**Several recordings.** `jfrq health a.jfr b.jfr c.jfr` reads the recordings concurrently
and answers with one table, a row per recording in the order given (the nodes of a
cluster, or a JVM's successive runs): span, findings, GC time, heap after GC floors,
resident set, live threads, threads started, the JVM's and the threads' CPU, and
throwables per second, then each recording's findings under its name.

## 13. jfrq-live watch, histo and growth

```
jfrq-live <pid> watch --every D --for D [--sample 60s] [--histo] [--ask 'QUESTION' ...] [--out DIR] [--recording ID|NAME] [--state DIR]
jfrq-live <pid> histo [--out FILE]
jfrq-live growth H1 H2 [H3 ...] [--top N]
```

`watch` follows a JVM for `--for` and exits by itself, in one process over one JMX
connection. Every `--every` it takes a window, a `delta` dump into `wNN-HHmm.jfr` (a
`full` one when the JVM has no cursor yet), and runs each `--ask` question on it into
`wNN-HHmm.<command>.txt`; every `--sample` it writes a row of the JVM's CPU, heap, threads,
open file descriptors and committed virtual memory to `samples.tsv`; with `--histo` it
takes a class histogram at the start and after each window, each one a full GC in the JVM.
It ends with a summary table, a row per window, on standard output and in `summary.txt`.
`--out` is a new or empty directory (default `watch-<pid>-<yyyyMMdd-HHmm>`, UTC).

```
jfrq-live 4242 watch --every 30m --for 2h --histo --ask health --ask 'hot --app com.example'
```

`histo` prints the JVM's class histogram as `jcmd <pid> GC.class_histogram` does, or
writes it to `--out`. `growth` reads two or more such histograms, oldest first, and lists
the classes whose instances and bytes both grew from each to the next, then, in a second
table, those whose bytes grew at every step while their instances did not (larger arrays
or buffers), each with its counts in every file, ranked by byte growth. Each dump also
holds 128 KiB in the target for 400 minutes, a JDK behaviour. Mechanics, the sample columns,
the cost of `--histo` and of each dump: [LIVE.md, section 7](LIVE.md#7-watching-a-jvm-for-a-while-watch-histo-growth).

## 14. A killed JVM

A JVM that was killed leaves its repository directory of chunk files rather than a
recording, and `jfrq` given that directory (or the `repository=` one above it) says what
to do: `jfr assemble DIR out.jfr` joins the chunks, the last of which, the one the JVM was
writing, is unfinished and unreadable by any reader; `jfrq` then names the size to cut
`out.jfr` to so it holds only the finished ones. A repository of a single chunk holds
nothing readable.

## 15. Exit status

| Status | When |
|---|---|
| 0 | success |
| 1 | the recording cannot be read (missing, not a recording, truncated inside its first chunk, still being written), the HTML report cannot be written, or standard output cannot be written |
| 2 | a usage error |

Every option is checked before the recording is opened, so these are usage errors too: a
directory given as the recording, and an `--html` target that is a directory, sits in a
directory that is missing or not writable, or is one of the recordings being read.
