# Design: `hot`

Part of the [design](README.md). How `hot` turns execution samples into where the CPU went:
families, sites, the frame below a site, leaf methods and a timeline.

**Events.** `jdk.ExecutionSample` only. At each period (`10 ms` in `profile`, `20 ms` in
`default`; the `Sampling` line prints it) the JVM walks a few threads, not all of them, and
writes one event for each it finds running Java code; with more runnable threads than that,
each is sampled less often (measured on JDK 25: 12 spinning threads got about 26 samples a
second each, 3 got about 87). The choice rotates, so a family's share of the samples is its
share of the Java threads' CPU as the sampler saw it (a lone spinning thread among 12 read
7.4 % against 7.7 % expected), but a count is not a per-thread time. `jdk.NativeMethodSample` is left out: it says
which native method a thread is in, and a thread blocked in `kevent` or a socket read is in
one, so counting it would rank waiting as CPU. The figures are sample counts, not time: a
count times the period is an estimate of CPU time only when the sampler kept its period,
which a loaded JVM does not promise ([stalls.md, section 3](stalls.md#3-sampling-cadence-is-measured-not-assumed)).

**Families.** A thread's family is the fold `info` uses: every run of digits becomes `N`, so
`pool-3-thread-7` and `pool-4-thread-1` are `pool-N-thread-N` and a pool is one row. A name
without digits is its own family. The fold is resolved once per thread, not per sample.

**Sites.** A site is the frame a `SiteKey` names, the rule `alloc --sites` uses: the
innermost frame outside the JDK, or with `--app` the innermost frame under one of the given
prefixes, falling back to the innermost frame outside the JDK for a stack that never enters
them. A recording names no application package (`sun.java.command` is a jar or a main
class), so `--app` is asked for, not guessed. Hidden-class frames are named without their
per-JVM address (`Watchdog$$Lambda.run`), so a lambda is its own row; a script that drops
frames it cannot parse (`jfr print` shows the address) attributes those samples to the next
frame out instead.

**Below a site.** The site says whose code was running; it does not say what that code was
doing when it is a container. On comando's EDT (`run5/d-2059.jfr`), 776 samples have
`MovableTabs.paint` as their innermost application frame, and the frame right below it is
`JComponent.paint` in all 776: the immediate callee splits nothing. The frame `hot` reports
below a site is the outermost one under it that is outside the JDK, which is the first code
the container dispatched to that is not the JDK's own plumbing: `FlatTabbedPaneUI.update`
358, `FlatTabbedPaneUI.paintTabArea` 323, `FlatButtonUI.update` 47 on that file. Where every
frame below the site is JDK code, the frame the site called says what it asked the JDK for:
`EdtWatchdog.post` calls `SwingUtilities.invokeLater` in 67 of its 69 samples, which the leaf
(`HashMap.putVal`, `getNode`) would scatter; the leaf table has the leaves. Where the site is the leaf, the row says `(the site itself)`. The component being painted is
not in the stack (a frame carries a method, not its receiver), so a class name is the
closest the recording can come.

**Cost.** The read keeps, per family, a count per distinct stack (stacks are interned, so
the key is compared by identity first) and each sample's time; the site, the frame below it
and the leaf are resolved once per distinct stack in `finish()`. On `d-2059.jfr` (30 min,
2,168 samples, 15 MB) the analysis takes 14 ms of a 200 ms read.

**Timeline.** Buckets are aligned to multiples of `--bucket` since the epoch, so a 5-minute
bucket starts at :00, :05 and so on, in UTC, and the same bucket in two recordings is the
same wall-clock stretch. Empty buckets are kept: a stretch of idle reads as zeros, not as a
missing row. A bucket width that would make more than 1,000 buckets over the samples' span
is widened to the smallest multiple of it that fits, so the buckets stay aligned; the report
prints the width it used. `--bucket infinity` is a usage error.

**Checked against.** `hotpaths.py` (a script over `jfr print --events jdk.ExecutionSample`
output) on `run5/d-2059.jfr`: AWT-EventQueue-N 1,808 samples (83.4 %), watchdog 123
(5.7 %), `claude-strand-Edge Project Manager` 52 (2.4 %), total 2,168, the same in both;
the script's per-site counts are higher only where it skipped lambda frames.
