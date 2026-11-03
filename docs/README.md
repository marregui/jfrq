# Documentation

| Document | Read it to |
|---|---|
| [TUTORIAL.md](TUTORIAL.md) | Diagnose four injected event-loop faults on the bundled Netty demo, step by step (about fifteen minutes) |
| [USAGE.md](USAGE.md) | Look up a command, an option, an output rule or an exit status |
| [RECORDING.md](RECORDING.md) | Make a recording that holds what `jfrq` needs: what JFR records, and the recommended settings |
| [LIVE.md](LIVE.md) | Ask a running JVM with `jfrq-live`: dumps, the cursor, deltas, bounds; follow one for hours with `watch`; compare class histograms with `histo` and `growth` |
| [JSON.md](JSON.md) | Read `--json` output from a program: every field and its unit |
| [design/](design/README.md) | Understand how each answer is reached, which JFR events it rests on, and what it cannot see |

## Design

| Document | Covers |
|---|---|
| [design/README.md](design/README.md) | The read pass, output, damaged files, testing, performance, known limits |
| [design/stalls.md](design/stalls.md) | `stalls`: idle detection, evidence, sampling cadence, pauses, merging |
| [design/locks.md](design/locks.md) | `locks`: waiting for work, holder resolution, convoys, moved locks |
| [design/hot.md](design/hot.md) | `hot`: execution samples by family, site and the frame below it, the timeline |
| [design/procs.md](design/procs.md) | `procs`: process starts by command, thread family and site, rates |
| [design/waits.md](design/waits.md) | `waits`: monitor waits and parks by class, site and the frame below it, thresholds, clipping |
| [design/alloc.md](design/alloc.md) | `alloc`: the weighted estimate, first samples, counters, sites, `--baseline` |
| [design/health.md](design/health.md) | `health`: findings, trends, throwables, thread CPU, native memory |
| [design/info.md](design/info.md) | `info`: settings, thread families and census, attached threads, starts |

## Pictures

Every picture is in [images/](images/). The infographics are hand-drawn SVG in one light style: paper ground `#FBF8F1`, ink `#1F1D1A` for text, `#5C574E` for secondary text and footers, ochre `#8A6A1F` for kickers, commands and file names, `#C9A74A` for rules and frames; Helvetica for text, Menlo for commands; a footer naming the document the picture is in.

| Picture | What it shows | Where it appears |
|---|---|---|
| [questions.svg](images/questions.svg) | Each command, the question it answers and the main JFR events it reads; what `jfrq-live` adds | [README](../README.md#why) |
| [watch.svg](images/watch.svg) | A `watch` on its time line: windows, samples between them, what is written at each window's end and at the end | [LIVE.md §7](LIVE.md#7-watching-a-jvm-for-a-while-watch-histo-growth) |
| [stalls-report.png](images/stalls-report.png) | The `stalls` HTML report on the Netty demo (a screenshot of the report itself) | [README](../README.md) |
