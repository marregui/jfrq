# Coding guidelines

jfrq follows the [Java Coding Standard v1.0.0](https://github.com/marregui/java-standard/blob/v1.0.0/STANDARD.md). The rules live there and are cited
by ID (`G-<section>.<n>`); this file is jfrq's Apply section (§12 of the standard) and does
not restate them.

## 1. Version

[v1.0.0](https://github.com/marregui/java-standard/blob/v1.0.0/STANDARD.md). Its rule IDs are the ones this repository's code and comments cite.

## 2. Hot paths

Everything that runs per event: `JfrReader` and its sinks, the collectors, `core/coll`, the
`Interner` and `core/jfr/Events`. G-1, G-2, G-3 and G-10.7 apply there and nowhere else; the
report objects and the text, HTML and JSON writers are final reporting (G-2.3).
G-7 applies only if a parallel pass over the file is introduced, and then G-7.1, G-7.2,
G-7.3 and G-7.5 are the ones to follow; G-4.7 becomes "every pooled object has an owner and
a baseline the tests can assert".

## 3. Applied

Per-event path 2026-09-18; G-10.2 to every module 2026-09-21. Enforced by the build:
`-Xlint:all -Werror` and doclint (G-10.10), Checkstyle `UnusedImports` (G-10.11), JaCoCo
floors (85 % `core`, 80 % `cli` and `live`).

- Primitive collections in `core/coll` (`ObjObjHashMap`, `IdentityObjObjHashMap`,
  `ObjLongHashMap`, `LongObjHashMap`, `ObjHashSet`, `ObjList`, `LongList`), with the
  `keyIndex()` sign convention, backward-shift delete and the `Quick` / `At` / `AtSlot`
  naming.
- Sentinels: `Nulls`, `parseNanosQuiet`, `periodNanos` / `thresholdNanos`,
  `culpritOrNull`.
- Reuse: `of()` on the scratch objects, `clear()` per candidate, interner tables probed
  with raw components so a hit allocates nothing; `int` event tags resolved once per
  event type.
- G-10.2 on main and test sources: 1901 declarations gained `final`. A javac-tree
  rewriter made the change; the build (`-Werror`, the test suites) and a second rewriter
  run reporting nothing left verified it. Declarations still bare are reassigned or of
  the exempt shapes the rule lists. `live`'s one stream became a loop (G-10.7).

## 4. Departures kept on purpose

These are design decisions; changing them is a design change, not a local edit.

- The analysis input is records (`Sample`, `Block`, `Wait`, `Pause`) with an `Interval`
  each, allocated per event. The analysis API and the pure-logic tests are built on them;
  flattening them (G-1.8) needs a new timeline representation and a new test fixture.
- Report objects (`AllocationReport`, `ContentionReport`, `StallReport`) and the text and
  HTML writers use `java.util` collections, `Optional`, `String.format` and
  `StringBuilder`. That is final reporting, which G-2.3 exempts; the sink protocol (G-2.4)
  would not change the output or its cost.
- The JDK exposes event timestamps only as `Instant`, so `getStartTime()` allocates once
  per read; the value is converted to `long` immediately and nothing else is kept.

## 5. Not applied

On purpose; a site that does not follow one of these is not a finding.

- G-3.3 object pools: every per-event object that is not interned is retained by the
  analysis, so nothing returns to a pool.
- G-5.2/G-5.3: exceptions carry prose messages from constructors. Almost every message
  reaches the terminal as the tool's answer to a user, so it is a sentence; no caller
  derives meaning from message text.
- G-9.1 and G-9.4, in part: each command's options are resolved and validated once, into
  a record per command, before the recording is opened; `Args` underneath stays a string
  map, and there is no configuration interface. The literal capacities left are initial
  sizes of growable tables, not pool sizes.
- G-9.2/G-9.3 (facades, three classes per configuration): there is one implementation of
  each, and the tests exercise the real filesystem and the real JFR parser.
- G-10.3: no nullability library (`core` depends on `jdk.jfr` only). Absence is a
  sentinel (G-1.2) or an `…OrNull` name plus javadoc; `@Override` and `@SuppressWarnings`
  still apply.
- G-10.5, test names only: descriptive sentence-style method names under JUnit 5, not
  `testXxx`. The rest of G-10.5 applies.
- G-10.8, signature wrapping only: multi-parameter signatures wrap where the line is long,
  not one parameter per line. The rest of G-10.8 applies.
- G-11.1, G-11.2, G-11.3: no leak-check harness, fluent assertion builder or
  fault-injecting facade (no facade exists to subclass). Tests that start JVM-global
  resources (recordings, threads) release them in `finally`.
- G-11.4: one printed, replayable seed per randomised test rather than two; test order is
  JUnit's default.

Not applied yet:

- G-11.7: test documentation is `/** */`; converting it to `///` is open.
- G-10.11, group order: 35 of 110 files put `jdk` or a library after the project's own
  imports; Checkstyle checks unused imports, not the order.
- G-6.5: jfrq has no logger; the CLI answers on stdout and reports failures on stderr.
- G-10.12: the thresholds in the stall and lock detectors carry their measurements
  (`Perch`, `docs/design/`); the rest of the code has not been checked against the rule.

## 6. Static imports

First, before the other groups (G-10.11).

Measure with `--timing` before and after each further step; `docs/design/README.md` §5 holds the
numbers.
