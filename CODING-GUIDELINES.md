# Coding guidelines

jfrq follows the [Java Coding Standard v2.0.1](https://github.com/marregui/java-standard/blob/v2.0.1/STANDARD.md). The
rules live there and are cited by ID (`G-<section>.<n>`); this file is jfrq's Apply section (§12 of the standard), its
parts in the standard's order, and does not restate them.

## 1. Version

[v2.0.1](https://github.com/marregui/java-standard/blob/v2.0.1/STANDARD.md), on JDK 25 (Temurin; the Gradle toolchain
of every module, `.sdkmanrc`). Its rule IDs are the ones this repository's code and comments cite.

## 2. Hot paths

Everything that runs per event: `JfrReader` and its sinks, the collectors, `core/coll`, the `Interner` and
`core/jfr/Events`. The measurement that put them there is `docs/design/README.md` §5: a 38 MB, 1.4-million-event
recording, where the per-event path is the cost of every command. G-1, G-2, G-3 and G-10.7 apply there and nowhere
else; the report objects and the text, HTML and JSON writers are final reporting (G-2.3).

## 3. Applied

Per-event path 2026-09-18; G-10.2 to every module 2026-09-21; v2.0.1 2026-10-30. Enforced by the build:
`-Xlint:all -Werror` and doclint (G-10.10); the standard's Checkstyle configuration at v2.0.1, unchanged
(`config/checkstyle/checkstyle.xml`, `projectPackagePattern` `^dev\.jfrq\.`): `UnusedImports`, `AvoidStarImport` and
`CustomImportOrder` (G-10.11), `NeedBraces` (G-10.8), `FinalLocalVariable` on locals, enhanced-`for` variables, catch
parameters and parameters (G-10.2); JaCoCo floors (85 % `core`, 80 % `cli` and `live`).

- Primitive collections in `core/coll` (`ObjObjHashMap`, `IdentityObjObjHashMap`, `ObjLongHashMap`,
  `LongObjHashMap`, `ObjHashSet`, `ObjList`, `LongList`), with the `keyIndex()` sign convention, backward-shift
  delete and the `Quick` / `At` / `AtSlot` naming.
- Sentinels: `Nulls`, `parseNanosQuiet`, `periodNanos` / `thresholdNanos`, `culpritOrNull`.
- Reuse: `of()` on the scratch objects, `clear()` per candidate, interner tables probed with raw components so a hit
  allocates nothing; `int` event tags resolved once per event type.
- G-10.2 on main and test sources: 1901 declarations gained `final` (2026-09-21, a javac-tree rewriter, verified by
  the build and a second run reporting nothing left); Checkstyle has checked it since 2026-10-30. A bare local or
  parameter is reassigned below, or, for a local, assigned once after a declaration without an initialiser.
- G-10.11 (2026-10-30): JDK, third-party and project groups, static imports last; 50 files reordered.
- G-10.5: test methods are named for the behaviour they prove.
- G-5.7: no caller derives meaning from an exception's message text.

## 4. Departures kept on purpose

These are design decisions; changing them is a design change, not a local edit.

- G-1.8: the analysis input is records (`Sample`, `Block`, `Wait`, `Pause`) with an `Interval` each, allocated per
  event. The analysis API and the pure-logic tests are built on them; flattening them needs a new timeline
  representation and a new test fixture.
- G-2.3/G-2.4: report objects (`AllocationReport`, `ContentionReport`, `StallReport`) and the text and HTML writers
  use `java.util` collections, `Optional`, `String.format` and `StringBuilder`. That is final reporting; the sink
  protocol would not change the output or its cost.
- The JDK exposes event timestamps only as `Instant`, so `getStartTime()` allocates once per read; the value is
  converted to `long` immediately and nothing else is kept.
- G-10.8, signature wrapping: a signature over the line limit wraps where it is long, not one parameter per line.

## 5. Not applied

Rules that do not fit, with the reason; a site that does not follow one of these is not a finding.

- G-3.3 object pools: every per-event object that is not interned is retained by the analysis, so nothing returns
  to a pool.
- G-4.7: no native memory in main code (`NativeThreads`, a test helper, is the one `Arena`).
- G-6.1 to G-6.4: no hot-path logger. G-6.5: no logger at all (part 11).
- G-7.1 to G-7.5 and G-7.9: no worker pool.
- G-9.1 to G-9.4: no configuration interface; each command's options resolve, once, into a record of its own.
- G-11.1 to G-11.3: no leak-check harness, fluent assertion builder or fault-injecting facade. Tests that start
  JVM-global resources (recordings, threads) release them in `finally`.
- G-11.4: one printed, replayable seed per randomised test; test order is JUnit's default.
- G-14.7, G-14.10 to G-14.12, G-15.5, G-15.14: a command-line tool, no service; `FlightRecorderMXBean` cannot carry
  a trace id.

Not applied yet. These are not waived: findings against them are still reported; each line is the plan.

- G-11.7: 377 of 394 test methods and 16 of 33 test classes have no doc comment, and the 17 that do use `/** */`.
  Plan: `///` on each test as it is touched; a pass over `core` first.
- G-10.8, line limit: 218 lines are over 120 characters. Plan: rewrap as touched; no bulk reformat.
- G-4.3: `live` suppresses an `Error` from cleanup in 7 places (for example `Snapshot.chain`). Plan: fix with the next `live`
  change.
- G-7.13 (12 `volatile` fields that do not name their threads), G-7.14 (2 unnamed thread sources), G-13.5 (test-only
  methods without `@TestOnly`), G-14.13 (time values without their unit, about 100 by sample), G-16.3 (29 unused
  variables not `_`), G-16.10 (5 `var` with a factory on the right, in tests): as touched.
- Defects found by the v2.0.0 audit (2026-10-30), to fix: G-1.7 (`LongList`, `ObjList` growth clamps instead of
  failing, past about 2^31 elements), G-13.6 (`jfrq-live --set` accepts a bare `0` that the JDK drops), G-15.1 (no
  timeout on JMX calls in `live`), G-15.9 (the cursor file has no version), G-15.10 and G-16.13/G-16.4 (`Main.readAll`:
  one unbounded virtual thread per file, parsing on it, and a failed read discards the others' results).
- G-1.5: `Hashes.spread(long)` folds without a multiply.
- G-14.6: the phases `--timing` prints are not JFR events.

## 6. Project packages

`^dev\.jfrq\.` (the `ci/` source programs have no package).

## 7. Preview features

None.

## 8. Doc comments in main code

`/** */`.

## 9. Nullability annotations

None. Absence is a sentinel (G-1.2) or an `…OrNull` name plus javadoc.

## 10. Licence header

```
// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0
```

## 11. Logging

None: the CLI answers on stdout and reports failures on stderr. No hot-path logger.

## 12. Test framework

JUnit Jupiter 6.1.3: `@Test`, `@ParameterizedTest`, `@TempDir`, `@BeforeAll`.

## 13. Entry points, configuration, latency budgets

Entry points: `dev.jfrq.cli` (`Main.run`, `Main.check`, `Args`, public for `live`), `dev.jfrq.core.jfr`
(`JfrReader.read`, throws `IOException`), `dev.jfrq.live` (`Jvm.attach`, `Snapshot.take`, throw `IOException`).
Configuration type: none of the project's own (per-command records). Latency budgets: none; `docs/design/README.md`
§5 holds measurements, not budgets. Measure with `--timing` before and after each further step.

## 14. Line limit

120 characters.
