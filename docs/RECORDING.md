# Recording for jfrq

What JFR records, how it decides what to drop, and the settings that give `jfrq` a
recording it can answer from.

## 1. What JFR is

JFR is Java Flight Recorder: the JVM's built-in, low-overhead event recorder. It lives
inside HotSpot (JEP 328, open-sourced in JDK 11) and writes a binary `.jfr` file of
timestamped events, which is analysed offline.

What it records:

- Periodic samples: which threads are running and their stack traces
  (`jdk.ExecutionSample`, `jdk.NativeMethodSample`). This is the profiling side.
- Blocking events with exact durations: monitor contention (`jdk.JavaMonitorEnter`),
  waits, parks, socket and file I/O, safepoints, GC pauses; each with the thread, the
  stack and the object involved.
- Allocation events (`jdk.ObjectAllocationSample`), GC and heap statistics, thread start
  and end, compiler activity, OS and CPU load, JVM and GC configuration.
- Anchor metadata: the settings and thresholds that were active (`jdk.ActiveSetting`),
  so a reader knows what the recording could and could not see.

How it works:

- Per-thread buffers in the JVM, flushed to disk in chunks. Overhead is typically 1-2 %
  because the JVM emits the events itself; no agent instruments bytecode.
- Threshold-based: durations below a per-event threshold (20 ms for monitor enter in the
  default profile) are dropped, so the absence of an event is not the absence of the
  behaviour.
- Started with `-XX:StartFlightRecording`, `jcmd <pid> JFR.start`, or the `jdk.jfr` API
  in-process (which is what `netty-demo` does).
- Read with `jfr print` and `jfr summary`, JDK Mission Control, or programmatically via
  `jdk.jfr.consumer.RecordingFile` and `EventStream`; the latter is what `jfrq` uses.

`jfrq` exists because raw JFR output is a firehose of events with no verdict. It streams
the file once and turns those events into one answer per question.

## 2. The recommended settings

The JDK's `profile` settings are a good start. Lower the blocking thresholds so short
waits are in the file, and raise the allocation sample rate:

```
java -XX:StartFlightRecording=filename=app.jfr,settings=profile,\
jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.ThreadSleep#threshold=1ms,\
jdk.SocketRead#threshold=1ms,jdk.SocketWrite#threshold=1ms,jdk.FileRead#threshold=1ms,jdk.FileWrite#threshold=1ms,\
jdk.SocketRead#throttle=off,jdk.SocketWrite#throttle=off,jdk.FileRead#throttle=off,jdk.FileWrite#throttle=off,\
jdk.ExecutionSample#period=10ms,jdk.NativeMethodSample#period=10ms,\
jdk.ObjectAllocationSample#throttle=1000/s \
-jar app.jar
```

`jfrq stalls` and `jfrq locks` print the thresholds that were active, because a 20 ms
monitor threshold means no wait shorter than 20 ms exists in the file, whatever the
application did. The `profile` settings also throttle socket and file events to 300 per
second across the JVM (JDK 25); `jfrq stalls` warns when a throttle is in force, and the
line above switches it off so no blocking call goes unrecorded.

`jdk.JavaMonitorWait` (an `Object.wait`) is left at the `profile` threshold of 10 ms; what
lowering it costs has not been measured. `jfrq waits` reads it, so when the question is a
short `Object.wait` on a thread that should not wait (a UI thread flushing a render queue),
add `jdk.JavaMonitorWait#threshold=1ms` to the line; `jfrq waits` prints the threshold that
was active, and nothing shorter is in the file.

## 3. What `health` needs

`jfrq health` needs nothing beyond either JDK 25 settings file: both record the
collector's events, the once-a-second statistics, `jdk.ThreadCPULoad` and
`jdk.JavaExceptionThrow`, throttled to 100 per second in `default` and 300 in `profile`.
Under the throttle the class and site shares are of a sample; the total created is exact
either way.

When a recording says `jdk.JavaExceptionThrow` was off (JDK 21's `profile` leaves it off),
`health` says so and prints the setting that turns it on,
`jdk.JavaExceptionThrow#enabled=true`, which `-XX:StartFlightRecording` and
`jcmd <pid> JFR.start` both take.

A JVM run with `-XX:NativeMemoryTracking=summary` writes NMT's committed memory by
category, which `health` lists.

## 4. A JVM that is still running

A recording the JVM is still writing cannot be read in place: its last chunk is open.
`jfrq-live` takes a dump of a window and asks the question of that; its `start` command
starts a recording with the settings above. [LIVE.md](LIVE.md) explains how.
