# Design: `waits`

Part of the [design](README.md). How `waits` turns monitor waits and parks into what
threads waited on, from the waiting thread's side, split by the code that waited.

**Events.** `jdk.JavaMonitorWait` (`Object.wait`) and `jdk.ThreadPark` (`LockSupport.park`
and everything built on it: `ReentrantLock`, `Condition`, the `java.util.concurrent`
queues). Both are thresholded: `20 ms` in `default`, `10 ms` in `profile` (JDK 25's
`lib/jfr/*.jfc`), so a wait shorter than its threshold is not in the file and the totals
are those of the waits above it. The `Thresholds` line prints both as recorded;
`run2/d-1257.jfr` was recorded at `10 ms` for waits and `1 ms` for parks. `locks` reads
neither the same way: it leaves monitor waits out (a thread in `wait()` chose to wait for a
notification) and reads parks as contention for a lock, holder and all. `waits` asks
another question, what the waiting thread was doing, so it reads both and never looks at
the notifier or the holder.

**Rows.** A row is an event type and the class waited on: the monitor's class for a wait
(`monitorClass`), the blocker's class for a park (`parkedClass`), `(no blocker)` for a
park without one. A wait and a park on the same class are two rows, since one is a
notification and the other a lock or a condition. `--thread` filters by the waiting
thread's name and `--monitor` by the class, by JVM name or as printed (`byte[]`); each
filter runs once per thread and once per class, not per event.

**Sites and the frame below.** Each row is split by site, the `SiteKey` `hot` and `alloc`
use (the innermost frame outside the JDK, or under `--app`'s prefixes), and each site by the
frame below it, by the rule `hot` uses (`SiteKey.below`): the outermost frame under the site
that is outside the JDK, else the frame the site called. No rule names a toolkit: to split
by a library's frames, `--app` names its package, a JDK package included. On `d-1257.jfr`,
the event thread's waits on the Metal render queue's flusher:

| Command | Site | Time | Waits | Below |
|---|---|---|---|---|
| no `--app` | `FlatEditorPaneUI.paintSafely` | 23.4 s | 1,030 | `BasicTextUI.paintSafely` |
| `--app javax.swing.text.html` | `CSSBorder$SolidPainter.paint` | 19.0 s | 829 | `Graphics.fillPolygon` |
| `--app javax.swing.text.html` | `ParagraphView.paint` | 4.34 s | 201 | `ParagraphView.paint` (`javax.swing.text`) |

Of all 1,137 waits (25.3 s, longest 56.7 ms), 103 (1.84 s) have no frame outside the JDK in
their recorded stack; their site is the leaf, `Object.wait0`. 1,033 of the 1,137 stacks are
cut at the recording's 64-frame depth; a site deeper than the depth is not in the stack,
and the key falls back to the innermost frame outside the JDK that is.

**Clipping.** A wait that began before the recording or outlived it is counted for the part
inside the span, as `locks` counts it (`Interval.clampTo`), and the report says how many
were cut; a wait wholly outside the span is not reported. The span is known only after the
pass, so the read keeps each wait's start and end and clips in `finish()`.

**Cost.** Per kept event: two filter verdicts and a class lookup, all probes into tables
filled once per thread and per class, then three appends (start, end, interned stack). The
site and the frame below it are resolved once per distinct stack in `finish()`. Measured
with `--timing`, no filter, median of three runs, against `info` on the same file:

| File | Size | Waits + parks | `waits` read + analyse | `info` read + analyse |
|---|---|---|---|---|
| `run2/d-1357.jfr` | 20 MB | 25,140 + 122,465 | 228 + 24.0 ms | 258 + 4.3 ms |
| `jfrq-dogfood-3/exit-final.jfr` | 41 MB | 1,776 + 117,812 | 268 + 19.3 ms | 286 + 11.1 ms |

**Checked against.** Two scripts over `jfr print --stack-depth 64` output of `d-1257.jfr`.
`edt_flush_ab.py` (waits on `MTLRenderQueue$QueueFlusher` by `AWT-EventQueue` threads):
25.3 s in 1,137, under `CSSBorder` 19.0 s in 829, longest 57 ms; `jfrq waits --thread
'AWT-EventQueue-*' --monitor '*QueueFlusher'` gives the same, the `CSSBorder` share under
`--app javax.swing.text.html`. `edt_park_sites.py` (the EDT's parks on
`ReentrantLock$NonfairSync` by the first application frame): `SessionsSidebar$SessionList.paintComponent`
18.4 s in 1,660, `MovableTabs.paint` 6.84 s in 681, `LockVeil$1.paint` 3.03 s in 313; `jfrq
waits --thread AWT-EventQueue-0 --monitor '*ReentrantLock$NonfairSync' --app dev.marregui`
gives the same rows.
