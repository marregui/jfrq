# Documentation

| Document | Read it to |
|---|---|
| [TUTORIAL.md](TUTORIAL.md) | Diagnose four injected event-loop faults on the bundled Netty demo, step by step (about fifteen minutes) |
| [USAGE.md](USAGE.md) | Look up a command, an option, an output rule or an exit status |
| [RECORDING.md](RECORDING.md) | Make a recording that holds what `jfrq` needs: what JFR records, and the recommended settings |
| [LIVE.md](LIVE.md) | Ask a running JVM with `jfrq-live`: dumps, the cursor, deltas, bounds |
| [JSON.md](JSON.md) | Read `--json` output from a program: every field and its unit |
| [design/](design/README.md) | Understand how each answer is reached, which JFR events it rests on, and what it cannot see |

## Design

| Document | Covers |
|---|---|
| [design/README.md](design/README.md) | The read pass, output, damaged files, testing, performance, known limits |
| [design/stalls.md](design/stalls.md) | `stalls`: idle detection, evidence, sampling cadence, pauses, merging |
| [design/locks.md](design/locks.md) | `locks`: waiting for work, holder resolution, convoys, moved locks |
| [design/hot.md](design/hot.md) | `hot`: execution samples by family, site and the frame below it, the timeline |
| [design/alloc.md](design/alloc.md) | `alloc`: the weighted estimate, first samples, counters, sites, `--baseline` |
| [design/health.md](design/health.md) | `health`: findings, trends, throwables, thread CPU, native memory |
| [design/info.md](design/info.md) | `info`: settings, thread families and census, attached threads, starts |
