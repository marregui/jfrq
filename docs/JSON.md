# JSON output

`--json` makes any `jfrq` command print one JSON document on standard output instead of
the text report.

- It is built from the same report objects as the text and the HTML, and `--top N` bounds
  its lists the same way, so the three carry the same numbers.
- Every list `--top` cut has a count beside it (`…Found`). The exceptions are `convoys`,
  whose search stops at `--top`, and `longest`, whose count is `waits`.
- `--html` still writes its file.
- Under `jfrq-live`, a question with `--json` after `--` has standard output to itself:
  the dump's own lines (`Dumped`, `Window`, `Cursor`) go to standard error.
- Both tools write UTF-8 on every platform, text reports included. On Windows the default
  would otherwise be a code page such as cp1252.

## Conventions

- **Schema.** Every document starts with `tool` (`"jfrq"`), `version` (the tool's),
  `schema` (an integer, now `1`) and `command`. A field may be added without changing
  `schema`; renaming or removing one, or changing what it means, changes `schema`. A fix
  that makes a field measure what this document already says it measures does not change
  `schema`.
- **Units are in the names.** `…Nanos` is a duration or offset in nanoseconds, `bytes` and
  `…Bytes` are bytes, `bytesPerSecond…` is a rate, `share` is a fraction of 1, `ratio` is a
  relative change (`0.5` is +50 %).
- **Instants** are ISO-8601 strings in UTC (`2026-09-25T07:15:34.363375Z`). An event also
  has `offsetNanos`, its distance from the recording's start, which is what the text prints
  as `+13.682s`.
- **Absent is `null`, never 0.** A value the recording cannot give (a threshold that was
  not set, a cadence with no pair of samples, a life count for a virtual thread, a count of
  events of a type the recording did not enable) is `null`.
- **Enumerations** are upper-case names: `verdict` (`BLOCKED_MONITOR`, `PARKED`,
  `OBJECT_WAIT`, `SLEEP`, `BLOCKING_IO`, `BUSY`, `SATURATED`, `GC_PAUSE`, `SAFEPOINT`,
  `UNEXPLAINED`), `evidence` (`EVENT`, `SAMPLES`, `SILENCE`), `sight` (`CLEAR`,
  `NATIVE_SAMPLER`, `JAVA_SAMPLER`, `OWN_ABSENCE`), lock `kind` (`MONITOR_ENTER`, `PARK`),
  finding `kind` (`OUT_OF_MEMORY`, `EVACUATION_FAILED`, `FULL_GC`, `GC_TIME_OVER_GOAL`,
  `PAUSE_OVER_TARGET`, `HUMONGOUS_ALLOCATION`, `METASPACE_GC`, `SYSTEM_GC`), trend `unit`
  (`BYTES`, `COUNT`, `FRACTION`).
- **A stack** is `{"frames": [...], "culprit": ..., "truncated": ...}`: the innermost
  frames as a stack trace prints them (twelve at most; six for a lock site, the depth sites
  are grouped at), the innermost frame outside the JDK as `package.Class.method` (named even
  when it lies below the frames kept; `null` when every frame is the JDK's), and whether
  frames were left out. A lambda is named without the address the JVM gave its class
  (`Handler$$Lambda.run`), so the same code has the same name in every run; so is a hidden
  class wherever a class is named (`Pattern$$Lambda`, `LambdaForm$MH`). A stall with no stack (a pause, an unexplained gap) has
  `"stack": null`.

## Every document

| Field | |
|---|---|
| `recording.file`, `recording.path` | the file name, and the path as given, which tells a baseline and a current recording of one name apart |
| `recording.start`, `recording.end` | the data span, from the chunk headers |
| `recording.durationNanos` | its length |
| `recording.chunks` | how many chunks |
| `recording.warnings` | problems with the file itself (truncated, joined) |

## `info`

| Field | |
|---|---|
| `threadsSeen` | threads that are the thread of some event |
| `threadsAlive.atStart`, `.started`, `.ended`, `.atEnd` | the census of platform threads; alive at start plus started minus ended is alive at end |
| `settingsKnown` | whether the file carries its settings (`jdk.ActiveSetting`) |
| `thresholded`, `throttled` | the event types whose threshold suppresses something, and the throttled ones |
| `eventTypes[]` | `type`, `count`, `enabled`, `thresholdNanos`, `period` (as recorded, e.g. `"10 ms"`, `"everyChunk"`), `periodNanos`, `throttle` |
| `threadFamilies[]` | `family` (`pool-N-thread-N`), `glob` (a `--thread` value that matches every thread of the family; a family of one gets its name escaped, which matches only it, while a wildcard can also reach another family's thread: `Netty-worker-*` matches `Netty-worker-main`), `threads`, `seen`, `virtual` (how many are virtual threads: no census covers them, so their life counts are `null`), `aliveAtStart`, `started`, `ended`, `aliveAtEnd`, `attached` (how many native code attached to the JVM, whose starts carry no stack and no parent thread; `null` when the starts do not say), `cpuShare` (the family's share of the JVM's CPUs across the window, from `jdk.ThreadCPULoad`, without the readings `threadCpu.readingsLeftOut` counts; `null` when the recording has no readings or the family is outside them. The JVM's CPUs are its active processor count: the machine's, unless `-XX:ActiveProcessorCount`, a container limit or a CPU affinity mask sets fewer), `example` |
| `threadCpu` | `share` (every Java thread's, the same rule), `readings` (the readings counted), `readingsLeftOut` (two kinds, the VM's own `main` excepted. First, a reading whose stretch is not in the file: its thread has no start and no earlier reading in the file, and either the reading is at the first evaluation of a thread alive before the recording, or the recording has no `jdk.ThreadStart`. Second, the readings of a thread native code attached, up to and including its first below one core: they hold CPU the native thread used before the attach); `null` values without readings |
| `starts` | `null` without `--thread`; with it: `glob`, `threads`, `starts`, `attached`, `first`, `firstOffsetNanos`, `last`, `lastOffsetNanos`, `peaks[]` (`windowNanos` of 100 ms and of 1 s, the most starts `count` in one window of that width, and when the first such window began: `start`, `offsetNanos`), `creatorsFound`, `creators[]` (`site`: the innermost frame outside the JDK in the starting thread's stack, or `<attached from native code>`, or `<no stack>`; `starts`, `share`, `parents`: the threads that started them; `stack`, from the site down) |

## `stalls`

| Field | |
|---|---|
| `gapNanos` | the stall threshold applied |
| `samplingPeriodNanos.java`, `.native` | the sampler periods |
| `thresholdNanos` | per blocking event type |
| `threadsMatched` | threads the filter matched that had something to judge |
| `unseenHeadline` | the `Unseen` line: how short an unexplained stall these threads cannot show, and on how many; `null` when every thread's view is clear |
| `unseen[]` | the sentences the text report closes with, under `UNSEEN`: what the recording cannot show on these threads, why, and the setting that would help |
| `warnings[]` | the `WARNING` lines |
| `byVerdict[]` | `verdict`, `stalls`, `stalledNanos`, `worstNanos`, largest total first |
| `threadsWithStalls`, `threadsWithoutStalls` | the counts behind `threads` |
| `threads[]` | the threads that stalled, most stalled first: `thread`, `threadId`, `virtual`, `samples`, `javaCadenceNanos`, `nativeCadenceNanos`, `sight`, `unseenBelowNanos` (the shortest unexplained stall the samples can show; one longer than the recording means none, which the text prints as a dash), `stalls`, `stalledNanos`, `worstNanos` |
| `stallsFound`, `stalls[]` | the explained stalls, longest first: `thread`, `start`, `offsetNanos`, `durationNanos`, `verdict`, `evidence`, `detail`, `samples`, `stack`, `meanwhile` |
| `stalls[].meanwhile[]` | for a park on a lock, or a monitor enter whose holder is unknown, up to three other threads the sampler saw running during it, a correlation and not a holder; empty otherwise: `thread`, `javaSamples`, `nativeSamples` (samples inside the stall that are not idle), `frame` (the innermost frame most of them had), `frameSamples` (how many had it), `site` (the site under `--app`, `null` without it) |
| `unexplainedFound`, `unexplained[]` | the unexplained gaps, the same shape |
| `pausesFound`, `pauses[]` | JVM-wide pauses at least the gap long: `start`, `offsetNanos`, `durationNanos`, `kind` (`GC`, `SAFEPOINT`), `detail` |

## `locks`

| Field | |
|---|---|
| `thresholdNanos` | for `jdk.JavaMonitorEnter` and `jdk.ThreadPark` |
| `noContention` | the sentence the text prints when every wait was a worker waiting for work, else `null` |
| `blockedNanos`, `waits`, `clippedWaits` | the totals, and the waits cut to the recording's span |
| `movedNanos` | the part of `blockedNanos` on `movedByCollector` |
| `movedByCollector[]` | one thread's waits from one place over addresses that changed only at GC pauses, probably one object the collector moved (counted in the totals, not set aside): `thread`, `threadId`, `class`, `locks` (the addresses), `waits`, `totalNanos`, `chanceLog10` (the odds, as a power of ten, that chance put a pause in every change), `stack` (the longest wait's) |
| `locksFound`, `sitesFound`, `threadsFound` | how many rows `locks`/`sites` and `threads` had before `--top` |
| `locks[]` | without `--by-site`: `lock`, `class`, `kind`, `totalNanos`, `waits`, `maxNanos`, `waiters`, `heldBy`, `stack` (the longest wait's) |
| `sites[]` | with `--by-site`, instead of `locks`: `kind`, `totalNanos`, `waits`, `maxNanos`, `locks` (the instances), `waiters`, `heldBy`, `stack` |
| `threads[]` | `thread`, `threadId`, `virtual`, `totalNanos`, `waits`, `maxNanos`, `share` |
| `convoys[]` | each an array of waits, outermost first: `waiter`, `start`, `offsetNanos`, `durationNanos`, `lock`, `heldBy`, `handedOnThrough` |
| `waitingForWork` | `threads`, `parks`, `totalNanos`, `byShape` (locks recognised by shape rather than name), `queuesFound`, `queues[]` shaped like `locks[]` |
| `longest[]` | the longest waits, shaped like a convoy link with a `stack` |

## `health`

| Field | |
|---|---|
| `warnings[]` | what makes a figure in the document wrong: a JVM CPU total (`jdk.CPULoad`) from a JVM with JDK-8326446 (Apple silicon, before 17.0.13, 21.0.4, 22.0.2 and 23), which writes it at 3/125 of the truth; otherwise one below what its own Java threads used, on a recording made on macOS, where the two divide by the same count |
| `findings[]` | most serious first: `kind`, `count`, `first`, `firstOffsetNanos`, `last`, `lastOffsetNanos` (all `null` for `GC_TIME_OVER_GOAL`, which is about the whole window), `text` |
| `gc` | `null` counts when `jdk.GarbageCollection` (or, for `oldCycles`, `jdk.OldGarbageCollection`) was not recorded; `collections`, `byCollector` and `byCause` (objects, most first; a G1 concurrent cycle, `G1Old`, is in `byCollector` but not in `byCause`), `oldCycles`, `pauseNanos`, `pauseShare`, `longestPauseNanos`, `gcTimeRatio`, `pauseTargetNanos`, `maxHeapBytes` |
| `trends[]` | `series` (`Heap after GC`, `Resident set`, `Live threads`, `JVM CPU`, `Machine CPU`; one the recording has no events for is left out), `unit`, `points`, `start`, `end`, `min`, `max`, `mean`, `floorFirstThird`, `floorLastThird` (the lowest value in each; `null` under three points) |
| `threadsStarted`, `threadsPeak` | threads started in the window, and the most alive at once since the JVM started |
| `threadCpu` | as in `info`: the Java threads' share of the JVM's CPUs from their own readings |
| `nativeMemory[]`, `nativeMemoryCategories` | NMT's committed memory (`jdk.NativeMemoryUsage`, only with `-XX:NativeMemoryTracking`), shaped like `trends[]`: `Total` first, then the categories, largest at the end of the window first, `--top` of them; and how many categories there were. Empty without NMT |
| `throwables` | `created` (exact, between the first and last `jdk.ExceptionStatistics`), `createdNanos` (that stretch), `perSecond`, `events` (`jdk.JavaExceptionThrow`; `null`, as are `classesFound` and `sitesFound`, when it was off), `throttle`, `enableWith` (when the settings show `jdk.JavaExceptionThrow` was off, the setting that turns it on: `jdk.JavaExceptionThrow#enabled=true`; otherwise `null`), `errors` (`jdk.JavaErrorThrow` per class), `classesFound`, `byClass[]` (`class`, `events`, `share`, `perSecond` (over the whole window), `first`, `firstOffsetNanos`, `medianOffsetNanos`, `last`, `lastOffsetNanos` (when the class's first, median and last were created: a start-up burst has its median near its first, a steady rate near the middle of the window), `message`: one example), `sitesFound`, `bySite[]` (`site`, `class`, `events`, `share`, `stack`: from below the throwable's own construction) |

Given several recordings, the document has no `recording`; it has `reports[]` instead, one
per recording in the order given, each a `recording` object followed by every field above.

## `hot`

| Field | |
|---|---|
| `samples` | every `jdk.ExecutionSample` read |
| `siteKey` | how a site is chosen, in words |
| `bucketNanos`, `buckets[]`, `bucketTotals[]` | the timeline's bucket width (a multiple of `--bucket`, wider when that would make more than 1,000), each bucket's start (UTC) and its samples across families |
| `familiesFound`, `leavesFound` | how many rows each list had before `--top` |
| `families[]` | `family`, `threads`, `samples`, `share`, `sitesFound`, `sites[]` (`frame`, `samples`, `belowFound`, `below[]` (`frame`, `samples`)), `timeline[]` (samples per bucket, aligned with `buckets[]`) |
| `leaves[]` | `frame`, `samples`: the innermost frame of every sample |

`below[]` names the outermost frame outside the JDK under the site, else the frame the site called, else
`(the site itself)`. Every list is cut to `--top`.

## `procs`

| Field | |
|---|---|
| `processStarts`, `perMinute` | every `jdk.ProcessStart` read, and that count per minute of the recording's span |
| `siteKey` | how a site is chosen, in words |
| `commandsFound`, `familiesFound` | how many rows each list had before `--top` |
| `commands[]` | `command` (the basename of the command line's first word, `(empty)` when it has none), `starts`, `perMinute`, `example` (the earliest command line under that name, whole), `familiesFound`, `families[]` (`family`, `starts`), `sitesFound`, `sites[]` (`frame`, `starts`) |
| `families[]` | `family`, `threads` (how many of its threads started a process), `starts`, `perMinute`, `commandsFound`, `commands[]` (`command`, `starts`) |

Every list is cut to `--top`.

## `waits`

| Field | |
|---|---|
| `thresholdNanos` | `jdk.JavaMonitorWait` and `jdk.ThreadPark`: waits shorter than these are not in the file; `null` when the recording has no settings for one |
| `siteKey` | how a site is chosen, in words |
| `totalNanos`, `waits` | the time and count of every wait reported, each counted for the part inside the recording |
| `clippedWaits` | how many of them began before the recording or outlived it |
| `classesFound` | how many classes there were before `--top` |
| `classes[]` | `event` (`jdk.JavaMonitorWait` or `jdk.ThreadPark`), `class` (the monitor's or the blocker's JVM class name, `(no blocker)` for a park without one), `totalNanos`, `waits`, `maxNanos`, `sitesFound`, `sites[]` (`frame`, `totalNanos`, `waits`, `maxNanos`, `belowFound`, `below[]` (`frame`, `totalNanos`, `waits`)) |

`below[]` is chosen as in `hot`. Every list is cut to `--top`.

## `alloc`

| Field | |
|---|---|
| `warnings[]` | what to know before trusting the estimate |
| `source`, `samples`, `events` | the event the estimate rests on, and how much of it: `samples` are the events kept, without the first sample of each thread not seen starting in the recording |
| `estimatedBytes`, `bytesPerSecond` | the estimate |
| `counted` | whether the JVM's own per-thread counters were in the file; when they were: `countedBytes`, `countedThreads`, `estimatedOnCountedThreads`, `estimateError`, over the threads with a counter; `estimatedOnCountedThreads` is their estimate over the stretch each counter covers, from its first reading (or the thread's start) to its last, so the two compare like for like. A `threads[]` row's `countedBytes` is `null` unless that stretch holds 95 % of the row's `bytes` |
| `threadsFound`, `classesFound`, `packagesFound`, `sitesFound` | how many rows each list had before `--top` (the last two with `--sites`) |
| `threads[]` | `thread`, `bytes`, `countedBytes`, `bytesPerSecond`, `share`, `samples`, `topClasses[]` (`class`, `bytes`) |
| `classes[]` | `class` (the JVM name, `[B` for `byte[]`), `bytes`, `bytesPerSecond`, `share`, `samples` |
| `siteKey`, `packages[]`, `sites[]` | with `--sites`: how sites are keyed, the package roots seen (`package`, `share`), and `site`, `bytes`, `bytesPerSecond`, `share`, `samples`, `stacks`, `stack` |

With `--baseline`, `recording` is the current file and the document instead has
`baseline` (its own `recording` object and the estimate fields above), `current` (the
estimate fields), and `change`, `threads[]`, `classes[]` and, with `--sites`, `sites[]`
(each with its `…Found` count), each row with `bytesPerSecondBefore`, `bytesPerSecondAfter`, `bytesPerSecondChange` and
`ratio` (`null` when the baseline had nothing), plus `samplesBefore` and `samplesAfter`
where they apply: a change of several hundred percent resting on a handful of samples is
within sampling error.
